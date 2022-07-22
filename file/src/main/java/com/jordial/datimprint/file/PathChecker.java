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
import java.util.function.Consumer;

import javax.annotation.*;
import com.globalmentor.security.*;

import io.clogr.Clogged;

/**
 * Checks individual paths against their imprints to detect changes in content, filename case, and modification timestamp. The {@link #close()} method must be
 * called after the checker is finished being used to ensure that path checking and and production of results is complete and resources are cleaned up.
 * @implSpec By default symbolic links are followed.
 * @author Garret Wilson
 * @see PathImprintGenerator#FINGERPRINT_ALGORITHM
 */
public class PathChecker implements Closeable, Clogged {

	private final Executor checkExecutor;

	/**
	 * @return The executor for checking paths ; may or may not be an instance of {@link ExecutorService}, and may or may not be the same executor as
	 *         {@link #getProduceExecutor()}.
	 */
	protected Executor getCheckExecutor() {
		return checkExecutor;
	}

	private final Executor produceExecutor;

	/**
	 * @return The executor for producing results; may or may not be an instance of {@link ExecutorService}, and may or may not be the same executor as
	 *         {@link #getCheckExecutor()}.
	 */
	protected Executor getProduceExecutor() {
		return produceExecutor;
	}

	private final Optional<Consumer<Result>> foundResultConsumer;

	/** @return The consumer, if any, to which results will be produced after a path is checked. */
	protected Optional<Consumer<Result>> findResultConsumer() {
		return foundResultConsumer;
	}

	private final Optional<Listener> foundListener;

	/** @return The listener, if any, to events from this class. */
	protected Optional<Listener> findListener() {
		return foundListener;
	}

	/**
	 * No-args constructor.
	 * @see Builder#newDefaultCheckExecutor()
	 * @see Builder#newDefaultProduceExecutor()
	 */
	public PathChecker() {
		this.checkExecutor = Builder.newDefaultCheckExecutor();
		this.produceExecutor = Builder.newDefaultProduceExecutor();
		this.foundResultConsumer = Optional.empty();
		this.foundListener = Optional.empty();
	}

	/**
	 * Executor constructor.
	 * @param executor The executor for checking imprints; may or may not be an instance of {@link ExecutorService}.
	 */
	public PathChecker(@Nonnull final Executor executor) {
		this.checkExecutor = this.produceExecutor = requireNonNull(executor);
		this.foundResultConsumer = Optional.empty();
		this.foundListener = Optional.empty();
	}

	/**
	 * Builder constructor.
	 * @param builder The builder providing the specification for creating a new instance.
	 */
	protected PathChecker(@Nonnull final Builder builder) {
		this.checkExecutor = builder.determineCheckExecutor();
		this.produceExecutor = builder.determineProduceExecutor();
		this.foundResultConsumer = builder.findResultConsumer();
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
	 * @see #getCheckExecutor()
	 * @see #getProduceExecutor()
	 */
	@Override
	public void close() throws IOException {
		if(checkExecutor instanceof ExecutorService checkExecutorService) {
			checkExecutorService.shutdown();
			try {
				if(!checkExecutorService.awaitTermination(3, TimeUnit.MINUTES)) {
					checkExecutorService.shutdownNow();
				}
			} catch(final InterruptedException interruptedException) {
				checkExecutorService.shutdownNow();
				Thread.currentThread().interrupt();
			}
		}
		if(produceExecutor != checkExecutor && produceExecutor instanceof ExecutorService produceExecutorService) { //if we have separate executor services
			produceExecutorService.shutdown();
			try {
				//allow time to write the information
				if(!produceExecutorService.awaitTermination(30, TimeUnit.SECONDS)) {
					produceExecutorService.shutdownNow();
				}
			} catch(final InterruptedException interruptedException) {
				produceExecutorService.shutdownNow();
				Thread.currentThread().interrupt();
			}
		}
		try { //at this point both executor service should be shut down or requested to shut down now
			if(checkExecutor instanceof ExecutorService checkExecutorService) {
				if(!checkExecutorService.awaitTermination(5, TimeUnit.SECONDS)) {
					throw new IOException("Imprint generator service not shut down properly; imprint generation may be incomplete.");
				}
			}
			if(produceExecutor != checkExecutor && produceExecutor instanceof ExecutorService produceExecutorService) { //if we have separate executor services
				if(!produceExecutorService.awaitTermination(5, TimeUnit.SECONDS)) {
					throw new IOException("Imprint production service not shut down properly; all imprints may not have been written.");
				}
			}
		} catch(final InterruptedException interruptedException) {
			Thread.currentThread().interrupt();
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
			findListener().ifPresent(listener -> listener.beforeCheckPath(path));
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
			findListener().ifPresent(listener -> listener.afterCheckPath(path));
			findResultConsumer().ifPresent(resultConsumer -> getProduceExecutor().execute(() -> resultConsumer.accept(result)));
			return result;
		}), getCheckExecutor());
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
			this.contentFingerprint = FINGERPRINT_ALGORITHM.hash(file);
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
		 * Called immediately before checking a particular path.
		 * @param path The path being checked.
		 */
		void beforeCheckPath(@Nonnull Path path);

		/**
		 * Called immediately after checking a particular path.
		 * @param path The path being checked.
		 */
		void afterCheckPath(@Nonnull Path path);

	}

	/**
	 * Builder for specification for creating a {@link PathChecker}.
	 * @author Garret Wilson
	 */
	public static class Builder {

		private Executor checkExecutor = null;

		/**
		 * Specifies the check executor; if not set, a {@link #newDefaultCheckExecutor()} will be created and used.
		 * @param checkExecutor The executor for checking paths; may or may not be an instance of {@link ExecutorService}, and may or may not be the same executor
		 *          as {@link #withProduceExecutor(Executor)}.
		 * @return This builder.
		 * @throws IllegalStateException if a check executor-setting method is called twice on the builder.
		 */
		public Builder withCheckExecutor(@Nonnull final Executor checkExecutor) {
			checkState(this.checkExecutor == null, "Check executor already specified.");
			this.checkExecutor = requireNonNull(checkExecutor);
			return this;
		}

		/**
		 * Determines the check executor to use based upon the current settings.
		 * @return The specified check executor.
		 */
		private Executor determineCheckExecutor() {
			if(checkExecutor != null) {
				return checkExecutor;
			}
			return newDefaultCheckExecutor();
		}

		private Executor produceExecutor;

		/**
		 * Specifies the produce executor; if not set, a {@link #newDefaultProduceExecutor()} will be created and used.
		 * @param produceExecutor The executor for producing imprints; may or may not be an instance of {@link ExecutorService}, and may or may not be the same
		 *          executor as {@link #withCheckExecutor(Executor)}.
		 * @throws IllegalStateException if a produce executor-setting method is called twice on the builder.
		 * @return This builder.
		 */
		public Builder withProduceExecutor(@Nonnull final Executor produceExecutor) {
			checkState(this.produceExecutor == null, "Produce executor already specified.");
			this.produceExecutor = requireNonNull(produceExecutor);
			return this;
		}

		/**
		 * Determines the produce executor to use based upon the current settings.
		 * @return The specified produce executor.
		 */
		private Executor determineProduceExecutor() {
			return produceExecutor != null ? produceExecutor : newDefaultProduceExecutor();
		}

		/**
		 * Specifies a single executor to use as the check executor and as the produce executor.
		 * @apiNote This method is used primarily for testing; typically it is less efficient to have the same executor for checking and production.
		 * @param executor The executor for checking paths and producing results.
		 * @return This builder.
		 * @throws IllegalStateException if a check executor-setting method or a produce executor-setting method is called twice on the builder.
		 * @see #withCheckExecutor(Executor)
		 * @see #withProduceExecutor(Executor)
		 */
		public Builder withExecutor(@Nonnull final Executor executor) {
			checkState(this.checkExecutor == null, "Check executor already specified.");
			checkState(this.produceExecutor == null, "Produce executor already specified.");
			this.checkExecutor = requireNonNull(executor);
			this.produceExecutor = requireNonNull(executor);
			return this;
		}

		@Nullable
		private Consumer<Result> resultConsumer = null;

		/** @return The configured result consumer, if any. */
		private Optional<Consumer<Result>> findResultConsumer() {
			return Optional.ofNullable(resultConsumer);
		}

		/**
		 * Specifies the result consumer.
		 * @param resultConsumer The consumer to which results will be produced after a path is checked.
		 * @return This builder.
		 */
		public Builder withResultConsumer(@Nonnull final Consumer<Result> resultConsumer) {
			this.resultConsumer = requireNonNull(resultConsumer);
			return this;
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
		 * Returns a default executor for checking paths.
		 * @return A new default check executor.
		 */
		public static Executor newDefaultCheckExecutor() {
			return newFixedThreadPool(Runtime.getRuntime().availableProcessors());
		}

		/**
		 * Returns a default executor for production of results.
		 * @implSpec This implementation returns an executor using a single thread with normal priority.
		 * @return A new default produce executor.
		 */
		public static Executor newDefaultProduceExecutor() {
			return newSingleThreadExecutor();
		}

	}

}
