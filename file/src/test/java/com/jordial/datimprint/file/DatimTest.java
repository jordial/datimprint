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

import static com.github.npathai.hamcrestopt.OptionalMatchers.*;
import static com.globalmentor.collections.iterables.Iterables.*;
import static com.jordial.datimprint.file.PathImprintGenerator.FINGERPRINT_ALGORITHM;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.*;

import com.globalmentor.security.Hash;
import com.jordial.datimprint.file.Datim.Field;

/**
 * Tests of {@link Datim}.
 * @author Garret Wilson
 */
public class DatimTest {

	//Field

	/** @see Datim.Field#findByHeaderName(String) */
	@Test
	void testFieldFindByHeadeerName() {
		assertThat(Field.findByHeaderName("#"), isPresentAndIs(Field.NUMBER));
		assertThat(Field.findByHeaderName("path"), isPresentAndIs(Field.PATH));
		assertThat(Field.findByHeaderName("fingerprint"), isPresentAndIs(Field.FINGERPRINT));
		assertThat(Field.findByHeaderName("foo"), isEmpty());
	}

	//Parser

	/** @see Datim.Parser#readHeader() */
	@Test
	void testReadHeader() throws IOException {
		assertThat("Canonical header with newline.",
				new Datim.Parser(new StringReader("#\tminiprint\tpath\tcontent-modifiedAt\tcontent-fingerprint\tfingerprint\n")).readHeader(),
				contains(Field.NUMBER, Field.MINIPRINT, Field.PATH, Field.CONTENT_MODIFIED_AT, Field.CONTENT_FINGERPRINT, Field.FINGERPRINT));
		assertThat("Canonical header.", new Datim.Parser(new StringReader("#\tminiprint\tpath\tcontent-modifiedAt\tcontent-fingerprint\tfingerprint")).readHeader(),
				contains(Field.NUMBER, Field.MINIPRINT, Field.PATH, Field.CONTENT_MODIFIED_AT, Field.CONTENT_FINGERPRINT, Field.FINGERPRINT));
		assertThat("Headers in different order.",
				new Datim.Parser(new StringReader("fingerprint\tminiprint\t#\tpath\tcontent-modifiedAt\tcontent-fingerprint")).readHeader(),
				contains(Field.FINGERPRINT, Field.MINIPRINT, Field.NUMBER, Field.PATH, Field.CONTENT_MODIFIED_AT, Field.CONTENT_FINGERPRINT));
		assertThat("Only some headers.", new Datim.Parser(new StringReader("miniprint\tpath\tcontent-fingerprint\tcontent-modifiedAt")).readHeader(),
				contains(Field.MINIPRINT, Field.PATH, Field.CONTENT_FINGERPRINT, Field.CONTENT_MODIFIED_AT));
		assertThrows(IOException.class, () -> new Datim.Parser(new StringReader("miniprint\tpath\tfoo-bar\tcontent-modifiedAt")).readHeader(), "Unknown header.");
		assertThrows(IOException.class, () -> new Datim.Parser(new StringReader("miniprint\tpath\t\tcontent-modifiedAt")).readHeader(), "Missing header.");
		assertThrows(IOException.class, () -> new Datim.Parser(new StringReader("miniprint\tpath\tfoo-bar\tcontent-modifiedAt\t")).readHeader(),
				"Trailing delimiter.");
	}

	/** @see Datim.Parser#getFieldIndexes() */
	@Test
	void testGetFieldIndexes() throws IOException {
		assertThat("Canonical header with newline.",
				new Datim.Parser(new StringReader("#\tminiprint\tpath\tcontent-modifiedAt\tcontent-fingerprint\tfingerprint\n")).getFieldIndexes(),
				is(Map.of(Field.NUMBER, 0, Field.MINIPRINT, 1, Field.PATH, 2, Field.CONTENT_MODIFIED_AT, 3, Field.CONTENT_FINGERPRINT, 4, Field.FINGERPRINT, 5)));
		assertThat("Canonical header.",
				new Datim.Parser(new StringReader("#\tminiprint\tpath\tcontent-modifiedAt\tcontent-fingerprint\tfingerprint")).getFieldIndexes(),
				is(Map.of(Field.NUMBER, 0, Field.MINIPRINT, 1, Field.PATH, 2, Field.CONTENT_MODIFIED_AT, 3, Field.CONTENT_FINGERPRINT, 4, Field.FINGERPRINT, 5)));
		assertThat("Headers in different order.",
				new Datim.Parser(new StringReader("fingerprint\tminiprint\t#\tpath\tcontent-modifiedAt\tcontent-fingerprint")).getFieldIndexes(),
				is(Map.of(Field.FINGERPRINT, 0, Field.MINIPRINT, 1, Field.NUMBER, 2, Field.PATH, 3, Field.CONTENT_MODIFIED_AT, 4, Field.CONTENT_FINGERPRINT, 5)));
		assertThrows(IOException.class, () -> new Datim.Parser(new StringReader("miniprint\tpath\tcontent-fingerprint\tcontent-modifiedAt")).getFieldIndexes(),
				"Not all required headers present.");
	}

	/** @see Datim.Parser#getFieldIndexes() */
	@Test
	void testGetFieldIndexesCalledSubsequentlyReturnsSameReference() throws IOException {
		final Datim.Parser parser = new Datim.Parser(new StringReader("#\tminiprint\tpath\tcontent-modifiedAt\tcontent-fingerprint\tfingerprint"));
		final Map<Field, Integer> fieldIndexes = parser.getFieldIndexes();
		assertThat(parser.getFieldIndexes(), is((sameInstance(fieldIndexes))));
	}

	/** @see Datim.Parser#readImprint() */
	@Test
	void testReadImprint() throws IOException {
		final var input = """
				#\tminiprint\tpath\tcontent-modifiedAt\tcontent-fingerprint\tfingerprint
				81985529216486895\tc56f2ad0\t/foo.bar\t2022-05-22T20:48:16.7512146Z\tc3ab8ff13720e8ad9047dd39466b3c8974e592c2fa383d4a3960714caef0c4f2\tc56f2ad0a6e082790805ffabf1f68f13f77954ae6936ab1793edde7e101864c9
				""";
		assertThat(new Datim.Parser(new StringReader(input)).readImprint(),
				isPresentAndIs(new PathImprint(Path.of("/foo.bar"), FileTime.from(Instant.ofEpochSecond(1653252496, 751214600)),
						Hash.fromChecksum("c3ab8ff13720e8ad9047dd39466b3c8974e592c2fa383d4a3960714caef0c4f2"),
						Hash.fromChecksum("c56f2ad0a6e082790805ffabf1f68f13f77954ae6936ab1793edde7e101864c9"))));
	}

	/** @see Datim.Parser#readImprint() */
	@Test
	void verifyReadImprintReturnsEmptyIfNoImprintPresent() throws IOException {
		final var input = """
				#\tminiprint\tpath\tcontent-modifiedAt\tcontent-fingerprint\tfingerprint
				""";
		assertThat(new Datim.Parser(new StringReader(input)).readImprint(), isEmpty());
	}

	/** @see Datim.Parser#readImprint() */
	@Test
	void verifyReadImprintReturnsEmptyIfBasePathButNoImprintPresent() throws IOException {
		final Path rootDirectory = findFirst(FileSystems.getDefault().getRootDirectories()).orElseThrow(IllegalStateException::new);
		final Path fooBaseDirectory = rootDirectory.resolve("test").resolve("foo");
		final var input = """
				#\tminiprint\tpath\tcontent-modifiedAt\tcontent-fingerprint\tfingerprint
				/\t\t%s\t\t\t
				""".formatted(fooBaseDirectory);
		assertThat(new Datim.Parser(new StringReader(input)).readImprint(), isEmpty());
	}

	/** @see Datim.Parser#readImprint() */
	@Test
	void verifyReadImprintSkipsBasePathRecords() throws IOException {
		final Path rootDirectory = findFirst(FileSystems.getDefault().getRootDirectories()).orElseThrow(IllegalStateException::new);
		final Path fooBaseDirectory = rootDirectory.resolve("test").resolve("foo");
		final Path barBaseDirectory = rootDirectory.resolve("test").resolve("bar");
		final Path testFile = barBaseDirectory.resolve("test.bin");
		final var input = """
				#\tminiprint\tpath\tcontent-modifiedAt\tcontent-fingerprint\tfingerprint
				/\t\t%s\t\t\t
				/\t\t%s\t\t\t
				81985529216486895\tc56f2ad0\t%s\t2022-05-22T20:48:16.7512146Z\tc3ab8ff13720e8ad9047dd39466b3c8974e592c2fa383d4a3960714caef0c4f2\tc56f2ad0a6e082790805ffabf1f68f13f77954ae6936ab1793edde7e101864c9
				"""
				.formatted(fooBaseDirectory, barBaseDirectory, testFile);
		final var parser = new Datim.Parser(new StringReader(input));
		assertThat(parser.findCurrentBasePath(), isEmpty());
		assertThat(parser.readImprint(),
				isPresentAndIs(new PathImprint(testFile, FileTime.from(Instant.ofEpochSecond(1653252496, 751214600)),
						Hash.fromChecksum("c3ab8ff13720e8ad9047dd39466b3c8974e592c2fa383d4a3960714caef0c4f2"),
						Hash.fromChecksum("c56f2ad0a6e082790805ffabf1f68f13f77954ae6936ab1793edde7e101864c9"))));
		assertThat(parser.findCurrentBasePath(), isPresentAndIs(barBaseDirectory));
	}

	//Serializer

	/** @see Datim.Serializer#appendHeader(Appendable) */
	@Test
	void testSerializerAppendHeader() throws IOException {
		assertThat(new Datim.Serializer("\r\n").appendHeader(new StringBuilder()).toString(),
				is("#\tminiprint\tpath\tcontent-modifiedAt\tcontent-fingerprint\tfingerprint\r\n"));
	}

	/** @see Datim.Serializer#appendBasePath(Appendable, Path) */
	@Test
	void testSerializerAppendBasePath() throws IOException {
		final Path mockFilePath = mock(Path.class);
		when(mockFilePath.toAbsolutePath()).thenReturn(mockFilePath);
		final Path mockFileNamePath = mock(Path.class);
		final String filename = "foo.bar";
		when(mockFilePath.toString()).thenReturn("/" + filename);
		when(mockFileNamePath.toString()).thenReturn(filename);
		when(mockFilePath.getFileName()).thenReturn(mockFileNamePath);
		assertThat(new Datim.Serializer("\n").appendBasePath(new StringBuilder(), mockFilePath), hasToString("/\t\t/foo.bar\t\t\t\n"));
	}

	/** @see Datim.Serializer#appendImprint(Appendable, PathImprint, long) */
	@Test
	void testSerializerAppendImprint() throws IOException {
		final Path mockFilePath = mock(Path.class);
		when(mockFilePath.toRealPath(any())).thenReturn(mockFilePath);
		final Path mockFileNamePath = mock(Path.class);
		final String filename = "foo.bar";
		when(mockFilePath.toString()).thenReturn("/" + filename);
		when(mockFileNamePath.toString()).thenReturn(filename);
		when(mockFilePath.getFileName()).thenReturn(mockFileNamePath);
		final FileTime modifiedAt = FileTime.from(Instant.ofEpochSecond(1653252496, 751214600));
		final Hash contentFingerprint = FINGERPRINT_ALGORITHM.hash("foobar");
		final PathImprint imprint = PathImprint.forFile(mockFilePath, modifiedAt, contentFingerprint, FINGERPRINT_ALGORITHM);
		assertThat(new Datim.Serializer("\n").appendImprint(new StringBuilder(), imprint, 0x0123456789ABCDEFL), hasToString(
				"81985529216486895\tc56f2ad0\t/foo.bar\t2022-05-22T20:48:16.7512146Z\tc3ab8ff13720e8ad9047dd39466b3c8974e592c2fa383d4a3960714caef0c4f2\tc56f2ad0a6e082790805ffabf1f68f13f77954ae6936ab1793edde7e101864c9\n"));
	}

}
