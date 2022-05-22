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

import static java.util.Objects.*;

import java.nio.file.Path;
import java.time.Instant;

import com.globalmentor.security.Hash;

/**
 * Data holder for the imprint of a path, which may be a directory or a file.
 * @param path The path being described
 * @param filenameFingerprint The hash of the path filename.
 * @param modifiedAt The modification timestamp of the file.
 * @param contentFingerprint The fingerprint of the contents of a file, or of the child fingerprints of a directory.
 * @param fingerprint The full fingerprint of the file or directory, including its path, modification timestamp, and content fingerprint.
 * @author Garret Wilson
 */
public record PathImprint(Path path, Hash filenameFingerprint, Instant modifiedAt, Hash contentFingerprint, Hash fingerprint) {

	/**
	 * Constructor for argument validation.
	 * @param path The path being described
	 * @param filenameFingerprint The hash of the path filename.
	 * @param modifiedAt The modification timestamp of the file.
	 * @param contentFingerprint The fingerprint of the contents of a file, or of the child fingerprints of a directory.
	 * @param fingerprint The full fingerprint of the file or directory, including its path, modification timestamp, and content fingerprint.
	 */
	public PathImprint {
		requireNonNull(path);
		requireNonNull(filenameFingerprint);
		requireNonNull(modifiedAt);
		requireNonNull(contentFingerprint);
		requireNonNull(fingerprint);
	}

}
