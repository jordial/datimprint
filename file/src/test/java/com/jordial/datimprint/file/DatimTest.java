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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import org.junit.jupiter.api.*;

import com.globalmentor.security.Hash;

/**
 * Tests of {@link Datim}.
 * @author Garret Wilson
 */
public class DatimTest {

	//Serializer

	/** @see Datim.Serializer#appendHeader(Appendable, CharSequence) */
	@Test
	void testSerializerAppendHeader() throws IOException {
		assertThat(Datim.Serializer.appendHeader(new StringBuilder(), "\r\n").toString(),
				is("#\tminiprint\tpath\tcontent-modifiedAt\tcontent-fingerprint\tfingerprint\r\n"));
	}

	/** @see Datim.Serializer#appendImprint(Appendable, PathImprint, long, CharSequence) */
	@Test
	void testSerializerAppendImprint() throws IOException {
		final Path mockFilePath = mock(Path.class);
		when(mockFilePath.toAbsolutePath()).thenReturn(mockFilePath);
		final Path mockFileNamePath = mock(Path.class);
		final String filename = "foo.bar";
		when(mockFilePath.toString()).thenReturn("/" + filename);
		when(mockFileNamePath.toString()).thenReturn(filename);
		when(mockFilePath.getFileName()).thenReturn(mockFileNamePath);
		final FileTime modifiedAt = FileTime.from(Instant.ofEpochSecond(1653252496, 751214600));
		final Hash contentFingerprint = FINGERPRINT_ALGORITHM.hash("foobar");
		final PathImprint imprint = PathImprint.forFile(mockFilePath, modifiedAt, contentFingerprint, FINGERPRINT_ALGORITHM);
		assertThat(Datim.Serializer.appendImprint(new StringBuilder(), imprint, 0x0123456789ABCDEFL, "\n").toString(), is(
				"81985529216486895\tc56f2ad0\t/foo.bar\t2022-05-22T20:48:16.7512146Z\tc3ab8ff13720e8ad9047dd39466b3c8974e592c2fa383d4a3960714caef0c4f2\tc56f2ad0a6e082790805ffabf1f68f13f77954ae6936ab1793edde7e101864c9\n"));
	}

}
