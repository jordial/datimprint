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

import static com.globalmentor.java.Characters.*;
import static java.nio.charset.StandardCharsets.*;
import static org.fusesource.jansi.Ansi.*;
import static org.zalando.fauxpas.FauxPas.*;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.*;

import org.fusesource.jansi.Ansi;
import org.slf4j.Logger;
import org.slf4j.event.Level;

import com.globalmentor.application.*;
import com.globalmentor.java.*;
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
	 * @param argDataPath The file or base directory of the data for which an imprint should be generated.
	 * @param argCharset The charset for text encoding.
	 * @param argOutput The path to a file in which to store the output.
	 * @param argExecutorType The particular type of executor to use, if any.
	 * @throws IOException If an I/O error occurs.
	 */
	@Command(description = "Generates a data imprint of the indicated file or directory tree. The output will use the default console/system encoding and line separator unless an output file is specified.", mixinStandardHelpOptions = true)
	public void generate(
			@Parameters(paramLabel = "<data>", description = "The file or base directory of the data for which an imprint should be generated.%nDefaults to the working directory.") @Nonnull Path argDataPath,
			@Option(names = {"--charset",
					"-c"}, description = "The charset for text encoding.%nDefaults to UTF-8 if an output file is specified; otherwise uses the console system encoding unless redirected, in which case uses the default system encoding.") Optional<Charset> argCharset,
			@Option(names = {"--output",
					"-o"}, description = "The path to a file in which to store the output. UTF-8 will be used as the charset unless @|bold --charset|@ is specified. A single LF will be used as the line separator.") Optional<Path> argOutput,
			@Option(names = {
					"--executor"}, description = "Specifies a particular executor to use for multithreading. Valid values: ${COMPLETION-CANDIDATES}") Optional<PathImprintGenerator.Builder.ExecutorType> argExecutorType)
			throws IOException {

		final Logger logger = getLogger();

		logAppInfo();

		final long startTimeNs = System.nanoTime();
		final String lineSeparator = argOutput.map(__ -> LINE_FEED_CHAR).map(String::valueOf).orElseGet(OperatingSystem::getLineSeparator);
		final Charset charset = argCharset.orElse(argOutput.map(__ -> UTF_8).orElseGet( //see https://stackoverflow.com/q/72435634
				() -> Optional.ofNullable(System.console()).map(Console::charset).orElseGet(Charset::defaultCharset)));
		logger.info("{}", ansi().bold().fg(Ansi.Color.BLUE).a("Generating imprint for `%s` ...".formatted(argDataPath.toAbsolutePath())).reset());
		final OutputStream outputStream = argOutput.map(throwingFunction(Files::newOutputStream)).orElse(System.out);
		try { //manually flush or close the output stream and writer rather than using try-with-resources as the output stream may be System.out
			final Writer writer = new BufferedWriter(new OutputStreamWriter(outputStream, charset));
			try {
				writeImprintHeader(writer, lineSeparator);
				final AtomicLong counter = new AtomicLong(0);
				final Consumer<PathImprint> imprintConsumer = throwingConsumer(imprint -> writeImprint(writer, imprint, counter.incrementAndGet(), lineSeparator));
				final PathImprintGenerator.Builder imprintGeneratorBuilder = PathImprintGenerator.builder().withImprintConsumer(imprintConsumer)
						.withListener(new StatusPrinter());
				argExecutorType.ifPresent(imprintGeneratorBuilder::withGenerateExecutorType);
				try (final PathImprintGenerator imprintGenerator = imprintGeneratorBuilder.build()) {
					imprintGenerator.produceImprint(argDataPath);
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
		final Duration elapsed = Duration.ofNanos(System.nanoTime() - startTimeNs);

		logger.info("{}", ansi().bold().fg(Ansi.Color.BLUE)
				.a("Done. Elapsed time: %d:%02d:%02d.".formatted(elapsed.toHours(), elapsed.toMinutesPart(), elapsed.toSecondsPart())).reset());
	}

	/**
	 * Prints the header of an imprint output.
	 * @param writer The writer for writing the imprint header.
	 * @param lineSeparator The end-of-line character.
	 * @throws IOException if an I/O error occurs writing the data.
	 */
	protected void writeImprintHeader(@Nonnull final Writer writer, @Nonnull final String lineSeparator) throws IOException {
		writer.write("#\tMiniprint\tPath\tModified At\tContent Fingerprint\tComplete Fingerprint%s".formatted(lineSeparator));
		//TODO add "Levels" column with e.g. `+++` designation for number of levels below root
	}

	/**
	 * Prints the header of an imprint output. The counter will be incremented before printing.
	 * @param writer The writer for writing the imprint.
	 * @param imprint The imprint to print.
	 * @param number The number of the line being written.
	 * @param lineSeparator The end-of-line character.
	 * @throws IOException if an I/O error occurs writing the data.
	 */
	protected void writeImprint(@Nonnull final Writer writer, @Nonnull final PathImprint imprint, @Nonnull final long number, @Nonnull final String lineSeparator)
			throws IOException {
		//TODO ensure no tab in path
		writer.write("%s\t%s\t%s\t%s\t%s\t%s%s".formatted(Long.toUnsignedString(number), imprint.fingerprint().toChecksum().substring(0, 8), imprint.path(),
				imprint.modifiedAt(), imprint.contentFingerprint().toChecksum(), imprint.fingerprint().toChecksum(), lineSeparator));
	}

	/**
	 * Implementation of a path imprint generator listener that prints status information as the generator traverses the tree and generates imprints.
	 * <p>
	 * If {@link DatimprintCli#isVerbose()} is enabled, directories will be printed as they are entered.
	 * </p>
	 * <p>
	 * The generated imprint count (which may include imprints that are still in the process of being generated) and information about the current file hash being
	 * generated are printed unless {@link DatimprintCli#isQuiet()} is enabled.
	 * </p>
	 * @implNote The printed count is based upon the traversal/generation status, and is independent of the line numbers placed in the file.
	 * @implNote Because this class uses targeted synchronization to avoid contention, it is not guaranteed that the status will be printed in the order of calls.
	 *           In particular it is possible, although not likely, for the "count" part of the status to be out of order for subsequent calls.
	 * @author Garret Wilson
	 */
	private class StatusPrinter implements PathImprintGeneratorListener {

		final AtomicLong counter = new AtomicLong(0);

		/**
		 * {@inheritDoc}
		 * @implSpec This implementation writes the directory {@link System#err} if {@link DatimprintCli#isVerbose()} is enabled.
		 * @implNote This implementation relies on {@link PrintStream} already being synchronized for thread safety.
		 */
		@Override
		public void onEnterDirectory(final Path directory) {
			if(isVerbose()) {
				System.err.println(directory.toAbsolutePath());
			}
		}

		@Override
		public void beforeGenerateFileContentFingerprint(final Path file) {
			//TODO
		}

		@Override
		public void afterGenerateFileContentFingerprint(final Path file) {
			//TODO
		}

		@Override
		public void onGenerateImprint(final Path path) {
			counter.incrementAndGet();
			printStatus();

		}

		//TODO doc
		protected void printStatus() {
			if(!isQuiet()) {
				//TODO create utility for shortening path
				//TODO find a way to overwrite the previous path; probably pad all the filenames if needed
				System.err.print("%d | %s\r".formatted(counter.get(), "TODO.txt"));
			}
		}

	}

}
