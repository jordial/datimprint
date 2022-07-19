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
		//TODO handle file not exist
		findListener().ifPresent(listener -> listener.onCheckPath(path, imprint));
		final CompletableFuture<FileTime> futureContentModifiedAt = CompletableFuture.supplyAsync(throwingSupplier(() -> getLastModifiedTime(path)));
		if(isRegularFile(path)) {
			final CompletableFuture<Hash> futureContentFingerprint = generateFileContentFingerprintAsync(path);
			return futureContentModifiedAt.thenCombine(futureContentFingerprint,
					(contentModifiedAt, contentFingerprint) -> new Result(path, contentModifiedAt, contentFingerprint, imprint));
		} else if(isDirectory(path)) { //nothing expensive to do for a directory; check it immediately
			return futureContentModifiedAt.thenApply(contentModifiedAt -> new Result(path, contentModifiedAt, null, imprint));
		} else {
			throw new UnsupportedOperationException("Unsupported path `%s` is neither a regular file or a directory.".formatted(path));
		}
	}

	/**
	 * Generates the fingerprint of a file's contents asynchronously.
	 * @implSpec This implementation uses the executor returned by {@link #getExecutor())}.
	 * @param file The file for which a fingerprint should be generated of the contents.
	 * @return A future fingerprint of the file contents.
	 * @throws IOException if there is a problem reading the content.
	 */
	CompletableFuture<Hash> generateFileContentFingerprintAsync(@Nonnull final Path file) throws IOException {
		return CompletableFuture.supplyAsync(throwingSupplier(() -> {
			findListener().ifPresent(listener -> listener.beforeGenerateFileContentFingerprint(file));
			final Hash fingerprint = FINGERPRINT_ALGORITHM.hash(file);
			findListener().ifPresent(listener -> listener.afterGenerateFileContentFingerprint(file));
			return fingerprint;
		}), getExecutor());
	}

	//TODO document
	public class Result {

		private final Path path;

		private final FileTime contentModifiedAt;

		@Nullable
		private final Hash contentHash;

		private final PathImprint imprint;

		private final boolean isFilenameMatch;

		private final boolean isContentModifiedAtMatch;

		private final boolean isContentFingerprintMatch;

		private final boolean isMatch;

		//TODO add getters

		/**
		 * Constructor.
		 * @param path The path being checked.
		 * @param contentModifiedAt The path's modification timestamp.
		 * @param contentFingerprint The hash of the contents (e.g. for files), or <code>null</code> if there is no content fingerprint available (e.g. for
		 *          directories).
		 * @param imprint The imprint against which the path is being checked.
		 */
		private Result(@Nonnull final Path path, @Nonnull final FileTime contentModifiedAt, @Nullable final Hash contentFingerprint,
				@Nonnull final PathImprint imprint) {
			this.path = requireNonNull(path);
			this.contentModifiedAt = requireNonNull(contentModifiedAt);
			this.contentHash = contentFingerprint;
			this.imprint = requireNonNull(imprint);
			this.isFilenameMatch = findFilename(path).equals(findFilename(imprint.path()));
			//TODO check ignoreDirectoryModification flag
			this.isContentModifiedAtMatch = contentModifiedAt.equals(imprint.contentModifiedAt());
			this.isContentFingerprintMatch = contentFingerprint != null ? contentFingerprint.equals(imprint.fingerprint()) : true;
			this.isMatch = isFilenameMatch && isContentModifiedAtMatch && isContentFingerprintMatch;
		}

	}

	/**
	 * Holder of two fingerprints calculated from directory children.
	 * @param contentFingerprint The fingerprint of the child content fingerprints of the directory.
	 * @param childrenFingerprint The fingerprint of the the child fingerprints of the directory. Note that am empty directory is still expected to have a
	 *          children fingerprint.
	 * @author Garret Wilson
	 */
	record DirectoryContentChildrenFingerprints(@Nonnull Hash contentFingerprint, @Nonnull Hash childrenFingerprint) {

		/**
		 * Constructor for argument validation.
		 * @param contentFingerprint The fingerprint of the child content fingerprints of the directory.
		 * @param childrenFingerprint The fingerprint of the the child fingerprints of the directory.
		 */
		public DirectoryContentChildrenFingerprints {
			requireNonNull(contentFingerprint);
			requireNonNull(childrenFingerprint);
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
