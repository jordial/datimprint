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
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assumptions.*;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.*;

import com.jordial.datimprint.file.PathChecker.*;
import com.jordial.datimprint.file.PathChecker.Result.*;

/**
 * Integration tests of {@link PathChecker}.
 * @implSpec These tests use a imprint generator which performs all "asynchronous" operations in the calling thread, simulating synchronous operation.
 * @author Garret Wilson
 */
public class PathCheckerIT {

	private PathImprintGenerator fixtureImprintGenerator;
	private PathChecker testPathChecker;
	private List<Result> testProducedResults;

	@BeforeEach
	void setupImprintGenerator() {
		fixtureImprintGenerator = PathImprintGenerator.builder().withExecutor(Runnable::run).build();
		testProducedResults = new CopyOnWriteArrayList<>(); //to allow for future multithreaded production
		testPathChecker = PathChecker.builder().withExecutor(Runnable::run).withResultConsumer(testProducedResults::add).build();

	}

	@AfterEach
	void teardownImprintGenerator() throws IOException {
		testPathChecker.close();
		testProducedResults.clear();
		fixtureImprintGenerator.close();
	}

	/**
	 * {@link PathChecker#checkPathAsync(Path, PathImprint)}
	 * @see PathChecker.FileResult
	 */
	@Test
	void testCheckPathAsyncFileResult(@TempDir final Path tempDir) throws IOException {
		final Path file = writeString(tempDir.resolve("foo.bar"), "foobar");
		final PathImprint imprint = fixtureImprintGenerator.generateImprintAsync(file).join();
		final FileResult fileResult = testPathChecker.new FileResult(file, imprint);
		assertThat(testPathChecker.checkPathAsync(file, imprint).join(), is(fileResult));
		assertThat(testProducedResults, containsInAnyOrder(fileResult));
	}

	/**
	 * {@link PathChecker#checkPathAsync(Path, PathImprint)}
	 * @see PathChecker.DirectoryResult
	 */
	@Test
	void testCheckPathAsyncDirectoryResult(@TempDir final Path tempDir) throws IOException {
		final Path directory = createDirectory(tempDir.resolve("dir"));
		final PathImprint imprint = fixtureImprintGenerator.generateImprintAsync(directory).join();
		final DirectoryResult directoryResult = testPathChecker.new DirectoryResult(directory, imprint);
		assertThat(testPathChecker.checkPathAsync(directory, imprint).join(), is(directoryResult));
		assertThat(testProducedResults, containsInAnyOrder(directoryResult));
	}

	/**
	 * {@link PathChecker#checkPathAsync(Path, PathImprint)}
	 * @see PathChecker.MissingPathResult
	 */
	@Test
	void testCheckPathAsyncMissingPathResult(@TempDir final Path tempDir) throws IOException {
		final Path file = writeString(tempDir.resolve("foo.bar"), "foobar");
		final PathImprint imprint = fixtureImprintGenerator.generateImprintAsync(file).join();
		final Path missingFile = tempDir.resolve("missing").resolve("foo.bar");
		final MissingPathResult missingPathResult = testPathChecker.new MissingPathResult(missingFile, imprint);
		assertThat(testPathChecker.checkPathAsync(missingFile, imprint).join(), is(missingPathResult));
		assertThat(testProducedResults, containsInAnyOrder(missingPathResult));
	}

	//FileResult

	/** @see PathChecker.FileResult */
	@Test
	void testFileResultMatches(@TempDir final Path tempDir) throws IOException {
		final Path file = writeString(tempDir.resolve("foo.bar"), "foobar");
		final PathImprint imprint = fixtureImprintGenerator.generateImprintAsync(file).join();
		final FileResult fileResult = testPathChecker.new FileResult(file, imprint);
		assertThat(fileResult.getPath(), is(file));
		assertThat(fileResult.getImprint(), is(imprint));
		assertThat(fileResult.isMatch(), is(true));
		assertThat(fileResult.getMismatches(), is(empty()));
		assertThat(fileResult.getContentModifiedAt(), is(getLastModifiedTime(file)));
		assertThat(fileResult.getContentFingerprint(), is(FINGERPRINT_ALGORITHM.hash(file)));
	}

	/** @see PathChecker.FileResult */
	@Test
	void testFileResultContentModifiedAtMismatch(@TempDir final Path tempDir) throws IOException {
		final Path file = writeString(tempDir.resolve("foo.bar"), "foobar");
		final PathImprint imprint = fixtureImprintGenerator.generateImprintAsync(file).join();

		setLastModifiedTime(file, FileTime.from(getLastModifiedTime(file).toInstant().minus(Duration.ofHours(1)))); //shift the time back an hour

		final FileResult fileResult = testPathChecker.new FileResult(file, imprint);
		assertThat(fileResult.getPath(), is(file));
		assertThat(fileResult.getImprint(), is(imprint));
		assertThat(fileResult.isMatch(), is(false));
		assertThat(fileResult.getMismatches(), is(EnumSet.of(Mismatch.CONTENT_MODIFIED_AT)));
		assertThat(fileResult.getContentModifiedAt(), is(getLastModifiedTime(file)));
		assertThat(fileResult.getContentFingerprint(), is(FINGERPRINT_ALGORITHM.hash(file)));
	}

	/** @see PathChecker.FileResult */
	@Test
	void testFileResultContentFingerprintMismatch(@TempDir final Path tempDir) throws IOException {
		final Path file = writeString(tempDir.resolve("foo.bar"), "foobar");
		final FileTime manualLastModifiedTime = FileTime.from(Instant.now());
		setLastModifiedTime(file, manualLastModifiedTime); //manually set before and after to ensure the same value without regard to e.g. nanoseconds
		final PathImprint imprint = fixtureImprintGenerator.generateImprintAsync(file).join();

		writeString(tempDir.resolve("foo.bar"), "bar");
		setLastModifiedTime(file, manualLastModifiedTime); //reset the modification timestamp

		final FileResult fileResult = testPathChecker.new FileResult(file, imprint);
		assertThat(fileResult.getPath(), is(file));
		assertThat(fileResult.getImprint(), is(imprint));
		assertThat(fileResult.isMatch(), is(false));
		assertThat(fileResult.getMismatches(), is(EnumSet.of(Mismatch.CONTENT_FINGERPRINT)));
		assertThat(fileResult.getContentModifiedAt(), is(getLastModifiedTime(file)));
		assertThat(fileResult.getContentFingerprint(), is(FINGERPRINT_ALGORITHM.hash(file)));
	}

	/** @see PathChecker.FileResult */
	@Test
	void testFileResultContentFingerprintContentModifiedAtMismatch(@TempDir final Path tempDir) throws IOException {
		final Path file = writeString(tempDir.resolve("foo.bar"), "foobar");
		final FileTime beforeLastModifiedTime = FileTime.from(Instant.now());
		final PathImprint imprint = fixtureImprintGenerator.generateImprintAsync(file).join();

		writeString(tempDir.resolve("foo.bar"), "bar");
		setLastModifiedTime(file, FileTime.from(beforeLastModifiedTime.toInstant().minus(Duration.ofHours(1)))); //shift the time back an hour

		final FileResult fileResult = testPathChecker.new FileResult(file, imprint);
		assertThat(fileResult.getPath(), is(file));
		assertThat(fileResult.getImprint(), is(imprint));
		assertThat(fileResult.isMatch(), is(false));
		assertThat(fileResult.getMismatches(), is(EnumSet.of(Mismatch.CONTENT_FINGERPRINT, Mismatch.CONTENT_MODIFIED_AT)));
		assertThat(fileResult.getContentModifiedAt(), is(getLastModifiedTime(file)));
		assertThat(fileResult.getContentFingerprint(), is(FINGERPRINT_ALGORITHM.hash(file)));
	}

	/**
	 * Checks that a filename case mismatch can be detected on Windows.
	 * @implNote This implementation assumes that the case sensitivity of a directory has not been changed on Windows, which is now possible in PowerShell using
	 *           {@code fsutil.exe file setCaseSensitiveInfo <path> enable}.
	 * @see PathChecker.FileResult
	 * @see <a href="https://docs.microsoft.com/en-us/windows/wsl/case-sensitivity">Adjust case sensitivity</a>
	 */
	@Test
	@EnabledOnOs(value = OS.WINDOWS, disabledReason = "Non-Windows systems use case-sensitive file systems.")
	void testFileResultFilenameMismatch(@TempDir final Path tempDir) throws IOException {
		final Path file = writeString(tempDir.resolve("foo.bar"), "foobar");
		assumeTrue(readAttributes(file, BasicFileAttributes.class) instanceof DosFileAttributes,
				"In addition to Windows OS we require DOS file attributes as one proxy for determining if the file system is case-insensitive.");
		final FileTime manualLastModifiedTime = FileTime.from(Instant.now());
		setLastModifiedTime(file, manualLastModifiedTime); //manually set before and after to ensure the same value without regard to e.g. nanoseconds
		final PathImprint imprint = fixtureImprintGenerator.generateImprintAsync(file).join();

		delete(file);
		final Path caseChangedFile = writeString(tempDir.resolve("FOO.BAR"), "foobar");
		setLastModifiedTime(caseChangedFile, manualLastModifiedTime); //reset the modification timestamp

		final FileResult fileResult = testPathChecker.new FileResult(caseChangedFile, imprint);
		assertThat(fileResult.getPath(), is(caseChangedFile));
		assertThat(fileResult.getImprint(), is(imprint));
		assertThat(fileResult.isMatch(), is(false));
		assertThat(fileResult.getMismatches(), is(EnumSet.of(Mismatch.FILENAME)));
		assertThat(fileResult.getContentModifiedAt(), is(getLastModifiedTime(file)));
		assertThat(fileResult.getContentFingerprint(), is(FINGERPRINT_ALGORITHM.hash(file)));
	}

	//DirectoryResult

	/** @see PathChecker.DirectoryResult */
	@Test
	void testDirectoryResultMatches(@TempDir final Path tempDir) throws IOException {
		final Path directory = createDirectory(tempDir.resolve("dir"));
		final PathImprint imprint = fixtureImprintGenerator.generateImprintAsync(directory).join();
		final DirectoryResult directoryResult = testPathChecker.new DirectoryResult(directory, imprint);
		assertThat(directoryResult.getPath(), is(directory));
		assertThat(directoryResult.getImprint(), is(imprint));
		assertThat(directoryResult.isMatch(), is(true));
		assertThat(directoryResult.getMismatches(), is(empty()));
		assertThat(directoryResult.getContentModifiedAt(), is(getLastModifiedTime(directory)));
	}

	/** @see PathChecker.DirectoryResult */
	@Test
	void testDirectoryResultContentModifiedAtMismatch(@TempDir final Path tempDir) throws IOException {
		final Path directory = createDirectory(tempDir.resolve("dir"));
		final PathImprint imprint = fixtureImprintGenerator.generateImprintAsync(directory).join();

		setLastModifiedTime(directory, FileTime.from(getLastModifiedTime(directory).toInstant().minus(Duration.ofHours(1)))); //shift the time back an hour

		final DirectoryResult directoryResult = testPathChecker.new DirectoryResult(directory, imprint);
		assertThat(directoryResult.getPath(), is(directory));
		assertThat(directoryResult.getImprint(), is(imprint));
		assertThat(directoryResult.isMatch(), is(false));
		assertThat(directoryResult.getMismatches(), is(EnumSet.of(Mismatch.CONTENT_MODIFIED_AT)));
		assertThat(directoryResult.getContentModifiedAt(), is(getLastModifiedTime(directory)));
	}

	/**
	 * Checks to see that a directory will match a path that does have a filename. This accounts for the situation in which a directory is being compared with the
	 * root, e.g. if a backup <code>B:\backup\</code> was made from <code>A:\</code>. The latter would not have a filename, yet the directories should still be
	 * counted as a match.
	 * @see PathChecker.DirectoryResult
	 */
	@Test
	void verifyDirectoryResultMatchesWhenFilenameMissing(@TempDir final Path tempDir) throws IOException {
		final Path directory = createDirectory(tempDir.resolve("dir"));
		final Path root = directory.getRoot();
		assumeTrue(root.getFileName() == null,
				"The root path does not have a filename (the simplest, most cross-platform compatible approach to attempt to get a path with no filename).");
		final PathImprint noFilenameDirectoryImprint = PathImprint.forDirectory(root, getLastModifiedTime(directory), FINGERPRINT_ALGORITHM.emptyHash(),
				FINGERPRINT_ALGORITHM.emptyHash(), FINGERPRINT_ALGORITHM);
		final DirectoryResult directoryResult = testPathChecker.new DirectoryResult(directory, noFilenameDirectoryImprint);
		assertThat(directoryResult.getPath(), is(directory));
		assertThat(directoryResult.getImprint(), is(noFilenameDirectoryImprint));
		assertThat(directoryResult.isMatch(), is(true));
		assertThat(directoryResult.getMismatches(), is(empty()));
		assertThat(directoryResult.getContentModifiedAt(), is(getLastModifiedTime(directory)));
	}

	//MissingPathResult

	/** @see PathChecker.MissingPathResult */
	@Test
	void testMissingPathResult(@TempDir final Path tempDir) throws IOException {
		final Path file = writeString(tempDir.resolve("foo.bar"), "foobar");
		final PathImprint imprint = fixtureImprintGenerator.generateImprintAsync(file).join();
		final Path missingFile = tempDir.resolve("missing").resolve("foo.bar");
		final MissingPathResult missingPathResult = testPathChecker.new MissingPathResult(missingFile, imprint);
		assertThat(missingPathResult.getPath(), is(missingFile));
		assertThat(missingPathResult.getImprint(), is(imprint));
		assertThat(missingPathResult.isMatch(), is(false));
		assertThat(missingPathResult.getMismatches(), is(empty()));
	}

}
