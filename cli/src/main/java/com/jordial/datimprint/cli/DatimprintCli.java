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

import static com.globalmentor.collections.iterables.Iterables.*;
import static com.globalmentor.io.Paths.*;
import static java.nio.charset.StandardCharsets.*;
import static java.nio.file.Files.*;
import static java.nio.file.LinkOption.*;
import static java.util.Comparator.*;
import static java.util.stream.Collectors.*;
import static org.fusesource.jansi.Ansi.*;
import static org.zalando.fauxpas.FauxPas.*;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.DosFileAttributes;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.*;

import javax.annotation.*;

import org.fusesource.jansi.Ansi;
import org.slf4j.Logger;
import org.slf4j.event.Level;

import com.globalmentor.application.*;
import com.globalmentor.io.*;
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
	 * @param argOutput The path to a file in which to store the output.
	 * @param argOutputCharset The charset for text encoding the output, if output is specified.
	 * @param argExecutorType The particular type of executor to use, if any.
	 * @param argExcludePaths The literal paths to exclude, if any.
	 * @param argExcludePathGlobs The globs of paths to exclude, if any.
	 * @param argExcludeFilenameGlobs The globs of filenames to exclude, if any.
	 * @throws IOException If an I/O error occurs.
	 */
	@Command(description = "Generates a data imprint of the indicated file or directory tree. The output will use the default console/system encoding unless an output file is specified. The system line separator will be used.", mixinStandardHelpOptions = true)
	public void generate(
			@Parameters(paramLabel = "<data>", description = "The file or base directory of the data for which an imprint should be generated. Multiple data sources are allowed.", arity = "1..*") @Nonnull final List<Path> argDataPaths,
			@Option(names = {"--output",
					"-o"}, description = "The path to a file in which to store the output. UTF-8 will be used as the charset unless @|bold --output-charset|@ is specified. The system line separator will be used.") final Optional<Path> argOutput,
			@Option(names = "--output-charset", description = "The charset for text encoding the output; ignored if no output file indicated.%nDefaults to UTF-8 if an output file is specified; otherwise uses the console encoding.") final Optional<Charset> argOutputCharset,
			@Option(names = {
					"--executor"}, description = "Specifies a particular executor to use for multithreading. Valid values: ${COMPLETION-CANDIDATES}") final Optional<PathImprintGenerator.Builder.ExecutorType> argExecutorType,
			@Option(names = "--exclude-path", description = "One or more literal paths to exclude.") final List<Path> argExcludePaths,
			@Option(names = "--exclude-path-glob", description = "One or more matching globs of paths to exclude; e.g. `**.txt` to exclude all text files. Windows paths much escape path separators using `\\\\`.%nMust be quoted on Linux or via OpenJDK `java -jar`.") final List<String> argExcludePathGlobs,
			@Option(names = "--exclude-filename-glob", description = "One or more matching globs of filenames to exclude; e.g. `*.t?t` to exclude all text and test files.%nMust be quoted on Linux or via OpenJDK `java -jar`.") final List<String> argExcludeFilenameGlobs)
			throws IOException {

		final Logger logger = getLogger();

		logAppInfo();

		final List<Path> dataPaths = argDataPaths.stream().map(throwingFunction(path -> path.toRealPath(NOFOLLOW_LINKS))).collect(toUnmodifiableList());
		final FileSystem fileSystem = findFirst(dataPaths).orElseThrow(IllegalStateException::new).getFileSystem();
		final Charset outputCharset = argOutputCharset.orElse(UTF_8);
		final List<Path> excludePaths = argExcludePaths != null ? argExcludePaths : List.of();
		final List<String> excludePathGlobs = argExcludePathGlobs != null ? argExcludePathGlobs : List.of();
		final List<String> excludeFilenameGlobs = argExcludeFilenameGlobs != null ? argExcludeFilenameGlobs : List.of();
		logger.info("{}", ansi().bold().fg(Ansi.Color.BLUE)
				.a("Generating imprint for %s ...".formatted(dataPaths.stream().map(path -> "`%s`".formatted(path)).collect(joining(", ")))).reset());
		final Duration timeElapsed;
		try (final GenerateStatus status = new GenerateStatus();
				final Writer writer = argOutput
						.<Writer>map(throwingFunction(outputPath -> new BufferedWriter(new OutputStreamWriter(newOutputStream(outputPath), outputCharset))))
						.orElseGet(() -> new PrintStreamWriter(System.out, false))) {
			final Datim.Serializer datimSerializer = new Datim.Serializer();
			datimSerializer.appendHeader(writer);
			final AtomicLong counter = new AtomicLong(0);
			final Consumer<PathImprint> imprintConsumer = imprint -> {
				//suspend the status while writing the imprint if we are sending to stdout
				final Runnable appendImprint = throwingRunnable(() -> datimSerializer.appendImprint(writer, imprint, counter.incrementAndGet()));
				argOutput.ifPresentOrElse(__ -> appendImprint.run(), () -> status.supplyWithoutStatusLineAsync(appendImprint));
			};
			final PathImprintGenerator.Builder imprintGeneratorBuilder = PathImprintGenerator.builder().withImprintConsumer(imprintConsumer)
					.withExcludePaths(excludePaths).withExcludePathGlobs(fileSystem, excludePathGlobs).withExcludeFilenameGlobs(fileSystem, excludeFilenameGlobs);
			if(!isQuiet()) { //if we're in quiet mode, don't even bother with listening and printing a status
				imprintGeneratorBuilder.withListener(status);
			}
			argExecutorType.ifPresent(imprintGeneratorBuilder::withGenerateExecutorType);
			try (final PathImprintGenerator imprintGenerator = imprintGeneratorBuilder.build()) {
				for(final Path dataPath : dataPaths) {
					datimSerializer.appendBasePath(writer, dataPath);
					imprintGenerator.produceImprintAsync(dataPath).join(); //any errors encountered will be propagated in this synchronous call
					//At this point the entire tree has been traversed. There may still be imprints being produced (i.e written),
					//but starting generating and producing imprints for another tree will cause no problem—those imprints will just be added to the queue.
				}
			}
			timeElapsed = status.getElapsedTime();
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
	 * @implNote The status count is based upon the traversal/generation status, and is independent of the line numbers placed in the file.
	 * @author Garret Wilson
	 */
	private class GenerateStatus extends CliStatus<Path> implements PathImprintGenerator.Listener {

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

		/**
		 * {@inheritDoc}
		 * @implSpec This implementation prints a warning for all unreadable paths unless the hidden and system attributes are both set. This prevents unnecessary
		 *           warnings for paths expected to throw {@link AccessDeniedException} errors such as <code>System Volume Information</code> on Windows file
		 *           systems if access were attempted.
		 * @implNote The approach used in this method to detect hidden directories only works from Java 13 onwards because of bug
		 *           <a href="https://bugs.openjdk.org/browse/JDK-8215467">JDK-8215467</a>.
		 */
		@Override
		public void onSkipUnreadablePath(final Path path) {
			try {
				if(isHidden(path)) { //isHidden() only works for directories from Java 13 onwards; see JDK-8215467
					try {
						final DosFileAttributes dosFileAttributes = readAttributes(path, DosFileAttributes.class);
						if(dosFileAttributes.isSystem()) { //only hidden+system directories are ignored
							return; //don't warn for DOS hidden+system paths
						}
					} catch(final UnsupportedOperationException unsupportedOperationException) {
						//not an error condition; simply continue and warn about the hidden path if it isn't on a DOS file system and thus not marked "system"
					}
				}
				warnAsync(getLogger(), "Skipping unreadable path `{}`.", path);
			} catch(final FileNotFoundException fileNotFoundException) {
				getLogger().warn("Inaccessible path `{}` suddenly disappeared.", path);
			} catch(final IOException ioException) {
				throw new UncheckedIOException(ioException);
			}
		}

		@Override
		public void onSkipExcludedPath(Path path) {
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
	 * @param argImprintCharset The charset of the imprints file.
	 * @param argOutput The path to a file in which to store the output.
	 * @param argOutputCharset The charset for text encoding the output, if output is specified.
	 * @throws IOException If an I/O error occurs.
	 */
	@Command(description = "Checks the indicated file or files in the indicated directory tree against the data imprints in a file. The output will use the default console/system encoding unless an output file is specified. The system line separator will be used.", mixinStandardHelpOptions = true)
	public void check(
			@Parameters(paramLabel = "<data>", description = "The file or base directory of the file(s) to be checked.", arity = "1..*") @Nonnull final Path argDataPath,
			@Option(names = {"--imprint",
					"-i"}, description = "The file containing imprints against which to check the data files.", required = true) final Path argImprintFile,
			@Option(names = {
					"--imprint-charset"}, description = "The charset of the imprints file. If not provided, detected from the any BOM, defaulting to UTF-8.") Optional<Charset> argImprintCharset,
			@Option(names = {"--output",
					"-o"}, description = "The path to a file in which to store the output. UTF-8 will be used as the charset unless @|bold --output-charset|@ is specified. The system line separator will be used.") final Optional<Path> argOutput,
			@Option(names = "--output-charset", description = "The charset for text encoding the output; ignored if no output file indicated.%nDefaults to UTF-8 if an output file is specified; otherwise uses the console encoding.") final Optional<Charset> argOutputCharset)
			throws IOException {

		final Logger logger = getLogger();

		logAppInfo();

		final Path dataPath = argDataPath.toRealPath(NOFOLLOW_LINKS);
		final Charset outputCharset = argOutputCharset.orElse(UTF_8);
		logger.info("{}", ansi().bold().fg(Ansi.Color.BLUE).a("Checking `%s` against imprint `%s` ...".formatted(dataPath, argImprintFile)).reset());
		final Duration timeElapsed;
		final AtomicReference<Optional<Throwable>> foundErrorReference = new AtomicReference<>(Optional.empty());
		try (final InputStream inputStream = new BufferedInputStream(newInputStream(argImprintFile));
				final CheckStatus status = new CheckStatus();
				final Writer writer = argOutput
						.<Writer>map(throwingFunction(outputPath -> new BufferedWriter(new OutputStreamWriter(newOutputStream(outputPath), outputCharset))))
						.orElseGet(() -> new PrintStreamWriter(System.out, false))) {
			final Consumer<PathChecker.Result> resultConsumer = result -> {
				if(!result.isMatch()) {
					final List<String> reportLines = new ArrayList<>();
					//- description
					reportLines.add("- " + (result instanceof PathChecker.MissingPathResult
							? "Missing path `%s` to match imprint for path `%s`.%n".formatted(result.getPath(), result.getImprint().path())
							: "Path `%s` does not match imprint for path `%s`.%n".formatted(result.getPath(), result.getImprint().path())));
					//  * detail(s)
					if(result instanceof PathChecker.ExistingPathResult existingPathResult) {
						existingPathResult.getMismatches().stream().sorted(comparingInt(PathChecker.Result.Mismatch::ordinal)) //sort by ordinal to show most severe problems first
								.map(mismatch -> {
									return "  * " + switch(mismatch) { //`  * detail`
										//TODO it would be best not to assume the result type just because there was a content fingerprint mismatch
										case CONTENT_FINGERPRINT -> "Path content fingerprint `%s` did not match `%s` of the imprint.%n"
												.formatted(((PathChecker.FileResult)result).getContentFingerprint(), existingPathResult.getImprint().contentFingerprint());
										case CONTENT_MODIFIED_AT -> "Path modification timestamp %s did not match %s of the imprint.%n"
												.formatted(existingPathResult.getContentModifiedAt(), existingPathResult.getImprint().contentModifiedAt());
										case FILENAME -> "Path filename `%s` did not match `%s` of the imprint.%n".formatted(findFilename(existingPathResult.getPath()).orElse(""),
												findFilename(existingPathResult.getImprint().path()).orElse(""));
									};
								}).forEachOrdered(reportLines::add);
					}
					//suspend the status while adding to the report if we are sending to stdout
					final Runnable appendReport = throwingRunnable(() -> {
						reportLines.forEach(throwingConsumer(writer::append));
						writer.flush(); //it will be useful for the user to get the information sooner, and normally the report should be significantly smaller that the data being checked
					});
					argOutput.ifPresentOrElse(__ -> appendReport.run(), () -> status.supplyWithoutStatusLineAsync(appendReport));
				}
			};
			final PathChecker.Builder pathCheckerBuilder = PathChecker.builder().withResultConsumer(resultConsumer);
			if(!isQuiet()) { //if we're in quiet mode, don't even bother with listening and printing a status
				pathCheckerBuilder.withListener(status);
			}
			Optional<CompletableFuture<PathChecker.Result>> foundFutureResult = Optional.empty();
			final AtomicLong imprintCount = new AtomicLong(0);
			try (final PathChecker pathChecker = pathCheckerBuilder.build()) {
				final Datim.Parser parser = argImprintCharset.map(imprintCharset -> new Datim.Parser(new InputStreamReader(inputStream, imprintCharset)))
						.orElseGet(throwingSupplier(() -> new Datim.Parser(inputStream)));
				Optional<PathImprint> foundImprint;
				do {
					final Optional<CompletableFuture<PathChecker.Result>> lastFoundFutureResult = foundFutureResult;
					foundImprint = parser.readImprint(); //read an imprint
					foundImprint.ifPresent(__ -> status.setTotal(imprintCount.incrementAndGet())); //keep track of the total number of imprints read, updating the status
					foundFutureResult = foundImprint.map(throwingFunction(imprint -> { //schedule a result for checking the imprint
						final Path imprintPath = imprint.path();
						final Path oldBasePath = parser.findCurrentBasePath()
								.orElseThrow(() -> new IOException("Cannot relocate imprint path `%s`; base path not known.".formatted(imprintPath)));
						final Path path = changeBase(imprintPath, oldBasePath, dataPath);
						return pathChecker.checkPathAsync(path, imprint).exceptionally(throwable -> {
							foundErrorReference.compareAndSet(Optional.empty(), Optional.of(throwable)); //keep track of the first error that occurs
							return null;
						});
					})).map(
							//if we have a new future result, chain it to the last found future result
							newFutureResult -> lastFoundFutureResult.map(lastFutureResult -> lastFutureResult.thenCombine(newFutureResult, (__, result) -> result))
									.orElse(newFutureResult)) //if there is no last found future result, use the new future result
							//if we do not have a new future result, just stick with the one we found last (which may also be empty)
							.map(Optional::of).orElse(lastFoundFutureResult);
				} while(foundImprint.isPresent() && foundErrorReference.get().isEmpty());

				foundFutureResult.ifPresent(CompletableFuture::join); //join the last future result we found; this will ensure the entire chain is complete

				foundErrorReference.get().ifPresent(throwingConsumer(throwable -> { //propagate and let the application handle any error
					throw throwable;
				}));
			}
			timeElapsed = status.getElapsedTime();
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
	private class CheckStatus extends CliStatus<Path> implements PathChecker.Listener {

		@Override
		public void onCheckPath(final Path path, final PathImprint imprint) {
			if(isVerbose() && isDirectory(path)) {
				printLineAsync(path.toString());
			}
		}

		@Override
		public void beforeCheckPath(final Path path) {
			addWork(path);
		}

		@Override
		public void afterCheckPath(final Path path) {
			incrementCount(); //it makes more sense to the user if the count shows the number of checks completed, rather than the number of imprints read, which may be increase quickly and give no indication of actual checking progress
			removeWork(path);
		}

		@Override
		public void onResultMismatch(final PathChecker.Result result) {
			final CharSequence pathLabel = constrainLabelLength(result.getPath().toString(), WORK_MAX_LABEL_LENGTH);
			final String notificationText = result instanceof PathChecker.MissingPathResult ? "Missing path `%s` for imprint.".formatted(pathLabel)
					: "Path `%s` does not match imprint.".formatted(pathLabel);
			setNotificationAsync(Level.ERROR, notificationText); //TODO use Level.WARN for directory modification timestamps
		}

	}

}
