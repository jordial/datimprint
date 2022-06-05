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
import java.util.function.Consumer;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.*;

import org.fusesource.jansi.Ansi;
import org.slf4j.Logger;
import org.slf4j.event.Level;

import com.github.dtmo.jfiglet.*;
import com.globalmentor.application.*;
import com.globalmentor.java.*;
import com.jordial.datimprint.file.*;

import io.confound.config.*;
import io.confound.config.file.ResourcesConfigurationManager;
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
	 * Logs startup app information, including application banner, name, and version.
	 * @see #getLogger()
	 * @see Level#INFO
	 * @throws ConfigurationException if some configuration information isn't present.
	 */
	protected void logAppInfo() {
		final FigletRenderer figletRenderer;
		final Configuration appConfiguration;
		try {
			appConfiguration = ResourcesConfigurationManager.loadConfigurationForClass(getClass())
					.orElseThrow(ResourcesConfigurationManager::createConfigurationNotFoundException);
			figletRenderer = new FigletRenderer(FigFontResources.loadFigFontResource(FigFontResources.BIG_FLF));
		} catch(final IOException ioException) {
			throw new ConfigurationException(ioException);
		}
		final String appName = appConfiguration.getString(CONFIG_KEY_NAME);
		final String appVersion = appConfiguration.getString(CONFIG_KEY_VERSION);
		final Logger logger = getLogger();
		logger.info("\n{}{}{}", ansi().bold().fg(Ansi.Color.GREEN), figletRenderer.renderText(appName), ansi().reset());
		logger.info("{} {}\n", appName, appVersion);
	}

	/**
	 * Generates a data imprint of the indicated file or directory tree.
	 * @param argDataPath The file or base directory of the data for which an imprint should be generated.
	 * @param argCharset The charset for text encoding.
	 * @param argOutput The path to a file in which to store the output.
	 * @throws IOException If an I/O error occurs.
	 */
	@Command(description = "Generates a data imprint of the indicated file or directory tree. The output will use the default console/system encoding and line separator unless an output file is specified.", mixinStandardHelpOptions = true)
	public void generate(
			@Parameters(paramLabel = "<data>", description = "The file or base directory of the data for which an imprint should be generated.%nDefaults to the working directory, currently @|bold ${DEFAULT-VALUE}|@.", defaultValue = "${sys:user.dir}", arity = "0..1") @Nonnull Optional<Path> argDataPath,
			@Option(names = {"--charset",
					"-c"}, description = "The charset for text encoding.%nDefaults to UTF-8 if an output file is specified; otherwise uses the console system encoding unless redirected, in which case uses the default system encoding.") Optional<Charset> argCharset,
			@Option(names = {"--output",
					"-o"}, description = "The path to a file in which to store the output. UTF-8 will be used as the charset unless @|bold --charset|@ is specified. A single LF will be used as the line separator.") Optional<Path> argOutput)
			throws IOException {

		final Logger logger = getLogger();

		logAppInfo();

		final Path dataPath = argDataPath.orElseGet(OperatingSystem::getWorkingDirectory);
		final String lineSeparator = argOutput.map(__ -> LINE_FEED_CHAR).map(String::valueOf).orElseGet(OperatingSystem::getLineSeparator);
		final Charset charset = argCharset.orElse(argOutput.map(__ -> UTF_8).orElseGet( //see https://stackoverflow.com/q/72435634
				() -> Optional.ofNullable(System.console()).map(Console::charset).orElseGet(Charset::defaultCharset)));
		logger.info("{}", ansi().bold().fg(Ansi.Color.BLUE).a("Generating imprint for `%s`...".formatted(dataPath)).reset());
		final OutputStream outputStream = argOutput.map(throwingFunction(Files::newOutputStream)).orElse(System.out);
		try { //manually flush or close the output stream and writer rather than using try-with-resources as the output stream may be System.out
			final Writer writer = new BufferedWriter(new OutputStreamWriter(outputStream, charset));
			try {
				printImprintHeader(writer, lineSeparator);
				final AtomicLong counter = new AtomicLong(0);
				final Consumer<PathImprint> imprintConsumer = throwingConsumer(imprint -> printImprint(writer, imprint, counter, lineSeparator));
				try (final FileSystemDatimprinter datimprinter = new FileSystemDatimprinter(imprintConsumer)) {
					datimprinter.produceImprint(dataPath);
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
		logger.info("{}", ansi().bold().fg(Ansi.Color.BLUE).a("Done.").reset());
	}

	/**
	 * Prints the header of an imprint output.
	 * @param writer The writer for writing the imprint header.
	 * @param lineSeparator The end-of-line character.
	 * @throws IOException if an I/O error occurs writing the data.
	 */
	protected void printImprintHeader(@Nonnull final Writer writer, @Nonnull final String lineSeparator) throws IOException {
		writer.write("#\tMiniprint\tPath\tModified At\tContent Fingerprint\tComplete Fingerprint%s".formatted(lineSeparator));
		//TODO add "Levels" column with e.g. `+++` designation for number of levels below root
	}

	/**
	 * Prints the header of an imprint output. The counter will be incremented before printing.
	 * @param writer The writer for writing the imprint.
	 * @param imprint The imprint to print.
	 * @param counter The counter maintaining the number of imprints already printed.
	 * @param lineSeparator The end-of-line character.
	 * @throws IOException if an I/O error occurs writing the data.
	 */
	protected void printImprint(@Nonnull final Writer writer, @Nonnull final PathImprint imprint, @Nonnull final AtomicLong counter,
			@Nonnull final String lineSeparator) throws IOException {
		//TODO ensure no tab in path
		writer.write("%s\t%s\t%s\t%s\t%s\t%s%s".formatted(Long.toUnsignedString(counter.incrementAndGet()), imprint.fingerprint().toChecksum().substring(0, 8),
				imprint.path(), imprint.modifiedAt(), imprint.contentFingerprint().toChecksum(), imprint.fingerprint().toChecksum(), lineSeparator));
	}

}
