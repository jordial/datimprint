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
import static java.nio.file.Files.*;
import static org.zalando.fauxpas.FauxPas.*;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.function.Supplier;

import javax.annotation.*;

import com.globalmentor.io.Paths;
import com.globalmentor.security.*;

/**
 * Data imprinting file system implementation.
 * @author Garret Wilson
 */
public class FileSystemDatimprinter {

	/**
	 * The algorithm for calculating fingerprints.
	 * @apiNote This algorithm should be set to an algorithm known to be implemented on all supported Java versions.
	 */
	public static final MessageDigests.Algorithm FINGERPRINT_ALGORITHM = MessageDigests.SHA_256;

	/**
	 * Generates an imprint of a single path.
	 * @implSpec Symbolic links are followed.
	 * @param path The path for which an imprint should be generated.
	 * @return An imprint of the path.
	 * @throws IOException if there is a problem accessing the path in the file system.
	 */
	public PathImprint generateImprint(@Nonnull final Path path) throws IOException {
		if(isRegularFile(path)) {
			return generateImprint(path, throwingSupplier(() -> Files.readAttributes(path, BasicFileAttributes.class)),
					throwingSupplier(() -> Files.newInputStream(path)));
		} else if(isDirectory(path)) {
			throw new UnsupportedOperationException("Generating an imprint of a directory not yet supported."); //TODO implement directory imprint
		} else {
			throw new UnsupportedOperationException("Unsupported path `%s` is neither a regular file or a directory.".formatted(path));
		}
	}

	/**
	 * Generates an imprint of a single path.
	 * @apiNote This method is separate primarily to facility verification and testing.
	 * @implSpec Symbolic links are followed.
	 * @param path The path for which an imprint should be generated.
	 * @param fileAttributesSupplier The source for supplying the file attributes of the path.
	 * @param contentSupplier The source for supplying the content of the file or directory.
	 * @return An imprint of the path.
	 * @throws IOException if there is a problem accessing the path in the file system.
	 */
	PathImprint generateImprint(@Nonnull final Path path, @Nonnull final Supplier<BasicFileAttributes> fileAttributesSupplier,
			@Nonnull final Supplier<InputStream> contentSupplier) throws IOException {
		final Hash filenameFingerprint = FINGERPRINT_ALGORITHM
				.hash(Paths.findFilename(path).orElseThrow(() -> new IllegalArgumentException("Path `%s` has no filename.".formatted(path))));
		final Instant modifiedAt = fileAttributesSupplier.get().lastModifiedTime().toInstant();
		final Hash contentFingerprint;
		try (final InputStream inputStream = contentSupplier.get()) {
			contentFingerprint = FINGERPRINT_ALGORITHM.hash(inputStream);
		}
		return new PathImprint(path, filenameFingerprint, modifiedAt, contentFingerprint, generateFingerprint(filenameFingerprint, modifiedAt, contentFingerprint));
	}

	/**
	 * Returns an overall fingerprint for the components of an imprint.
	 * @param filenameFingerprint The fingerprint of the path filename.
	 * @param modifiedAt The modification timestamp of the file.
	 * @param contentFingerprint The fingerprint of the contents of a file, or of the child fingerprints of a directory.
	 * @return A fingerprint of all the components.
	 */
	public static Hash generateFingerprint(@Nonnull final Hash filenameFingerprint, @Nonnull final Instant modifiedAt, @Nonnull final Hash contentFingerprint) {
		final MessageDigest fingerprintMessageDigest = FINGERPRINT_ALGORITHM.getInstance();
		filenameFingerprint.updateMessageDigest(fingerprintMessageDigest);
		fingerprintMessageDigest.update(toBytes(modifiedAt.toEpochMilli()));
		contentFingerprint.updateMessageDigest(fingerprintMessageDigest);
		return Hash.fromDigest(fingerprintMessageDigest);

	}
}
