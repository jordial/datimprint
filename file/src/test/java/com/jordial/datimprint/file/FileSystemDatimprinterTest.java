/*
 * Copyright © 2022 GlobalMentor, Inc. <http://www.globalmentor.com/>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jordial.datimprint.file;

import static com.jordial.datimprint.file.FileSystemDatimprinter.FINGERPRINT_ALGORITHM;
import static java.nio.charset.StandardCharsets.*;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.attribute.*;
import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * Tests of {@link FileSystemDatimprinter}.
 * @author Garret Wilson
 */
public class FileSystemDatimprinterTest {

	/*
	 * It would be possible to mock the file system and file system provider, like this:
	 * 
	 * ```
	 * final Path testPath = mock(Path.class);
	 * final FileSystem testFileSystem = mock(FileSystem.class);
	 * when(testPath.getFileSystem()).thenReturn(testFileSystem);
	 * final FileSystemProvider testFileSystemProvider=mock(FileSystemProvider.class);
	 * when(testFileSystem.provider()).thenReturn(testFileSystemProvider);
	 * when(testPath.toString()).thenReturn("/test/foo.bar");
	 * when(testFileSystemProvider.newInputStream(eq(testPath), any())).thenReturn(new ByteArrayInputStream("foobar".getBytes(UTF_8)));
	 * etc.
	 * ```
	 * 
	 * But that would be brittle, depending on the implementation of the JDK many layers down.
	 * The datimprinter implementation could instead call the file system and file system provider directly
	 * and guarantee this in its contract, but it isn't appropriate as a general rules to make implementation
	 * guarantees in the API.
	 */

	/** @see FileSystemDatimprinter#generateImprint(Path, java.util.function.Supplier, java.util.function.Supplier) */
	@Test
	void testGenerateImprint() throws IOException {
		final Path mockPath = mock(Path.class);
		final Path mockFileNamePath = mock(Path.class);
		final String filename = "foo.bar";
		when(mockFileNamePath.toString()).thenReturn(filename);
		when(mockPath.getFileName()).thenReturn(mockFileNamePath);
		final BasicFileAttributes mockFileAttributes = mock(BasicFileAttributes.class);
		final Instant lastModifiedTime = Instant.ofEpochSecond(1653252496, 751214600);
		when(mockFileAttributes.lastModifiedTime()).thenReturn(FileTime.from(lastModifiedTime));
		final String content = "foobar";

		final PathImprint imprint = new FileSystemDatimprinter().generateImprint(mockPath, () -> mockFileAttributes,
				() -> new ByteArrayInputStream(content.getBytes(UTF_8)));
		assertThat(imprint.path(), is(mockPath));
		assertThat(imprint.filenameFingerprint(), is(FINGERPRINT_ALGORITHM.hash(filename)));
		assertThat(imprint.modifiedAt(), is(lastModifiedTime));
		assertThat(imprint.contentFingerprint(), is(FINGERPRINT_ALGORITHM.hash(content)));
		assertThat(imprint.fingerprint(),
				is(FileSystemDatimprinter.generateFingerprint(imprint.filenameFingerprint(), imprint.modifiedAt(), imprint.contentFingerprint())));

	}
}