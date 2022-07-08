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

import static com.globalmentor.java.Strings.*;
import static com.jordial.datimprint.file.FileSystemDatimprinter.FINGERPRINT_ALGORITHM;
import static java.nio.file.Files.*;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import com.globalmentor.security.Hash;

/**
 * Integration tests of {@link FileSystemDatimprinter}.
 * @implSpec These tests use a datimprinter which performs all "asynchronous" operations in the calling thread, simulating synchronous operation.
 * @author Garret Wilson
 */
public class FileSystemDatimprinterIT {

	private FileSystemDatimprinter testDatimprinter;

	@BeforeEach
	void setupDatimprinter() {
		testDatimprinter = new FileSystemDatimprinter(Runnable::run);
	}

	@AfterEach
	void teardownDatimprinter() throws IOException {
		testDatimprinter.close();
	}

	/** General smoke test for generating a directory tree imprint. */
	@Test
	void smokeTest() {

	}

	//files

	/** @see FileSystemDatimprinter#generateFileContentFingerprintAsync(Path) */
	@Test
	void testGenerateFileContentFingerprintAsync(@TempDir final Path tempDir) throws IOException {
		final String contents = "fooBar";
		final Path file = writeString(tempDir.resolve("foo.bar"), contents);
		final Hash contentFingerprint = FINGERPRINT_ALGORITHM.hash(contents);

		assertThat(testDatimprinter.generateFileContentFingerprintAsync(file).join(), is(contentFingerprint));
	}

	/** @see FileSystemDatimprinter#generateImprintAsync(Path) */
	@Test
	void testGenerateImprintAsyncFile(@TempDir final Path tempDir) throws IOException {
		final String filename = "foo.bar";
		final String content = "fooBar";
		final Path file = writeString(tempDir.resolve(filename), content);
		final FileTime modifiedAt = getLastModifiedTime(file);
		final Hash contentFingerprint = FINGERPRINT_ALGORITHM.hash(content);

		final PathImprint imprint = testDatimprinter.generateImprintAsync(file).join();
		assertThat(imprint.path(), is(file));
		assertThat(imprint.modifiedAt(), is(modifiedAt));
		assertThat(imprint.contentFingerprint(), is(contentFingerprint));
		assertThat(imprint.fingerprint(), is(PathImprint.generateFingerprint(file, modifiedAt, contentFingerprint, null, FINGERPRINT_ALGORITHM)));
	}

	//directories

	/** @see FileSystemDatimprinter#generateDirectoryContentChildrenFingerprintsAsync(Path) */
	@Test
	void testGenerateDirectoryContentFingerprintAsyncEmpty(@TempDir final Path tempDir) throws IOException {
		final Path directory = createDirectory(tempDir.resolve("foobar"));

		//important: normalize the order of the children, which the method should do as well
		final Hash directoryContentFingerprint = FINGERPRINT_ALGORITHM.hash(NO_STRINGS);
		final Hash directoryChildrenFingerprint = FINGERPRINT_ALGORITHM.hash(new Hash[0]);

		assertThat(testDatimprinter.generateDirectoryContentChildrenFingerprintsAsync(directory).join(),
				is(new FileSystemDatimprinter.DirectoryContentChildrenFingerprints(directoryContentFingerprint, directoryChildrenFingerprint)));
	}

	/** @see FileSystemDatimprinter#generateDirectoryContentChildrenFingerprintsAsync(Path) */
	@Test
	void testGenerateDirectoryContentFingerprintAsyncTwoChildFiles(@TempDir final Path tempDir) throws IOException {
		final Path directory = createDirectory(tempDir.resolve("foobar"));

		//foo child file
		final String fooFileContents = "foo";
		final Path fooFile = writeString(directory.resolve("foo.txt"), fooFileContents);
		final FileTime fooModifiedAt = getLastModifiedTime(fooFile);
		final Hash fooContentFingerprint = FINGERPRINT_ALGORITHM.hash(fooFileContents);
		final PathImprint fooImprint = PathImprint.forFile(fooFile, fooModifiedAt, fooContentFingerprint, FINGERPRINT_ALGORITHM);

		//bar child file
		final String barFileContents = "bar";
		final Path barFile = writeString(directory.resolve("bar.txt"), barFileContents);
		final FileTime barModifiedAt = getLastModifiedTime(barFile);
		final Hash barContentFingerprint = FINGERPRINT_ALGORITHM.hash(barFileContents);
		final PathImprint barImprint = PathImprint.forFile(barFile, barModifiedAt, barContentFingerprint, FINGERPRINT_ALGORITHM);

		//important: normalize the order of the children, which the method should do as well
		final Hash directoryContentFingerprint = FINGERPRINT_ALGORITHM.hash(barContentFingerprint, fooContentFingerprint);
		final Hash directoryChildrenFingerprint = FINGERPRINT_ALGORITHM.hash(barImprint.fingerprint(), fooImprint.fingerprint());

		assertThat(testDatimprinter.generateDirectoryContentChildrenFingerprintsAsync(directory).join(),
				is(new FileSystemDatimprinter.DirectoryContentChildrenFingerprints(directoryContentFingerprint, directoryChildrenFingerprint)));
	}

	//TODO create integration test(s) with subdirectories

	/** @see FileSystemDatimprinter#generateImprintAsync(Path) */
	@Test
	void testGenerateImprintAsyncDirectory(@TempDir final Path tempDir) throws IOException {
		final Path directory = createDirectory(tempDir.resolve("foobar"));

		//foo child file
		final String fooFileContents = "foo";
		final Path fooFile = writeString(directory.resolve("foo.txt"), fooFileContents);
		final FileTime fooModifiedAt = getLastModifiedTime(fooFile);
		final Hash fooContentFingerprint = FINGERPRINT_ALGORITHM.hash(fooFileContents);
		final PathImprint fooImprint = PathImprint.forFile(fooFile, fooModifiedAt, fooContentFingerprint, FINGERPRINT_ALGORITHM);

		//bar child file
		final String barFileContents = "bar";
		final Path barFile = writeString(directory.resolve("bar.txt"), barFileContents);
		final FileTime barModifiedAt = getLastModifiedTime(barFile);
		final Hash barContentFingerprint = FINGERPRINT_ALGORITHM.hash(barFileContents);
		final PathImprint barImprint = PathImprint.forFile(barFile, barModifiedAt, barContentFingerprint, FINGERPRINT_ALGORITHM);

		final FileTime directoryModifiedAt = getLastModifiedTime(directory); //get directory modification timestamp after its children are added/modified

		//important: normalize the order of the children, which the method should do as well
		final Hash directoryContentFingerprint = FINGERPRINT_ALGORITHM.hash(barContentFingerprint, fooContentFingerprint);
		final Hash directoryChildrenFingerprint = FINGERPRINT_ALGORITHM.hash(barImprint.fingerprint(), fooImprint.fingerprint());

		assertThat(testDatimprinter.generateDirectoryContentChildrenFingerprintsAsync(directory).join(),
				is(new FileSystemDatimprinter.DirectoryContentChildrenFingerprints(directoryContentFingerprint, directoryChildrenFingerprint)));

		final PathImprint imprint = testDatimprinter.generateImprintAsync(directory).join();
		assertThat(imprint.path(), is(directory));
		assertThat(imprint.modifiedAt(), is(directoryModifiedAt));
		assertThat(imprint.contentFingerprint(), is(directoryContentFingerprint));
		assertThat(imprint.fingerprint(),
				is(PathImprint.generateFingerprint(directory, directoryModifiedAt, directoryContentFingerprint, directoryChildrenFingerprint, FINGERPRINT_ALGORITHM)));
	}

}
