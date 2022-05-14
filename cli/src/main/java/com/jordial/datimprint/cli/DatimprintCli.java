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

import static org.fusesource.jansi.Ansi.*;

import java.io.IOException;
import java.nio.file.*;

import javax.annotation.*;

import org.fusesource.jansi.Ansi;
import org.slf4j.Logger;
import org.slf4j.event.Level;

import com.github.dtmo.jfiglet.*;
import com.globalmentor.application.*;

import io.confound.config.Configuration;
import io.confound.config.ConfigurationException;
import io.confound.config.file.ResourcesConfigurationManager;
import picocli.CommandLine.*;

/**
 * Command-line interface for Datimprint.
 * @author Garret Wilson
 */
@Command(name = "datimprint", description = "Jordial's command-line interface for data statistics, thumbprint, and verification")
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
	 * @param argData The file or base directory of the data for which an imprint should be generated.
	 * @throws IOException If an I/O error occurs.
	 */
	@Command(description = "Generates a data imprint of the indicated file or directory tree.", mixinStandardHelpOptions = true)
	public void generate(
			@Parameters(paramLabel = "<data>", description = "The file or base directory of the data for which an imprint should be generated.%nDefaults to the working directory, currently @|bold ${DEFAULT-VALUE}|@.", defaultValue = "${sys:user.dir}", arity = "0..1") @Nullable Path argData)
			throws IOException {
		final Logger logger = getLogger();
		logger.info("Data: {}", argData);

		logAppInfo();

		logger.info("{}", ansi().bold().fg(Ansi.Color.BLUE).a("Done.").reset());
	}

}
