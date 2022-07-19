/*
 * Copyright © 2022 Jordial Corporation <https://www.jordial.com/>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jordial.datimprint.cli;

import static com.globalmentor.io.Paths.*;
import static java.nio.charset.StandardCharsets.*;
import static java.nio.file.Files.*;
import static java.util.stream.Collectors.*;
import static org.fusesource.jansi.Ansi.*;
import static org.zalando.fauxpas.FauxPas.*;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.*;

import org.fusesource.jansi.Ansi;
import org.slf4j.Logger;
import org.slf4j.event.Level;

import com.globalmentor.application.*;
import com.jordial.datimprint.file.*;

import picocli.CommandLine.*;

/**
 * Command-line interface for Datimprint.
 * @author Garret Wilson
 */
@Command(name = "datimprint", description = "Jordial's command-line interface for data statistics, fingerprint, and verification")
public class DatimprintCli extends BaseCliApplication {

	/**
	 * Constructor.
	 * @param args The command line arguments.
	 */
	public DatimprintCli(@Nonnull final String[] args) {
		super(args, Level.INFO);
	}

	/**
	 * Main program entry method.
	 * @param args Program arguments.
	 */
	public static void main(@Nonnull final String[] args) {
		Application.start(new DatimprintCli(args));
	}

	/**
	 * Generates a data imprint of the indicated file or directory tree.
	 * @param argDataPaths The files or base directories of the data for which an imprint should be generated.
	 * @param argCharset The charset for text encoding.
	 * @param argOutput The path to a file in which to store the output.
	 * @param argExecutorType The particular type of executor to use, if any.
	 * @throws IOException If an I/O error occurs.
	 */
	@Command(description = "Generates a data imprint of the indicated file or directory tree. The output will use the default console/system encoding unless an output file is specified. The system line separator will be used.", mixinStandardHelpOptions = true)
	public void generate(
			@Parameters(paramLabel = "<data>", description = "The file or base directory of the data for which an imprint should be generated. Multiple data sources are allowed.", arity = "1..*") @Nonnull List<Path> argDataPaths,
			@Option(names = {"--charset",
					"-c"}, description = "The charset for text encoding.%nDefaults to UTF-8 if an output file is specified; otherwise uses the console system encoding unless redirected, in which case uses the default system encoding.") Optional<Charset> argCharset,
			@Option(names = {"--output",
					"-o"}, description = "The path to a file in which to store the output. UTF-8 will be used as the charset unless @|bold --charset|@ is specified. The system line separator will be used.") Optional<Path> argOutput,
			@Option(names = {
					"--executor"}, description = "Specifies a particular executor to use for multithreading. Valid values: ${COMPLETION-CANDIDATES}") Optional<PathImprintGenerator.Builder.ExecutorType> argExecutorType)
			throws IOException {

		final Logger logger = getLogger();

		logAppInfo();

		final Charset charset = argCharset.orElse(argOutput.map(__ -> UTF_8).orElseGet( //see https://stackoverflow.com/q/72435634
				() -> Optional.ofNullable(System.console()).map(Console::charset).orElseGet(Charset::defaultCharset)));
		logger.info("{}", ansi().bold().fg(Ansi.Color.BLUE).a(
				"Generating imprint for %s ...".formatted(argDataPaths.stream().map(Path::toAbsolutePath).map(path -> "`%s`".formatted(path)).collect(joining(", "))))
				.reset());
		final Duration timeElapsed;
		try (final GenerateStatusPrinter statusPrinter = new GenerateStatusPrinter()) {
			final OutputStream outputStream = argOutput.map(throwingFunction(Files::newOutputStream)).orElse(System.out);
			try { //manually flush or close the output stream and writer rather than using try-with-resources as the output stream may be System.out
				final Datim.Serializer datimSerializer = new Datim.Serializer();
				final Writer writer = new BufferedWriter(new OutputStreamWriter(outputStream, charset));
				try {
					datimSerializer.appendHeader(writer);
					final AtomicLong counter = new AtomicLong(0);
					final Consumer<PathImprint> imprintConsumer = throwingConsumer(imprint -> datimSerializer.appendImprint(writer, imprint, counter.incrementAndGet()));
					final PathImprintGenerator.Builder imprintGeneratorBuilder = PathImprintGenerator.builder().withImprintConsumer(imprintConsumer);
					if(!isQuiet()) { //if we're in quiet mode, don't even bother with listening and printing a status
						imprintGeneratorBuilder.withListener(statusPrinter);
					}
					argExecutorType.ifPresent(imprintGeneratorBuilder::withGenerateExecutorType);
					try (final PathImprintGenerator imprintGenerator = imprintGeneratorBuilder.build()) {
						for(final Path dataPath : argDataPaths) {
							datimSerializer.appendBasePath(writer, dataPath);
							imprintGenerator.produceImprint(dataPath);
							//TODO check for error
							//At this point the entire tree has been traversed. There may still be imprints being produced (i.e written),
							//but starting generating and producing imprints for another tree will cause no problem—those imprints will just be added to the queue.
						}
					}
				} finally {
					if(outputStream == System.out) { //don't close the writer if we are writing to stdout
						writer.flush();
					} else {
						writer.close();
					}
				}
			} finally {
				//If we are writing to stdout, we didn't close the writer, so flush the output stream (even though it
				//should have been flushed anyway; see https://stackoverflow.com/a/7166357). 
				if(outputStream == System.out) {
					outputStream.flush();
				}
			}
			timeElapsed = statusPrinter.getElapsedTime();
		}
		logger.info("{}", ansi().bold().fg(Ansi.Color.BLUE)
				.a("Done. Elapsed time: %d:%02d:%02d.".formatted(timeElapsed.toHours(), timeElapsed.toMinutesPart(), timeElapsed.toSecondsPart())).reset());
	}

	/**
	 * Implementation of a path imprint generator listener that prints status information to {@link System#err} as the generator traverses the tree and generates
	 * imprints.
	 * <p>
	 * If {@link DatimprintCli#isVerbose()} is enabled, directories will be printed as they are entered.
	 * </p>
	 * @implNote The printed count is based upon the traversal/generation status, and is independent of the line numbers placed in the file.
	 * @author Garret Wilson
	 */
	private class GenerateStatusPrinter extends CliStatus<Path> implements PathImprintGenerator.Listener {

		public GenerateStatusPrinter() {
			super(System.err);
		}

		@Override
		public void onGenerateImprint(final Path path) {
			incrementCount();
		}

		@Override
		public void onEnterDirectory(final Path directory) {
			if(isVerbose()) {
				printLineAsync(directory.toString());
			}
		}

		@Override
		public void beforeGenerateFileContentFingerprint(final Path file) {
			addWork(file);
		}

		@Override
		public void afterGenerateFileContentFingerprint(final Path file) {
			removeWork(file);
		}

	}

	/**
	 * Checks the indicated file or files in the indicated directory tree against the data imprints in a file. The imprints will be checked even if they are for
	 * different paths, as long as their relative paths (against the stored base paths) match those in the subtree. Any paths not in the imprints file (e.g. new
	 * files in the data directory) will not be checked.
	 * @param argDataPath The file or base directory of the file(s) to be checked.
	 * @param argImprintFile The file containing imprints against which to check the data files.
	 * @throws IOException If an I/O error occurs.
	 */
	@Command(description = "Checks the indicated file or files in the indicated directory tree against the data imprints in a file.", mixinStandardHelpOptions = true)
	public void check(
			@Parameters(paramLabel = "<data>", description = "The file or base directory of the file(s) to be checked.", arity = "1..*") @Nonnull Path argDataPath,
			@Option(names = {"--imprint",
					"-i"}, description = "The file containing imprints against which to check the data files.", arity = "1") Path argImprintFile) //TODO fix required
			//TODO @Option(names = {"--imprint-charset"}, description = "The charset of the imprints file. If not provided, detected from the any BOM, defaulting to UTF-8.") Optional<Charset> argImprintCharset)
			throws IOException {

		final Logger logger = getLogger();

		logAppInfo();

		logger.info("{}", ansi().bold().fg(Ansi.Color.BLUE).a("Checking `%s` against imprint `%s` ...".formatted(argDataPath, argImprintFile)).reset());
		final Duration timeElapsed;
		try (final InputStream inputStream = new BufferedInputStream(newInputStream(argImprintFile));
				final CheckStatusPrinter statusPrinter = new CheckStatusPrinter()) {
			final PathChecker.Builder pathCheckerBuilder = PathChecker.builder();
			if(!isQuiet()) { //if we're in quiet mode, don't even bother with listening and printing a status
				pathCheckerBuilder.withListener(statusPrinter);
			}
			try (final PathChecker pathChecker = pathCheckerBuilder.build()) {
				final Datim.Parser parser = new Datim.Parser(inputStream);
				Optional<PathImprint> foundImprint;
				do {
					foundImprint = parser.readImprint();
					foundImprint.ifPresent(throwingConsumer(imprint -> {
						final Path imprintPath = imprint.path();
						final Path oldBasePath = parser.findCurrentBasePath()
								.orElseThrow(() -> new IOException("Cannot relocate imprint path `%s`; base path not known.".formatted(imprintPath)));
						final Path path = changeBase(imprintPath, oldBasePath, argDataPath);
						pathChecker.checkPathAsync(path, imprint);
					}));
				} while(foundImprint.isPresent());
			}
			timeElapsed = statusPrinter.getElapsedTime();
		}
		logger.info("{}", ansi().bold().fg(Ansi.Color.BLUE)
				.a("Done. Elapsed time: %d:%02d:%02d.".formatted(timeElapsed.toHours(), timeElapsed.toMinutesPart(), timeElapsed.toSecondsPart())).reset());
	}

	/**
	 * Implementation of a path checker listener that prints status information to {@link System#err} as paths are checked.
	 * <p>
	 * If {@link DatimprintCli#isVerbose()} is enabled, directories will be printed separately as they are checked. The idea is that directories provide some
	 * indication of progress (assuming they were traversed in some order when producing the imprints file), but will not ever show up in the status because they
	 * do not have content fingerprints calculated for the check operation.
	 * </p>
	 * @author Garret Wilson
	 */
	private class CheckStatusPrinter extends CliStatus<Path> implements PathChecker.Listener {

		public CheckStatusPrinter() {
			super(System.err);
		}

		@Override
		public void onCheckPath(final Path path, final PathImprint imprint) {
			incrementCount();
			if(isVerbose() && isDirectory(path)) {
				printLineAsync(path.toString());
			}
		}

		@Override
		public void beforeGenerateFileContentFingerprint(final Path file) {
			addWork(file);
		}

		@Override
		public void afterGenerateFileContentFingerprint(final Path file) {
			removeWork(file);
		}

		@Override
		public void onResult(final PathChecker.Result result) {
			if(!result.isMatch()) {
				printLine(toReport(result));
			}
		}

		/**
		 * Determines a string for reporting a result.
		 * @param result The result being reported.
		 * @return The report message to provide the user.
		 */
		protected String toReport(@Nonnull final PathChecker.Result result) {
			//TODO improve with warnings vs errors
			//TODO add report for matching results in case needed in the future
			if(result instanceof PathChecker.MissingPathResult) {
				return "No path `%s` matching imprint `%s`.".formatted(result.getPath(), result.getImprint().path());
			} else {
				return "Path `%s` does not match imprint `%s`.".formatted(result.getPath(), result.getImprint().path());
			}
		}

	}

}
