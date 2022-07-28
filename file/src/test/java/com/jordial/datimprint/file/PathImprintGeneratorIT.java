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
import static java.nio.file.Files.*;
import static java.util.stream.Collectors.*;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import static org.junit.Assume.*;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.*;

import com.globalmentor.security.Hash;

/**
 * Integration tests of {@link PathImprintGenerator}.
 * @implSpec These tests use a imprint generator which performs all "asynchronous" operations in the calling thread, simulating synchronous operation.
 * @author Garret Wilson
 */
public class PathImprintGeneratorIT {

	private PathImprintGenerator testImprintGenerator;
	private List<PathImprint> testProducedImprints;

	@BeforeEach
	void setupImprintGenerator() {
		testProducedImprints = new CopyOnWriteArrayList<>(); //to allow for future multithreaded production
		testImprintGenerator = PathImprintGenerator.builder().withExecutor(Runnable::run).withImprintConsumer(testProducedImprints::add).build();
	}

	@AfterEach
	void teardownImprintGenerator() throws IOException {
		testImprintGenerator.close();
		testProducedImprints.clear();
	}

	/**
	 * Tests generating and producing an imprint for a multilevel tree with files and directories, intending to capture the most common types of directory/file
	 * combinations encountered (e.g. empty directories, nested directories, empty files, binary files).
	 * @see PathImprintGenerator#produceImprint(Path)
	 */
	@Test
	void testProduceImprintSmokeTest(@TempDir final Path tempDir) throws IOException {
		final Path exampleTextFile = writeString(tempDir.resolve("example.txt"), "stuff"); //`/example.txt`: "stuff"
		final PathImprint exampleTextFileImprint = testImprintGenerator.generateImprintAsync(exampleTextFile).join();
		final byte[] exampleBytes = new byte[] {0x03, (byte)0xFE, 0x02, 0x01, (byte)0xFF, (byte)0xAB, (byte)0x98, 0x00, 0x12};
		final Path exampleBinaryFile = write(tempDir.resolve("example.bin"), exampleBytes); //`/example.bin`: 0x03FE0201FFAB980012
		final PathImprint exampleBinaryFileImprint = testImprintGenerator.generateImprintAsync(exampleBinaryFile).join();

		final Path foobarDirectory = createDirectory(tempDir.resolve("foobar")); //`/foobar/`
		final Path fooFile = writeString(foobarDirectory.resolve("foo.txt"), "foo"); //`/foobar/foo.txt`: "foo"
		final PathImprint fooFileImprint = testImprintGenerator.generateImprintAsync(fooFile).join();
		final Path barFile = writeString(foobarDirectory.resolve("bar.txt"), "bar"); //`/foobar/bar.txt`: "bar"
		final PathImprint barFileImprint = testImprintGenerator.generateImprintAsync(barFile).join();

		final Path emptyDirectory = createDirectory(tempDir.resolve("empty")); //`/empty/`

		final Path level1Directory = createDirectory(tempDir.resolve("level-1")); //`/level-1/`
		final Path level1ThisFile = writeString(level1Directory.resolve("this.txt"), "level-1-this"); //`/level1/this.txt`: "level-1-this"
		final PathImprint level1ThisFileImprint = testImprintGenerator.generateImprintAsync(level1ThisFile).join();
		final Path level1EmptyFile = writeString(level1Directory.resolve("empty.bin"), ""); //`/level-1/empty.bin`: ""
		final PathImprint level1EmptyFileImprint = testImprintGenerator.generateImprintAsync(level1EmptyFile).join();

		final Path level2aDirectory = createDirectory(level1Directory.resolve("level-2a")); //`/level-1/level-2a/` (empty)

		final Path level2bDirectory = createDirectory(level1Directory.resolve("level-2b")); //`/level-1/level-2b/`

		final Path level3Directory = createDirectory(level2bDirectory.resolve("level-3")); //`/level-1/level-2b/level-3/`
		final Path level3ThatFile = writeString(level3Directory.resolve("that.txt"), "level-3-that"); //`/level-1/level-2b/level-3/that.txt`: "level-3-that"
		final PathImprint level3ThatFileImprint = testImprintGenerator.generateImprintAsync(level3ThatFile).join();

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
				FINGERPRINT_ALGORITHM.hash(emptyDirectoryImprint.contentFingerprint(), exampleBinaryFileImprint.contentFingerprint(),
						exampleTextFileImprint.contentFingerprint(), foobarDirectoryImprint.contentFingerprint(), level1DirectoryImprint.contentFingerprint()),
				FINGERPRINT_ALGORITHM.hash(emptyDirectoryImprint.fingerprint(), exampleBinaryFileImprint.fingerprint(), exampleTextFileImprint.fingerprint(),
						foobarDirectoryImprint.fingerprint(), level1DirectoryImprint.fingerprint()),
				FINGERPRINT_ALGORITHM);
		assertThat(testImprintGenerator.produceImprint(tempDir), is(tempDirectoryImprint));
		assertThat(testProducedImprints,
				containsInAnyOrder(tempDirectoryImprint, exampleTextFileImprint, exampleBinaryFileImprint, foobarDirectoryImprint, fooFileImprint, barFileImprint,
						emptyDirectoryImprint, level1DirectoryImprint, level1ThisFileImprint, level1EmptyFileImprint, level2aDirectoryImprint, level2bDirectoryImprint,
						level3DirectoryImprint, level3ThatFileImprint));
	}

	//files

	/** @see PathImprintGenerator#generateFileContentFingerprintAsync(Path) */
	@Test
	void testGenerateFileContentFingerprintAsyncText(@TempDir final Path tempDir) throws IOException {
		final String contents = "fooBar";
		final Path file = writeString(tempDir.resolve("foo.bar"), contents);
		final Hash contentFingerprint = FINGERPRINT_ALGORITHM.hash(contents);

		assertThat(testImprintGenerator.generateFileContentFingerprintAsync(file).join(), is(contentFingerprint));
		assertThat(testProducedImprints, is(empty()));
	}

	/** @see PathImprintGenerator#generateFileContentFingerprintAsync(Path) */
	@Test
	void testGenerateFileContentFingerprintAsyncBinary(@TempDir final Path tempDir) throws IOException {
		final byte[] contents = new byte[] {0x03, (byte)0xFE, 0x02, 0x01, (byte)0xFF, (byte)0xAB, (byte)0x98, 0x00, 0x12};
		final Path file = write(tempDir.resolve("foo.bar"), contents);
		final Hash contentFingerprint = FINGERPRINT_ALGORITHM.hash(contents);

		assertThat(testImprintGenerator.generateFileContentFingerprintAsync(file).join(), is(contentFingerprint));
		assertThat(testProducedImprints, is(empty()));
	}

	/** @see PathImprintGenerator#generateImprintAsync(Path) */
	@Test
	void testGenerateImprintAsyncFile(@TempDir final Path tempDir) throws IOException {
		final String filename = "foo.bar";
		final String content = "fooBar";
		final Path file = writeString(tempDir.resolve(filename), content);
		final FileTime contentModifiedAt = getLastModifiedTime(file);
		final Hash contentFingerprint = FINGERPRINT_ALGORITHM.hash(content);

		final PathImprint imprint = testImprintGenerator.generateImprintAsync(file).join();
		assertThat(imprint.path(), is(file));
		assertThat(imprint.contentModifiedAt(), is(contentModifiedAt));
		assertThat(imprint.contentFingerprint(), is(contentFingerprint));
		assertThat(imprint.fingerprint(), is(PathImprint.generateFingerprint(file, contentModifiedAt, contentFingerprint, null, FINGERPRINT_ALGORITHM)));
		assertThat(testProducedImprints, is(empty()));
	}

	/** @see PathImprintGenerator#produceImprintAsync(Path) */
	@Test
	void testProduceImprintAsyncFile(@TempDir final Path tempDir) throws IOException {
		final String filename = "foo.bar";
		final String content = "fooBar";
		final Path file = writeString(tempDir.resolve(filename), content);
		final FileTime contentModifiedAt = getLastModifiedTime(file);
		final Hash contentFingerprint = FINGERPRINT_ALGORITHM.hash(content);

		final PathImprint imprint = testImprintGenerator.produceImprintAsync(file).join();
		assertThat(imprint.path(), is(file));
		assertThat(imprint.contentModifiedAt(), is(contentModifiedAt));
		assertThat(imprint.contentFingerprint(), is(contentFingerprint));
		assertThat(imprint.fingerprint(), is(PathImprint.generateFingerprint(file, contentModifiedAt, contentFingerprint, null, FINGERPRINT_ALGORITHM)));
		assertThat(testProducedImprints, containsInAnyOrder(imprint));
	}

	//directories

	/** @see PathImprintGenerator#generateDirectoryContentChildrenFingerprintsAsync(Path) */
	@Test
	void testGenerateDirectoryContentFingerprintAsyncEmpty(@TempDir final Path tempDir) throws IOException {
		final Path directory = createDirectory(tempDir.resolve("foobar"));

		//important: normalize the order of the children, which the method should do as well
		final Hash directoryContentFingerprint = FINGERPRINT_ALGORITHM.emptyHash();
		final Hash directoryChildrenFingerprint = FINGERPRINT_ALGORITHM.emptyHash();

		assertThat(testImprintGenerator.generateDirectoryContentChildrenFingerprintsAsync(directory).join(),
				is(new PathImprintGenerator.DirectoryContentChildrenFingerprints(directoryContentFingerprint, directoryChildrenFingerprint)));
		assertThat(testProducedImprints, is(empty()));
	}

	/** @see PathImprintGenerator#generateDirectoryContentChildrenFingerprintsAsync(Path) */
	@Test
	void testGenerateDirectoryContentChildrenFingerprintsAsyncTwoChildFiles(@TempDir final Path tempDir) throws IOException {
		final Path directory = createDirectory(tempDir.resolve("foobar"));

		//foo child file
		final String fooFileContents = "foo";
		final Path fooFile = writeString(directory.resolve("foo.txt"), fooFileContents);
		final FileTime fooContentModifiedAt = getLastModifiedTime(fooFile);
		final Hash fooContentFingerprint = FINGERPRINT_ALGORITHM.hash(fooFileContents);
		final PathImprint fooImprint = PathImprint.forFile(fooFile, fooContentModifiedAt, fooContentFingerprint, FINGERPRINT_ALGORITHM);

		//bar child file
		final String barFileContents = "bar";
		final Path barFile = writeString(directory.resolve("bar.txt"), barFileContents);
		final FileTime barContentModifiedAt = getLastModifiedTime(barFile);
		final Hash barContentFingerprint = FINGERPRINT_ALGORITHM.hash(barFileContents);
		final PathImprint barImprint = PathImprint.forFile(barFile, barContentModifiedAt, barContentFingerprint, FINGERPRINT_ALGORITHM);

		//important: normalize the order of the children, which the method should do as well
		final Hash directoryContentFingerprint = FINGERPRINT_ALGORITHM.hash(barContentFingerprint, fooContentFingerprint);
		final Hash directoryChildrenFingerprint = FINGERPRINT_ALGORITHM.hash(barImprint.fingerprint(), fooImprint.fingerprint());

		assertThat(testImprintGenerator.generateDirectoryContentChildrenFingerprintsAsync(directory).join(),
				is(new PathImprintGenerator.DirectoryContentChildrenFingerprints(directoryContentFingerprint, directoryChildrenFingerprint)));
		assertThat(testProducedImprints, containsInAnyOrder(fooImprint, barImprint));
	}

	/** @see PathImprintGenerator#produceChildImprintsAsync(Path) */
	@Test
	void testProduceChildImprintsAsync(@TempDir final Path tempDir) throws IOException {
		final Path directory = createDirectory(tempDir.resolve("foobar"));

		//foo child file
		final String fooFileContents = "foo";
		final Path fooFile = writeString(directory.resolve("foo.txt"), fooFileContents);
		final FileTime fooContentModifiedAt = getLastModifiedTime(fooFile);
		final Hash fooContentFingerprint = FINGERPRINT_ALGORITHM.hash(fooFileContents);
		final PathImprint fooImprint = PathImprint.forFile(fooFile, fooContentModifiedAt, fooContentFingerprint, FINGERPRINT_ALGORITHM);

		//bar child file
		final String barFileContents = "bar";
		final Path barFile = writeString(directory.resolve("bar.txt"), barFileContents);
		final FileTime barContentModifiedAt = getLastModifiedTime(barFile);
		final Hash barContentFingerprint = FINGERPRINT_ALGORITHM.hash(barFileContents);
		final PathImprint barImprint = PathImprint.forFile(barFile, barContentModifiedAt, barContentFingerprint, FINGERPRINT_ALGORITHM);

		final Map<Path, PathImprint> childImprintsByPath = testImprintGenerator.produceChildImprintsAsync(directory).join().entrySet().stream()
				.collect(toMap(Map.Entry::getKey, entry -> entry.getValue().join()));
		assertThat("Produced child imprints were as expected.", testProducedImprints, containsInAnyOrder(fooImprint, barImprint));
		assertThat("Produced child imprints were returned.", childImprintsByPath.values(), containsInAnyOrder(testProducedImprints.toArray()));
		childImprintsByPath.entrySet().forEach(childImprintByPath -> assertThat("Returnd child imprint was mapped to correctPath.", childImprintByPath.getKey(),
				is(childImprintByPath.getValue().path())));
	}

	/**
	 * Verify that any child directories that are hidden and marked as DOS "system" directories are ignored. This is to prevent {@link AccessDeniedException} when
	 * trying to access directories such as <code>System Volume Information</code> and <code>$RECYCLE.BIN</code> on Windows file systems.
	 * @see PathImprintGenerator#produceChildImprintsAsync(Path)
	 */
	@Test
	@EnabledOnOs({OS.WINDOWS})
	void verifyProduceChildImprintsAsyncIgnoresDosHiddenSystemDirectories(@TempDir final Path tempDir) throws IOException {
		final Path directory = createDirectory(tempDir.resolve("dir"));
		assumeThat("We assume that on Windows the file system uses DOS attributes; otherwise this test will not work.",
				readAttributes(directory, BasicFileAttributes.class), isA(DosFileAttributes.class));

		final Path fooFile = writeString(directory.resolve("foo.txt"), "foo");
		final Path hiddenFile = writeString(directory.resolve("hidden.txt"), "hidden");
		setAttribute(hiddenFile, "dos:hidden", true);
		final Path systemFile = writeString(directory.resolve("system.txt"), "system");
		setAttribute(systemFile, "dos:system", true);
		final Path hiddenSystemFile = writeString(directory.resolve("hidden-system.txt"), "hidden,system");
		setAttribute(hiddenSystemFile, "dos:hidden", true);
		setAttribute(hiddenSystemFile, "dos:system", true);
		final Path fooDirectory = createDirectory(directory.resolve("foo"));
		final Path hiddenDirectory = createDirectory(directory.resolve("hidden"));
		setAttribute(hiddenDirectory, "dos:hidden", true);
		final Path systemDirectory = createDirectory(directory.resolve("system"));
		setAttribute(systemDirectory, "dos:system", true);
		final Path hiddenSystemDirectory = createDirectory(directory.resolve("hidden-system"));
		setAttribute(hiddenSystemDirectory, "dos:hidden", true);
		setAttribute(hiddenSystemDirectory, "dos:system", true);
		final Path barFile = writeString(directory.resolve("bar.txt"), "bar");
		final Path barDirectory = createDirectory(directory.resolve("bar"));

		assertThat(testImprintGenerator.produceChildImprintsAsync(directory).join().keySet(), //should contain all children except hidden+system directory
				containsInAnyOrder(fooFile, barFile, hiddenFile, systemFile, hiddenSystemFile, fooDirectory, barDirectory, hiddenDirectory, systemDirectory));
	}

	/** @see PathImprintGenerator#generateImprintAsync(Path) */
	@Test
	void testGenerateImprintAsyncDirectory(@TempDir final Path tempDir) throws IOException {
		final Path directory = createDirectory(tempDir.resolve("foobar"));

		//foo child file
		final String fooFileContents = "foo";
		final Path fooFile = writeString(directory.resolve("foo.txt"), fooFileContents);
		final FileTime fooContentModifiedAt = getLastModifiedTime(fooFile);
		final Hash fooContentFingerprint = FINGERPRINT_ALGORITHM.hash(fooFileContents);
		final PathImprint fooImprint = PathImprint.forFile(fooFile, fooContentModifiedAt, fooContentFingerprint, FINGERPRINT_ALGORITHM);

		//bar child file
		final String barFileContents = "bar";
		final Path barFile = writeString(directory.resolve("bar.txt"), barFileContents);
		final FileTime barContentModifiedAt = getLastModifiedTime(barFile);
		final Hash barContentFingerprint = FINGERPRINT_ALGORITHM.hash(barFileContents);
		final PathImprint barImprint = PathImprint.forFile(barFile, barContentModifiedAt, barContentFingerprint, FINGERPRINT_ALGORITHM);

		final FileTime directoryContentModifiedAt = getLastModifiedTime(directory); //get directory modification timestamp after its children are added/modified

		//important: normalize the order of the children, which the method should do as well
		final Hash directoryContentFingerprint = FINGERPRINT_ALGORITHM.hash(barContentFingerprint, fooContentFingerprint);
		final Hash directoryChildrenFingerprint = FINGERPRINT_ALGORITHM.hash(barImprint.fingerprint(), fooImprint.fingerprint());

		final PathImprint imprint = testImprintGenerator.generateImprintAsync(directory).join();
		assertThat(imprint.path(), is(directory));
		assertThat(imprint.contentModifiedAt(), is(directoryContentModifiedAt));
		assertThat(imprint.contentFingerprint(), is(directoryContentFingerprint));
		assertThat(imprint.fingerprint(), is(PathImprint.generateFingerprint(directory, directoryContentModifiedAt, directoryContentFingerprint,
				directoryChildrenFingerprint, FINGERPRINT_ALGORITHM)));
		assertThat(testProducedImprints, containsInAnyOrder(fooImprint, barImprint));
	}

	/** @see PathImprintGenerator#produceImprintAsync(Path) */
	@Test
	void testProduceImprintAsyncDirectory(@TempDir final Path tempDir) throws IOException {
		final Path directory = createDirectory(tempDir.resolve("foobar"));

		//foo child file
		final String fooFileContents = "foo";
		final Path fooFile = writeString(directory.resolve("foo.txt"), fooFileContents);
		final FileTime fooContentModifiedAt = getLastModifiedTime(fooFile);
		final Hash fooContentFingerprint = FINGERPRINT_ALGORITHM.hash(fooFileContents);
		final PathImprint fooImprint = PathImprint.forFile(fooFile, fooContentModifiedAt, fooContentFingerprint, FINGERPRINT_ALGORITHM);

		//bar child file
		final String barFileContents = "bar";
		final Path barFile = writeString(directory.resolve("bar.txt"), barFileContents);
		final FileTime barContentModifiedAt = getLastModifiedTime(barFile);
		final Hash barContentFingerprint = FINGERPRINT_ALGORITHM.hash(barFileContents);
		final PathImprint barImprint = PathImprint.forFile(barFile, barContentModifiedAt, barContentFingerprint, FINGERPRINT_ALGORITHM);

		final FileTime directoryContentModifiedAt = getLastModifiedTime(directory); //get directory modification timestamp after its children are added/modified

		//important: normalize the order of the children, which the method should do as well
		final Hash directoryContentFingerprint = FINGERPRINT_ALGORITHM.hash(barContentFingerprint, fooContentFingerprint);
		final Hash directoryChildrenFingerprint = FINGERPRINT_ALGORITHM.hash(barImprint.fingerprint(), fooImprint.fingerprint());

		final PathImprint imprint = testImprintGenerator.produceImprintAsync(directory).join();
		assertThat(imprint.path(), is(directory));
		assertThat(imprint.contentModifiedAt(), is(directoryContentModifiedAt));
		assertThat(imprint.contentFingerprint(), is(directoryContentFingerprint));
		assertThat(imprint.fingerprint(), is(PathImprint.generateFingerprint(directory, directoryContentModifiedAt, directoryContentFingerprint,
				directoryChildrenFingerprint, FINGERPRINT_ALGORITHM)));
		assertThat(testProducedImprints, containsInAnyOrder(imprint, fooImprint, barImprint));
	}

}
