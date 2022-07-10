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

import static com.globalmentor.java.Longs.*;
import static java.util.Objects.*;

import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;

import javax.annotation.*;

import com.globalmentor.io.Paths;
import com.globalmentor.security.*;

/**
 * Data holder for the imprint of a path, which may be a directory or a file.
 * <p>
 * An imprint of a single path has three major parts:
 * </p>
 * <ul>
 * <li>Name (the string form of the {@link Path#getFileName()})</li>
 * <li>Attributes (primarily last-modified timestamp)</li>
 * <li>Content</li>
 * </ul>
 * <p>
 * Of these the most important is the content. The name may have different case on operating systems such as Microsoft Windows. The last-modified timestamps may
 * differ, especially for directories (if the backup/copy mechanism was not thorough, as directories typically change their last-modified times when child paths
 * are added or removed during the backup/copy process itself).
 * </p>
 * <p>
 * The content fingerprint reflects the fidelity of <em>only the content</em> of a particular tree. The children fingerprint reflects the fidelity of the entire
 * level below the current path; this value will only be present for directories. Finally the fingerprint of the path itself includes the content and children
 * fingerprints.
 * </p>
 * @param path The path being described
 * @param modifiedAt The modification timestamp of the file.
 * @param contentFingerprint The fingerprint of the contents of a file, or for a directory, of all the content fingerprints of the children.
 * @param fingerprint The full fingerprint of the file or directory, including its name, attribute, content, and children fingerprint.
 * @author Garret Wilson
 */
public record PathImprint(@Nonnull Path path, @Nonnull FileTime modifiedAt, @Nonnull Hash contentFingerprint, @Nonnull Hash fingerprint) {

	/**
	 * Constructor for argument validation.
	 * @param path The path being described
	 * @param modifiedAt The modification timestamp of the file.
	 * @param contentFingerprint The fingerprint of the contents of a file, or of the child fingerprints of a directory.
	 * @param fingerprint The full fingerprint of the file or directory, including its path, modification timestamp, and content fingerprint.
	 */
	public PathImprint {
		requireNonNull(path);
		requireNonNull(modifiedAt);
		requireNonNull(contentFingerprint);
		requireNonNull(fingerprint);
	}

	/**
	 * Generates an imprint of a single path given the modification timestamp and pre-generated hash of the path contents.
	 * @implSpec The overall fingerprint is generated using
	 *           {@link #generateFingerprint(Path, FileTime, Hash, Hash, com.globalmentor.security.MessageDigests.Algorithm)}.
	 * @param file The path of the file for which an imprint should be generated.
	 * @param modifiedAt The modification timestamp of the file.
	 * @param contentFingerprint The fingerprint of the contents of the file.
	 * @param fingerprintAlgorithm The algorithm for calculating fingerprints.
	 * @return An imprint for the file.
	 * @throws IllegalArgumentException if the path has no filename.
	 * @see Path#getFileName()
	 */
	public static PathImprint forFile(@Nonnull final Path file, @Nonnull final FileTime modifiedAt, @Nonnull final Hash contentFingerprint,
			@Nonnull final MessageDigests.Algorithm fingerprintAlgorithm) {
		return new PathImprint(file, modifiedAt, contentFingerprint, generateFingerprint(file, modifiedAt, contentFingerprint, null, fingerprintAlgorithm));
	}

	/**
	 * Generates an imprint of a single path given the modification timestamp and pre-generated hash of the path contents.
	 * @implSpec The overall fingerprint is generated using
	 *           {@link #generateFingerprint(Path, FileTime, Hash, Hash, com.globalmentor.security.MessageDigests.Algorithm)}.
	 * @param directory The path of the directory for which an imprint should be generated.
	 * @param modifiedAt The modification timestamp of the file.
	 * @param contentFingerprint The fingerprint of the child content fingerprints of the directory.
	 * @param childrenFingerprint The fingerprint of the the child fingerprints of the directory. Note that an empty directory is still expected to have a
	 *          children fingerprint.
	 * @param fingerprintAlgorithm The algorithm for calculating fingerprints.
	 * @return An imprint for the directory.
	 * @throws IllegalArgumentException if the path has no filename.
	 * @see Path#getFileName()
	 */
	public static PathImprint forDirectory(@Nonnull final Path directory, @Nonnull final FileTime modifiedAt, @Nonnull final Hash contentFingerprint,
			@Nonnull final Hash childrenFingerprint, @Nonnull final MessageDigests.Algorithm fingerprintAlgorithm) {
		return new PathImprint(directory, modifiedAt, contentFingerprint,
				generateFingerprint(directory, modifiedAt, contentFingerprint, childrenFingerprint, fingerprintAlgorithm));
	}

	/**
	 * Returns an overall fingerprint for the components of an imprint.
	 * @implSpec the file time is hashed at millisecond resolution.
	 * @param path The path for which an imprint should be generated.
	 * @param modifiedAt The modification timestamp of the file.
	 * @param contentFingerprint The fingerprint of the contents of a file, or of the all the child content fingerprints of a directory.
	 * @param childrenFingerprint The fingerprint of the the child fingerprints of a directory, or <code>null</code> if children are not applicable (e.g. for a
	 *          file). Note that am empty directory is still expected to have a children fingerprint.
	 * @param fingerprintAlgorithm The algorithm for calculating fingerprints.
	 * @return A fingerprint of all the components.
	 * @throws IllegalArgumentException if the path has no filename.
	 * @see Path#getFileName()
	 */
	public static Hash generateFingerprint(@Nonnull final Path path, @Nonnull final FileTime modifiedAt, @Nonnull final Hash contentFingerprint,
			@Nullable final Hash childrenFingerprint, @Nonnull final MessageDigests.Algorithm fingerprintAlgorithm) {
		final Hash filenameFingerprint = fingerprintAlgorithm
				.hash(Paths.findFilename(path).orElseThrow(() -> new IllegalArgumentException("Path `%s` has no filename.".formatted(path))));
		final MessageDigest fingerprintMessageDigest = fingerprintAlgorithm.newMessageDigest();
		filenameFingerprint.updateMessageDigest(fingerprintMessageDigest);
		fingerprintMessageDigest.update(toBytes(modifiedAt.toMillis()));
		contentFingerprint.updateMessageDigest(fingerprintMessageDigest);
		if(childrenFingerprint != null) {
			childrenFingerprint.updateMessageDigest(fingerprintMessageDigest);
		}
		return Hash.fromDigest(fingerprintMessageDigest);
	}

}
