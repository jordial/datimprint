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
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.attribute.*;
import java.time.Instant;

import org.junit.jupiter.api.*;

import com.globalmentor.security.Hash;

/**
 * Tests of {@link PathImprint}.
 * @author Garret Wilson
 */
public class PathImprintTest {

	/** @see PathImprint#forFile(Path, FileTime, Hash, com.globalmentor.security.MessageDigests.Algorithm) */
	@Test
	void testForFile() throws IOException {
		final Path mockFilePath = mock(Path.class);
		when(mockFilePath.toRealPath(any())).thenReturn(mockFilePath);
		final Path mockFileNamePath = mock(Path.class);
		final String filename = "foo.bar";
		when(mockFileNamePath.toString()).thenReturn(filename);
		when(mockFilePath.getFileName()).thenReturn(mockFileNamePath);
		final FileTime contentModifiedAt = FileTime.from(Instant.ofEpochSecond(1653252496, 751214600));
		final Hash contentFingerprint = FINGERPRINT_ALGORITHM.hash("foobar");

		final PathImprint imprint = PathImprint.forFile(mockFilePath, contentModifiedAt, contentFingerprint, FINGERPRINT_ALGORITHM);
		assertThat(imprint.path(), is(mockFilePath));
		assertThat(imprint.contentModifiedAt(), is(contentModifiedAt));
		assertThat(imprint.contentFingerprint(), is(contentFingerprint));
		assertThat(imprint.fingerprint(), is(PathImprint.generateFingerprint(mockFilePath, contentModifiedAt, contentFingerprint, null, FINGERPRINT_ALGORITHM)));
		assertThat(imprint.miniprintChecksum(), is(imprint.fingerprint().toChecksum().substring(0, PathImprint.MINIPRINT_CHECKSUM_LENGTH)));
	}

	/** @see PathImprint#forFile(Path, FileTime, Hash, com.globalmentor.security.MessageDigests.Algorithm) */
	@Test
	void verifyForFileWithNoFilenameThrowsIllegalArgumentException() throws IOException {
		final Path mockFilePath = mock(Path.class);
		when(mockFilePath.toRealPath(any())).thenReturn(mockFilePath);
		final FileTime contentModifiedAt = FileTime.from(Instant.ofEpochSecond(1653252496, 751214600));
		final Hash contentFingerprint = FINGERPRINT_ALGORITHM.hash("foobar");

		assertThrows(IllegalArgumentException.class, () -> PathImprint.forFile(mockFilePath, contentModifiedAt, contentFingerprint, FINGERPRINT_ALGORITHM));
	}

	/** @see PathImprint#forDirectory(Path, FileTime, Hash, Hash, com.globalmentor.security.MessageDigests.Algorithm) */
	@Test
	void testForEmptyDirectory() throws IOException {
		final Path mockDirectoryPath = mock(Path.class);
		when(mockDirectoryPath.toRealPath(any())).thenReturn(mockDirectoryPath);
		final Path mockDirectoryFileNamePath = mock(Path.class);
		final String directoryfilename = "foobar";
		when(mockDirectoryFileNamePath.toString()).thenReturn(directoryfilename);
		when(mockDirectoryPath.getFileName()).thenReturn(mockDirectoryFileNamePath);
		final FileTime directoryContentModifiedAt = FileTime.from(Instant.ofEpochSecond(1653252496, 751214600));

		final Hash directoryContentFingerprint = FINGERPRINT_ALGORITHM.emptyHash();
		final Hash directoryChildrenFingerprint = FINGERPRINT_ALGORITHM.emptyHash();

		final PathImprint imprint = PathImprint.forDirectory(mockDirectoryPath, directoryContentModifiedAt, directoryContentFingerprint,
				directoryChildrenFingerprint, FINGERPRINT_ALGORITHM);
		assertThat(imprint.path(), is(mockDirectoryPath));
		assertThat(imprint.contentModifiedAt(), is(directoryContentModifiedAt));
		assertThat(imprint.contentFingerprint(), is(directoryContentFingerprint));
		assertThat(imprint.fingerprint(), is(PathImprint.generateFingerprint(mockDirectoryPath, directoryContentModifiedAt, directoryContentFingerprint,
				directoryChildrenFingerprint, FINGERPRINT_ALGORITHM)));
		assertThat(imprint.miniprintChecksum(), is(imprint.fingerprint().toChecksum().substring(0, PathImprint.MINIPRINT_CHECKSUM_LENGTH)));
	}

	/**
	 * Tests generating an imprint for a directory with no filename.
	 * @apiNote This simulates volume paths e.g. <code>A:\</code> on Windows.
	 * @see PathImprint#forDirectory(Path, FileTime, Hash, Hash, com.globalmentor.security.MessageDigests.Algorithm)
	 */
	@Test
	void testForDirectoryWithNoFilename() throws IOException {
		final Path mockDirectoryPath = mock(Path.class);
		when(mockDirectoryPath.toRealPath(any())).thenReturn(mockDirectoryPath);
		final FileTime directoryContentModifiedAt = FileTime.from(Instant.ofEpochSecond(1653252496, 751214600));

		final Hash directoryContentFingerprint = FINGERPRINT_ALGORITHM.emptyHash();
		final Hash directoryChildrenFingerprint = FINGERPRINT_ALGORITHM.emptyHash();

		final PathImprint imprint = PathImprint.forDirectory(mockDirectoryPath, directoryContentModifiedAt, directoryContentFingerprint,
				directoryChildrenFingerprint, FINGERPRINT_ALGORITHM);
		assertThat(imprint.path(), is(mockDirectoryPath));
		assertThat(imprint.contentModifiedAt(), is(directoryContentModifiedAt));
		assertThat(imprint.contentFingerprint(), is(directoryContentFingerprint));
		assertThat(imprint.fingerprint(), is(PathImprint.generateFingerprint(mockDirectoryPath, directoryContentModifiedAt, directoryContentFingerprint,
				directoryChildrenFingerprint, FINGERPRINT_ALGORITHM)));
		assertThat(imprint.miniprintChecksum(), is(imprint.fingerprint().toChecksum().substring(0, PathImprint.MINIPRINT_CHECKSUM_LENGTH)));
	}

	/**
	 * Simulates a directory with children.
	 * @implNote Actual calculation of fingerprints from child content and child fingerprints is technically unnecessary for a test. The test would work the same
	 *           if predetermined fingerprints were used for these parameters, or if empty hashes were use as in {@link #testForEmptyDirectory()}. Similarly the
	 *           order of the child processing has been normalized to alphabetical order as would occur in real life, even though it makes no difference here as
	 *           only the fingerprint itself is considered. Calculating the values manually here helps illustrate how the values would be generated in real life.
	 *           Furthermore it provides a pattern for the integration tests of the actual imprint generation.
	 * @see PathImprint#forDirectory(Path, FileTime, Hash, Hash, com.globalmentor.security.MessageDigests.Algorithm)
	 */
	@Test
	void testForDirectoryWithChildren() throws IOException {
		final Path mockDirectoryPath = mock(Path.class);
		when(mockDirectoryPath.toRealPath(any())).thenReturn(mockDirectoryPath);
		final Path mockDirectoryFileNamePath = mock(Path.class);
		final String directoryfilename = "foobar";
		when(mockDirectoryFileNamePath.toString()).thenReturn(directoryfilename);
		when(mockDirectoryPath.getFileName()).thenReturn(mockDirectoryFileNamePath);
		final FileTime directoryContentModifiedAt = FileTime.from(Instant.ofEpochSecond(1653252496, 751214600));

		//foo child file
		final Path mockFooFilePath = mock(Path.class);
		when(mockFooFilePath.toRealPath(any())).thenReturn(mockFooFilePath);
		final Path mockFooFileFileNamePath = mock(Path.class);
		final String fooFilename = "foo.txt";
		when(mockFooFileFileNamePath.toString()).thenReturn(fooFilename);
		when(mockFooFilePath.getFileName()).thenReturn(mockFooFileFileNamePath);
		final FileTime fooContentModifiedAt = FileTime.from(Instant.ofEpochSecond(1653252496, 751214100));
		final Hash fooContentFingerprint = FINGERPRINT_ALGORITHM.hash("foo");
		final PathImprint fooImprint = PathImprint.forFile(mockFooFilePath, fooContentModifiedAt, fooContentFingerprint, FINGERPRINT_ALGORITHM);

		//bar child file
		final Path mockBarFilePath = mock(Path.class);
		when(mockBarFilePath.toRealPath(any())).thenReturn(mockBarFilePath);
		final Path mockBarFileFileNamePath = mock(Path.class);
		final String barFilename = "bar.txt";
		when(mockBarFileFileNamePath.toString()).thenReturn(barFilename);
		when(mockBarFilePath.getFileName()).thenReturn(mockBarFileFileNamePath);
		final FileTime barContentModifiedAt = FileTime.from(Instant.ofEpochSecond(1653252496, 751214200));
		final Hash barContentFingerprint = FINGERPRINT_ALGORITHM.hash("bar");
		final PathImprint barImprint = PathImprint.forFile(mockBarFilePath, barContentModifiedAt, barContentFingerprint, FINGERPRINT_ALGORITHM);

		final Hash directoryContentFingerprint = FINGERPRINT_ALGORITHM.hash(barContentFingerprint, fooContentFingerprint);
		final Hash directoryChildrenFingerprint = FINGERPRINT_ALGORITHM.hash(barImprint.fingerprint(), fooImprint.fingerprint());

		final PathImprint imprint = PathImprint.forDirectory(mockDirectoryPath, directoryContentModifiedAt, directoryContentFingerprint,
				directoryChildrenFingerprint, FINGERPRINT_ALGORITHM);
		assertThat(imprint.path(), is(mockDirectoryPath));
		assertThat(imprint.contentModifiedAt(), is(directoryContentModifiedAt));
		assertThat(imprint.contentFingerprint(), is(directoryContentFingerprint));
		assertThat(imprint.fingerprint(), is(PathImprint.generateFingerprint(mockDirectoryPath, directoryContentModifiedAt, directoryContentFingerprint,
				directoryChildrenFingerprint, FINGERPRINT_ALGORITHM)));
		assertThat(imprint.miniprintChecksum(), is(imprint.fingerprint().toChecksum().substring(0, PathImprint.MINIPRINT_CHECKSUM_LENGTH)));
	}

}
