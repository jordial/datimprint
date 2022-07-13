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

package com.jordial.datimprint.file;

import java.nio.file.Path;

import javax.annotation.Nonnull;

/**
 * Listens for events from {@link PathImprintGenerator}.
 * <p>
 * Implementations of this class <strong>must be thread safe</strong>, as the methods may be called concurrently.
 * </p>
 * @author Garret Wilson
 */
public interface PathImprintGeneratorListener {

	/**
	 * Called when traversal enters a directory, before any directory listing or generation takes place in the directory.
	 * @param directory The directory entered.
	 */
	void onEnterDirectory(@Nonnull Path directory);

	/**
	 * Called immediately before fingerprint generation begins for the contents of a particular file.
	 * @param file The file the fingerprint of which is to be generated.
	 */
	void beforeGenerateFileContentFingerprint(@Nonnull Path file);

	/**
	 * Called immediately after fingerprint generation is completed for the contents of a particular file.
	 * @param file The file the fingerprint of which has been generated.
	 */
	void afterGenerateFileContentFingerprint(@Nonnull Path file);

	/**
	 * Called when generation of an imprint is being scheduled for a path.
	 * @param path The path for which the imprint is being generated.
	 */
	void onGenerateImprint(@Nonnull Path path);

}
