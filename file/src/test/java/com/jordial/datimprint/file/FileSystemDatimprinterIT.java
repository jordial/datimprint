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

	/**
	 * Tests generating an imprint for a multilevel tree with files and directories, intending to capture the most common types of directory/file combinations
	 * encountered (e.g. empty directories, nested directories, empty files, binary files).
	 * @see FileSystemDatimprinter#generateImprintAsync(Path)
	 */
	@Test
	void testGenerateImprintAsyncSmokeTest(@TempDir final Path tempDir) throws IOException {
		final Path exampleTextFile = writeString(tempDir.resolve("example.txt"), "stuff"); //`/example.txt`: "stuff"
		final PathImprint exampleTextFileImprint = testDatimprinter.generateImprintAsync(exampleTextFile).join();
		final byte[] exampleBytes = new byte[] {0x03, (byte)0xFE, 0x02, 0x01, (byte)0xFF, (byte)0xAB, (byte)0x98, 0x00, 0x12};
		final Path exampleBinFile = write(tempDir.resolve("example.bin"), exampleBytes); //`/example.bin`: 0x03FE0201FFAB980012
		final PathImprint exampleBinFileImprint = testDatimprinter.generateImprintAsync(exampleBinFile).join();

		final Path foobarDirectory = createDirectory(tempDir.resolve("foobar")); //`/foobar/`
		final Path fooFile = writeString(foobarDirectory.resolve("foo.txt"), "foo"); //`/foobar/foo.txt`: "foo"
		final PathImprint fooFileImprint = testDatimprinter.generateImprintAsync(fooFile).join();
		final Path barFile = writeString(foobarDirectory.resolve("bar.txt"), "bar"); //`/foobar/bar.txt`: "bar"
		final PathImprint barFileImprint = testDatimprinter.generateImprintAsync(barFile).join();

		final Path emptyDirectory = createDirectory(tempDir.resolve("empty")); //`/empty/`

		final Path level1Directory = createDirectory(tempDir.resolve("level-1")); //`/level-1/`
		final Path level1ThisFile = writeString(level1Directory.resolve("this.txt"), "this"); //`/level1/foo.txt`: "this"
		final PathImprint level1ThisFileImprint = testDatimprinter.generateImprintAsync(level1ThisFile).join();
		final Path level1EmptyFile = writeString(level1Directory.resolve("empty.bin"), ""); //`/level-1/empty.bin`: "deep foo"
		final PathImprint level1EmptyFileImprint = testDatimprinter.generateImprintAsync(level1EmptyFile).join();

		final Path level2aDirectory = createDirectory(level1Directory.resolve("level-2a")); //`/level-1/level-2a/` (empty)

		final Path level2bDirectory = createDirectory(level1Directory.resolve("level-2b")); //`/level-1/level-2b/`

		final Path level3Directory = createDirectory(level2bDirectory.resolve("level-3")); //`/level-1/level-2b/level-3/`
		final Path level3ThatFile = writeString(level3Directory.resolve("bar.txt"), "that"); //`/level-1/level-2b/level-3/bar.txt`: "that"
		final PathImprint level3ThatFileImprint = testDatimprinter.generateImprintAsync(level3ThatFile).join();

		final PathImprint level3DirectoryImprint = PathImprint.forDirectory(level3Directory, getLastModifiedTime(level3Directory),
				FINGERPRINT_ALGORITHM.hash(level3ThatFileImprint.contentFingerprint()), FINGERPRINT_ALGORITHM.hash(level3ThatFileImprint.fingerprint()),
				FINGERPRINT_ALGORITHM);
		final PathImprint level2bDirectoryImprint = PathImprint.forDirectory(level2bDirectory, getLastModifiedTime(level2bDirectory),
				FINGERPRINT_ALGORITHM.hash(level3DirectoryImprint.contentFingerprint()), FINGERPRINT_ALGORITHM.hash(level3DirectoryImprint.fingerprint()),
				FINGERPRINT_ALGORITHM);
		final PathImprint level2aDirectoryImprint = PathImprint.forDirectory(level2aDirectory, getLastModifiedTime(level2aDirectory),
				FINGERPRINT_ALGORITHM.emptyHash(), FINGERPRINT_ALGORITHM.emptyHash(), FINGERPRINT_ALGORITHM);
		final PathImprint level1DirectoryImprint = PathImprint.forDirectory(level1Directory, getLastModifiedTime(level1Directory),
				FINGERPRINT_ALGORITHM.hash(level1EmptyFileImprint.contentFingerprint(), level2aDirectoryImprint.contentFingerprint(),
						level2bDirectoryImprint.contentFingerprint(), level1ThisFileImprint.contentFingerprint()),
				FINGERPRINT_ALGORITHM.hash(level1EmptyFileImprint.fingerprint(), level2aDirectoryImprint.fingerprint(), level2bDirectoryImprint.fingerprint(),
						level1ThisFileImprint.fingerprint()),
				FINGERPRINT_ALGORITHM);
		final PathImprint emptyDirectoryImprint = PathImprint.forDirectory(emptyDirectory, getLastModifiedTime(emptyDirectory), FINGERPRINT_ALGORITHM.emptyHash(),
				FINGERPRINT_ALGORITHM.emptyHash(), FINGERPRINT_ALGORITHM);
		final PathImprint foobarDirectoryImprint = PathImprint.forDirectory(foobarDirectory, getLastModifiedTime(foobarDirectory),
				FINGERPRINT_ALGORITHM.hash(barFileImprint.contentFingerprint(), fooFileImprint.contentFingerprint()),
				FINGERPRINT_ALGORITHM.hash(barFileImprint.fingerprint(), fooFileImprint.fingerprint()), FINGERPRINT_ALGORITHM);
		final PathImprint tempDirectoryImprint = PathImprint.forDirectory(tempDir, getLastModifiedTime(tempDir),
				FINGERPRINT_ALGORITHM.hash(emptyDirectoryImprint.contentFingerprint(), exampleBinFileImprint.contentFingerprint(),
						exampleTextFileImprint.contentFingerprint(), foobarDirectoryImprint.contentFingerprint(), level1DirectoryImprint.contentFingerprint()),
				FINGERPRINT_ALGORITHM.hash(emptyDirectoryImprint.fingerprint(), exampleBinFileImprint.fingerprint(), exampleTextFileImprint.fingerprint(),
						foobarDirectoryImprint.fingerprint(), level1DirectoryImprint.fingerprint()),
				FINGERPRINT_ALGORITHM);
		assertThat(testDatimprinter.generateImprintAsync(tempDir).join(), is(tempDirectoryImprint));
	}

	//files

	/** @see FileSystemDatimprinter#generateFileContentFingerprintAsync(Path) */
	@Test
	void testGenerateFileContentFingerprintAsyncText(@TempDir final Path tempDir) throws IOException {
		final String contents = "fooBar";
		final Path file = writeString(tempDir.resolve("foo.bar"), contents);
		final Hash contentFingerprint = FINGERPRINT_ALGORITHM.hash(contents);

		assertThat(testDatimprinter.generateFileContentFingerprintAsync(file).join(), is(contentFingerprint));
	}

	/** @see FileSystemDatimprinter#generateFileContentFingerprintAsync(Path) */
	@Test
	void testGenerateFileContentFingerprintAsyncBinary(@TempDir final Path tempDir) throws IOException {
		final byte[] contents = new byte[] {0x03, (byte)0xFE, 0x02, 0x01, (byte)0xFF, (byte)0xAB, (byte)0x98, 0x00, 0x12};
		final Path file = write(tempDir.resolve("foo.bar"), contents);
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
		final Hash directoryContentFingerprint = FINGERPRINT_ALGORITHM.emptyHash();
		final Hash directoryChildrenFingerprint = FINGERPRINT_ALGORITHM.emptyHash();

		assertThat(testDatimprinter.generateDirectoryContentChildrenFingerprintsAsync(directory).join(),
				is(new FileSystemDatimprinter.DirectoryContentChildrenFingerprints(directoryContentFingerprint, directoryChildrenFingerprint)));
	}

	/** @see FileSystemDatimprinter#generateDirectoryContentChildrenFingerprintsAsync(Path) */
	@Test
	void testGenerateDirectoryContentChildrenFingerprintsAsyncTwoChildFiles(@TempDir final Path tempDir) throws IOException {
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
