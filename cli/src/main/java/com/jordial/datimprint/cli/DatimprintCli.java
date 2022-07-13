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

import static com.globalmentor.collections.iterators.Iterators.*;
import static com.globalmentor.java.Characters.*;
import static java.nio.charset.StandardCharsets.*;
import static java.util.Collections.*;
import static java.util.concurrent.Executors.*;
import static org.fusesource.jansi.Ansi.*;
import static org.zalando.fauxpas.FauxPas.*;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;
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
				try (final StatusPrinter statusPrinter = new StatusPrinter(startTimeNs)) {
					final PathImprintGenerator.Builder imprintGeneratorBuilder = PathImprintGenerator.builder().withImprintConsumer(imprintConsumer);
					if(!isQuiet()) { //if we're in quiet mode, don't even bother with listening and printing a status
						imprintGeneratorBuilder.withListener(statusPrinter);
					}
					argExecutorType.ifPresent(imprintGeneratorBuilder::withGenerateExecutorType);
					try (final PathImprintGenerator imprintGenerator = imprintGeneratorBuilder.build()) {
						imprintGenerator.produceImprint(argDataPath);
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
	 * Implementation of a path imprint generator listener that prints status information to {@link System#err} as the generator traverses the tree and generates
	 * imprints.
	 * <p>
	 * If {@link DatimprintCli#isVerbose()} is enabled, directories will be printed as they are entered.
	 * </p>
	 * <p>
	 * The generated imprint count (which may include imprints that are still in the process of being generated) and information about the current file hash being
	 * generated are printed.
	 * </p>
	 * <p>
	 * This status printer must be closed after it is no longer in use.
	 * </p>
	 * @implNote The printed count is based upon the traversal/generation status, and is independent of the line numbers placed in the file.
	 * @implNote This implementation uses a separate, single-threaded executor for printing to reduce contention with generation and prevent race conditions in
	 *           status consistency.
	 * @author Garret Wilson
	 */
	private class StatusPrinter implements PathImprintGeneratorListener, Closeable {

		private final ExecutorService printExecutorService = newSingleThreadExecutor();

		/** The count of generated imprints. */
		private final AtomicLong counter = new AtomicLong(0);

		private final long startTimeNs;

		/**
		 * Start time constructor.
		 * @param startTimeNs The time the process started in nanoseconds.
		 */
		public StatusPrinter(final long startTimeNs) {
			this.startTimeNs = startTimeNs;
		}

		/**
		 * The file content fingerprints currently being generated. This is not expected to grow very large, as the number is limited to large extent by the number
		 * of threads used in the thread pool.
		 */
		private final Set<Path> fileContentFingerprintsGenerating = newSetFromMap(new ConcurrentHashMap<>());

		private Optional<Path> optionalStatusFile = Optional.empty();

		/**
		 * Finds a file marked as the "current" one generating a hash the purpose of status display. This is the file to display in the status, even though there
		 * might be several files actually having their file contents generated.
		 * @implSpec This method updates the record of the current file dynamically, based upon whether the file is actually still being hashed or not. If it is
		 *           not, or if there is no record of the current file to use, another file is determined by choosing any from the set of currently generating
		 *           fingerprint files.
		 * @return The file marked has currently having its content fingerprint generated for status purposes, if any.
		 */
		protected synchronized Optional<Path> findStatusFile() {
			Optional<Path> foundStatusFile = optionalStatusFile;
			//if no file has been chosen, or it is no longer actually being hashed
			if(!optionalStatusFile.map(fileContentFingerprintsGenerating::contains).orElse(false)) {
				foundStatusFile = findNext(fileContentFingerprintsGenerating.iterator()); //chose an arbitrary file for the status
				optionalStatusFile = foundStatusFile; //update the record of the status file for next time
			}
			return foundStatusFile;
		}

		/** Keeps track of the last status string to prevent unnecessary re-printing and to determine padding. */
		@Nullable
		private String lastStatus = null;

		/**
		 * Prints the given directory and then prints the status.
		 * @implSpec The record of the last status will be cleared.
		 * @param directory The directory to print.
		 */
		protected synchronized void printDirectory(@Nonnull final Path directory) {
			final int padWidth = lastStatus != null ? lastStatus.length() : 1; //pad at least to a nonzero value to avoid a MissingFormatWidthException
			System.err.println(("\r%-" + padWidth + "s").formatted(directory.toAbsolutePath()));
			lastStatus = null; //we're skipping to another line for further status
		}

		/**
		 * Prints the current status, including the elapsed time, count, and current status file.
		 * @see #findStatusFile()
		 */
		protected synchronized void printStatus() {
			final Duration elapsed = Duration.ofNanos(System.nanoTime() - startTimeNs);
			final String status = "%d:%02d:%02d | %d | %s".formatted(elapsed.toHours(), elapsed.toMinutesPart(), elapsed.toSecondsPart(), counter.get(),
					findStatusFile().map(Path::toString).orElse(""));
			if(!status.equals(lastStatus)) { //if the status is different than the last time (or there was no previous status)
				//We only have to pad to the last actual status, _not_ to the _padded_ last status, because
				//if the last status was printed padded, it would have erased the previous status already.
				//In other words, padding only needs to be added once to overwrite each previous status.
				final int padWidth = lastStatus != null ? lastStatus.length() : 1; //pad at least to a nonzero value to avoid a MissingFormatWidthException
				System.err.print(("\r%-" + padWidth + "s").formatted(status));
				lastStatus = status; //update the last status for checking the next time
			}
		}

		/** Clears the current status by blanking out the status and returning the cursor to the beginning of the line. */
		protected synchronized void clearStatus() {
			final int padWidth = lastStatus != null ? lastStatus.length() : 1; //pad at least to a nonzero value to avoid a MissingFormatWidthException
			System.err.print(("\r%-" + padWidth + "s\r").formatted(""));
			lastStatus = null;
		}

		/**
		 * {@inheritDoc}
		 * @implSpec This implementation writes the directory {@link System#err} if {@link DatimprintCli#isVerbose()} is enabled.
		 * @implNote This implementation relies on {@link PrintStream} already being synchronized for thread safety.
		 */
		@Override
		public void onEnterDirectory(final Path directory) {
			if(isVerbose()) {
				printExecutorService.execute(() -> {
					printDirectory(directory);
					printStatus();
				});
			}
		}

		@Override
		public void beforeGenerateFileContentFingerprint(final Path file) {
			fileContentFingerprintsGenerating.add(file);
			printExecutorService.execute(this::printStatus);
		}

		@Override
		public void afterGenerateFileContentFingerprint(final Path file) {
			fileContentFingerprintsGenerating.remove(file);
			printExecutorService.execute(this::printStatus);
		}

		@Override
		public void onGenerateImprint(final Path path) {
			counter.incrementAndGet();
			printExecutorService.execute(this::printStatus);
		}

		/**
		 * {@inheritDoc}
		 * @implSpec This implementation clears the status and then shuts down the executor used for printing the status.
		 * @throws IOException If the status print executor could not be shut down.
		 */
		@Override
		public void close() throws IOException {
			printExecutorService.execute(this::clearStatus);
			printExecutorService.shutdown();
			try {
				if(!printExecutorService.awaitTermination(5, TimeUnit.SECONDS)) {
					printExecutorService.shutdownNow();
					if(!printExecutorService.awaitTermination(3, TimeUnit.SECONDS)) {
						throw new IOException("Status printing service not shut down properly.");
					}
				}
			} catch(final InterruptedException interruptedException) {
				printExecutorService.shutdownNow();
				Thread.currentThread().interrupt();
			}
		}

	}

}
