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

import static com.jordial.datimprint.file.PathImprintGenerator.FINGERPRINT_ALGORITHM;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.attribute.*;
import java.time.Instant;

import org.junit.jupiter.api.*;

import com.globalmentor.security.Hash;

/**
 * Tests of {@link PathImprint}..
 * @author Garret Wilson
 */
public class PathImprintTest {

	/** @see PathImprint#forFile(Path, FileTime, Hash, com.globalmentor.security.MessageDigests.Algorithm) */
	@Test
	void testForFile() throws IOException {
		final Path mockFilePath = mock(Path.class);
		final Path mockFileNamePath = mock(Path.class);
		final String filename = "foo.bar";
		when(mockFileNamePath.toString()).thenReturn(filename);
		when(mockFilePath.getFileName()).thenReturn(mockFileNamePath);
		final FileTime modifiedAt = FileTime.from(Instant.ofEpochSecond(1653252496, 751214600));
		final Hash contentFingerprint = FINGERPRINT_ALGORITHM.hash("foobar");

		final PathImprint imprint = PathImprint.forFile(mockFilePath, modifiedAt, contentFingerprint, FINGERPRINT_ALGORITHM);
		assertThat(imprint.path(), is(mockFilePath));
		assertThat(imprint.modifiedAt(), is(modifiedAt));
		assertThat(imprint.contentFingerprint(), is(contentFingerprint));
		assertThat(imprint.fingerprint(), is(PathImprint.generateFingerprint(mockFilePath, modifiedAt, contentFingerprint, null, FINGERPRINT_ALGORITHM)));
	}

	/** @see PathImprint#forFile(Path, FileTime, Hash, com.globalmentor.security.MessageDigests.Algorithm) */
	@Test
	void testForDirectory() throws IOException {
		final Path mockDirectoryPath = mock(Path.class);
		final Path mockDirectoryFileNamePath = mock(Path.class);
		final String directoryfilename = "foobar";
		when(mockDirectoryFileNamePath.toString()).thenReturn(directoryfilename);
		when(mockDirectoryPath.getFileName()).thenReturn(mockDirectoryFileNamePath);
		final FileTime directoryModifiedAt = FileTime.from(Instant.ofEpochSecond(1653252496, 751214600));

		//foo child file
		final Path mockFooFilePath = mock(Path.class);
		final Path mockFooFileFileNamePath = mock(Path.class);
		final String fooFilename = "foo.txt";
		when(mockFooFileFileNamePath.toString()).thenReturn(fooFilename);
		when(mockFooFilePath.getFileName()).thenReturn(mockFooFileFileNamePath);
		final FileTime fooModifiedAt = FileTime.from(Instant.ofEpochSecond(1653252496, 751214100));
		final Hash fooContentFingerprint = FINGERPRINT_ALGORITHM.hash("foo");
		final PathImprint fooImprint = PathImprint.forFile(mockFooFilePath, fooModifiedAt, fooContentFingerprint, FINGERPRINT_ALGORITHM);

		//bar child file
		final Path mockBarFilePath = mock(Path.class);
		final Path mockBarFileFileNamePath = mock(Path.class);
		final String barFilename = "bar.txt";
		when(mockBarFileFileNamePath.toString()).thenReturn(barFilename);
		when(mockBarFilePath.getFileName()).thenReturn(mockBarFileFileNamePath);
		final FileTime barModifiedAt = FileTime.from(Instant.ofEpochSecond(1653252496, 751214200));
		final Hash barContentFingerprint = FINGERPRINT_ALGORITHM.hash("bar");
		final PathImprint barImprint = PathImprint.forFile(mockBarFilePath, barModifiedAt, barContentFingerprint, FINGERPRINT_ALGORITHM);

		//Actual calculation of fingerprints from child content and child fingerprints is technically unnecessary for a test.
		//The test would work the same if predetermined fingerprints were used for these parameters. Similarly the order of the child
		//processing has been normalized to alphabetical order as would occur in real life, even though it makes not difference
		//here as only the fingerprint itself is considered.
		//
		//Calculating the values manually here helps illustrate how the values would be generated in real life.
		//Furthermore it provides a pattern for the integration tests of the actual imprint generation.
		final Hash directoryContentFingerprint = FINGERPRINT_ALGORITHM.hash(barContentFingerprint, fooContentFingerprint);
		final Hash directoryChildrenFingerprint = FINGERPRINT_ALGORITHM.hash(barImprint.fingerprint(), fooImprint.fingerprint());

		final PathImprint imprint = PathImprint.forDirectory(mockDirectoryPath, directoryModifiedAt, directoryContentFingerprint, directoryChildrenFingerprint,
				FINGERPRINT_ALGORITHM);
		assertThat(imprint.path(), is(mockDirectoryPath));
		assertThat(imprint.modifiedAt(), is(directoryModifiedAt));
		assertThat(imprint.contentFingerprint(), is(directoryContentFingerprint));
		assertThat(imprint.fingerprint(), is(PathImprint.generateFingerprint(mockDirectoryPath, directoryModifiedAt, directoryContentFingerprint,
				directoryChildrenFingerprint, FINGERPRINT_ALGORITHM)));
	}

}
