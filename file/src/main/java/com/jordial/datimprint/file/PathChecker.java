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

import static com.globalmentor.io.Paths.*;
import static com.globalmentor.java.Conditions.*;
import static com.jordial.datimprint.file.PathImprintGenerator.FINGERPRINT_ALGORITHM;
import static java.nio.file.Files.*;
import static java.util.Objects.*;
import static java.util.concurrent.Executors.*;
import static org.zalando.fauxpas.FauxPas.*;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.*;

import javax.annotation.*;

import com.globalmentor.security.*;

import io.clogr.Clogged;

/**
 * Checks individual paths against their imprints to detect changes in content, filename case, and modification timestamp.
 * @implSpec By default symbolic links are followed.
 * @author Garret Wilson
 * @see PathImprintGenerator#FINGERPRINT_ALGORITHM
 */
public class PathChecker implements Closeable, Clogged {

	private final Executor executor;

	/** @return The executor checking imprints; may or may not be an instance of {@link ExecutorService}. */
	protected Executor getExecutor() {
		return executor;
	}

	private final Optional<Listener> foundListener;

	/** @return The listener, if any, to events from this class. */
	protected Optional<Listener> findListener() {
		return foundListener;
	}

	/**
	 * No-args constructor.
	 * @implSpec Checking uses a thread pool that by default has the same number of threads as the number of available processors.
	 * @see Builder#newDefaultExecutor()
	 */
	public PathChecker() {
		this(Builder.newDefaultExecutor());
	}

	/**
	 * Executor constructor.
	 * @param executor The executor for checking imprints; may or may not be an instance of {@link ExecutorService}.
	 */
	public PathChecker(@Nonnull final Executor executor) {
		this.executor = requireNonNull(executor);
		this.foundListener = Optional.empty();
	}

	/**
	 * Builder constructor.
	 * @param builder The builder providing the specification for creating a new instance.
	 */
	protected PathChecker(@Nonnull final Builder builder) {
		this.executor = builder.determineExecutor();
		this.foundListener = builder.findListener();
	}

	/** @return A new builder for specifying a new {@link PathChecker}. */
	public static PathChecker.Builder builder() {
		return new Builder();
	}

	/**
	 * {@inheritDoc}
	 * @implSpec This implementation shuts down the executor if it is an instance of {@link ExecutorService}.
	 * @throws IOException If the executor service could not be shut down.
	 * @see #getExecutor()
	 */
	@Override
	public void close() throws IOException {
		if(executor instanceof ExecutorService executorService) {
			executorService.shutdown();
			try {
				if(!executorService.awaitTermination(3, TimeUnit.MINUTES)) {
					executorService.shutdownNow();
					if(!executorService.awaitTermination(1, TimeUnit.MINUTES)) {
						throw new IOException("Imprint checking service not shut down properly.");
					}
				}
			} catch(final InterruptedException interruptedException) {
				executorService.shutdownNow();
				Thread.currentThread().interrupt();
			}
		}
	}

	/**
	 * Asynchronously checks a single path, which must be a regular file or a directory, against an imprint.
	 * @param path The path being checked.
	 * @param imprint The imprint against which the path is being checked.
	 * @return A future result of checking the path.
	 * @throws IOException if there is a problem accessing the file system.
	 */
	public CompletableFuture<Result> checkPathAsync(@Nonnull final Path path, @Nonnull final PathImprint imprint) throws IOException {
		getLogger().trace("Checking path `{}` against imprint {}.", path, imprint);
		findListener().ifPresent(listener -> listener.onCheckPath(path, imprint));
		return CompletableFuture.supplyAsync(throwingSupplier(() -> {
			final Result result;
			if(isRegularFile(path)) {
				result = new FileResult(path, imprint);
			} else if(isDirectory(path)) {
				result = new DirectoryResult(path, imprint);
			} else if(!exists(path)) {
				result = new MissingPathResult(path, imprint);
			} else {
				throw new UnsupportedOperationException("Unsupported path `%s` is neither a regular file or a directory.".formatted(path));
			}
			findListener().ifPresent(listener -> listener.onResult(result));
			return result;
		}), getExecutor());
	}

	/**
	 * Generates the fingerprint of a file's contents. Events are sent before and after fingerprint generation.
	 * @param file The file for which a fingerprint should be generated of the contents.
	 * @return The fingerprint of the file contents.
	 * @throws IOException if there is a problem reading the content.
	 * @see Listener#beforeGenerateFileContentFingerprint(Path)
	 * @see Listener#afterGenerateFileContentFingerprint(Path)
	 */
	Hash generateFileContentFingerprint(@Nonnull final Path file) throws IOException {
		findListener().ifPresent(listener -> listener.beforeGenerateFileContentFingerprint(file));
		final Hash fingerprint = FINGERPRINT_ALGORITHM.hash(file);
		findListener().ifPresent(listener -> listener.afterGenerateFileContentFingerprint(file));
		return fingerprint;
	}

	/**
	 * The result of checking a path against an imprint.
	 * @author Garret Wilson
	 */
	public sealed interface Result {

		/** @return The path being checked. */
		public Path getPath();

		/** @return The imprint against which the path is being checked. */
		public PathImprint getImprint();

		/** @return <code>true</code> if the path matched the imprint. */
		boolean isMatch();

	}

	/**
	 * Abstract implementation of a result.
	 * @author Garret Wilson
	 */
	protected sealed abstract class AbstractResult implements Result {

		private final Path path;

		@Override
		public Path getPath() {
			return path;
		}

		private final PathImprint imprint;

		@Override
		public PathImprint getImprint() {
			return imprint;
		}

		/**
		 * Constructor.
		 * @param path The path being checked.
		 * @param imprint The imprint against which the path is being checked.
		 */
		protected AbstractResult(@Nonnull final Path path, @Nonnull final PathImprint imprint) {
			this.path = requireNonNull(path);
			this.imprint = requireNonNull(imprint);
		}

	}

	/**
	 * A result of checking a path that does not exist.
	 * @author Garret Wilson
	 */
	public final class MissingPathResult extends AbstractResult {

		/**
		 * Constructor.
		 * @param path The path being checked.
		 * @param imprint The imprint against which the path is being checked.
		 */
		protected MissingPathResult(@Nonnull final Path path, @Nonnull final PathImprint imprint) {
			super(path, imprint);
		}

		@Override
		public boolean isMatch() {
			return false;
		}

	}

	/**
	 * Base implementation for file and directory results.
	 * @author Garret Wilson
	 */
	protected abstract sealed class BaseResult extends AbstractResult {

		private final boolean isFilenameMatch;

		/** @return Whether the filename of the imprint match exactly, with regard to case. */
		public boolean isFilenameMatch() {
			return isFilenameMatch;
		}

		private final FileTime contentModifiedAt;

		private final boolean isContentModifiedAtMatch;

		/** @return Whether the content modification timestamps match. */
		public boolean isContentModifiedAtMatch() {
			return isContentModifiedAtMatch;
		}

		/**
		 * Constructor.
		 * @param path The path being checked.
		 * @param imprint The imprint against which the path is being checked.
		 * @throws IOException if there is an error getting additional information about the path.
		 */
		protected BaseResult(@Nonnull final Path path, @Nonnull final PathImprint imprint) throws IOException {
			super(path, imprint);
			//TODO retrieve the actual filename name from the file; the one that was passed was generated
			this.isFilenameMatch = findFilename(path).equals(findFilename(imprint.path()));
			this.contentModifiedAt = getLastModifiedTime(path);
			this.isContentModifiedAtMatch = contentModifiedAt.equals(imprint.contentModifiedAt());
		}

	}

	/**
	 * The result of a file check.
	 * @author Garret Wilson
	 */
	public final class FileResult extends BaseResult {

		private final Hash contentFingerprint;

		/** @return The calculated fingerprint of the file. */
		public Hash getContentFingerprint() {
			return contentFingerprint;
		}

		private final boolean isContentFingerprintMatch;

		/** @return Whether the imprint content fingerprint matched that of the file. */
		public boolean isContentFingerprintMatch() {
			return isContentFingerprintMatch;
		}

		/**
		 * Constructor.
		 * @param file The file being checked.
		 * @param imprint The imprint against which the path is being checked.
		 * @throws IOException if there is an error getting additional information about the file.
		 */
		protected FileResult(@Nonnull final Path file, @Nonnull final PathImprint imprint) throws IOException {
			super(file, imprint);
			this.contentFingerprint = generateFileContentFingerprint(file);
			this.isContentFingerprintMatch = contentFingerprint.equals(imprint.contentFingerprint());
		}

		@Override
		public boolean isMatch() {
			return isFilenameMatch() && isContentModifiedAtMatch() && isContentFingerprintMatch();
		}

	}

	/**
	 * The result of a directory check.
	 * @author Garret Wilson
	 */
	public final class DirectoryResult extends BaseResult {

		/**
		 * Constructor.
		 * @param directory The directory being checked.
		 * @param imprint The imprint against which the path is being checked.
		 * @throws IOException if there is an error getting additional information about the directory.
		 */
		protected DirectoryResult(@Nonnull final Path directory, @Nonnull final PathImprint imprint) throws IOException {
			super(directory, imprint);
		}

		@Override
		public boolean isMatch() {
			//TODO check ignoreDirectoryModification flag
			return isFilenameMatch() && isContentModifiedAtMatch();
		}

	}

	/**
	 * Listens for events from the checker.
	 * <p>
	 * Implementations of this class <strong>must be thread safe</strong>, as the methods may be called concurrently.
	 * </p>
	 * @author Garret Wilson
	 */
	public interface Listener {

		/**
		 * Called when checking of a path against an imprint is being scheduled.
		 * @param path The path being checked.
		 * @param imprint The imprint against which the path is being checked.
		 */
		void onCheckPath(@Nonnull Path path, @Nonnull PathImprint imprint);

		/**
		 * Called immediately before fingerprint generation begins for the contents of a particular file.
		 * @param file The file the fingerprint of which is to be generated.
		 */
		void beforeGenerateFileContentFingerprint(@Nonnull Path file);

		/**
		 * Called immediately after fingerprint generation is completed for the contents of a particular file.
		 * @param file The file the fingerprint of which has been generated.
		 */
		void afterGenerateFileContentFingerprint(@Nonnull Path file);

		/**
		 * Called when a path has been checked and the result is ready.
		 * @param result The result of checking the path against an imprint.
		 */
		void onResult(@Nonnull Result result);

	}

	/**
	 * Builder for specification for creating a {@link PathChecker}.
	 * @author Garret Wilson
	 */
	public static class Builder {

		private Executor executor = null;

		/**
		 * Specifies the executor; if not set, a {@link #newDefaultExecutor()} will be created and used.
		 * @param executor The executor for checking imprints; may or may not be an instance of {@link ExecutorService}.
		 * @return This builder.
		 * @throws IllegalStateException if a executor-setting method is called twice on the builder.
		 */
		public Builder withExecutor(@Nonnull final Executor executor) {
			checkState(this.executor == null, "Executor already specified.");
			this.executor = requireNonNull(executor);
			return this;
		}

		/**
		 * Determines the executor to use based upon the current settings.
		 * @return The specified executor.
		 */
		private Executor determineExecutor() {
			if(executor != null) {
				return executor;
			}
			return newDefaultExecutor();
		}

		@Nullable
		private Listener listener = null;

		/** @return The configured listener, if any. */
		private Optional<Listener> findListener() {
			return Optional.ofNullable(listener);
		}

		/**
		 * Specifies the listener of events from this class.
		 * <p>
		 * The listener will be called immediately in the relevant thread. Thus the listener <em>must be thread safe</em> and should take as little time as
		 * possible.
		 * </p>
		 * @param listener The listener of events from the generator.
		 * @return This builder.
		 */
		public Builder withListener(@Nonnull final Listener listener) {
			this.listener = requireNonNull(listener);
			return this;
		}

		/** @return A new instance of the imprint checker based upon the current builder configuration. */
		public PathChecker build() {
			return new PathChecker(this);
		}

		/**
		 * Returns a default executor.
		 * @return A new default executor.
		 */
		public static Executor newDefaultExecutor() {
			return newFixedThreadPool(Runtime.getRuntime().availableProcessors());
		}

	}

}
