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
import static java.nio.file.Files.*;
import static java.util.Comparator.*;
import static java.util.Objects.*;
import static java.util.concurrent.Executors.*;
import static java.util.function.Function.*;
import static java.util.stream.Collectors.*;
import static org.zalando.fauxpas.FauxPas.*;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.Stream;

import javax.annotation.*;

import com.globalmentor.security.*;

import io.clogr.Clogged;

/**
 * Data imprinting file system implementation. The {@link #close()} method must be called after the imprint generator is finished being used to ensure that
 * generation and especially production of imprints is complete and resources are cleaned up.
 * @apiNote Generally method with names beginning with <code>generate…()</code> only generate information and do not pass it to the consumer, while methods with
 *          names beginning with <code>produce…</code> will involve generating information and producing it to the consumer, although some methods (notably
 *          {@link #generateDirectoryContentChildrenFingerprintsAsync(Path)}) inherently involves producing child imprints.
 * @apiNote The word "contents" is used when referring to what a file or directory contains, while "content" is used as an adjective, such as "content
 *          fingerprint".
 * @implSpec By default symbolic links are followed.
 * @author Garret Wilson
 */
public class PathImprintGenerator implements Closeable, Clogged {

	/**
	 * The algorithm for calculating fingerprints.
	 * @apiNote This algorithm should be set to an algorithm known to be implemented on all supported Java versions.
	 */
	public static final MessageDigests.Algorithm FINGERPRINT_ALGORITHM = MessageDigests.SHA_256;

	private final Executor generateExecutor;

	/**
	 * @return The executor for traversing and generating imprints; may or may not be an instance of {@link ExecutorService}, and may or may not be the same
	 *         executor as {@link #getProduceExecutor()}.
	 */
	protected Executor getGenerateExecutor() {
		return generateExecutor;
	}

	private final Executor produceExecutor;

	/**
	 * @return The executor for producing imprints; may or may not be an instance of {@link ExecutorService}, and may or may not be the same executor as
	 *         {@link #getGenerateExecutor()}.
	 */
	protected Executor getProduceExecutor() {
		return produceExecutor;
	}

	private final Optional<Consumer<PathImprint>> foundImprintConsumer;

	/** @return The consumer, if any, to which imprints will be produced after being generated. */
	protected Optional<Consumer<PathImprint>> findImprintConsumer() {
		return foundImprintConsumer;
	}

	private final Optional<Listener> foundListener;

	/** @return The listener, if any, to events from this class. */
	protected Optional<Listener> findListener() {
		return foundListener;
	}

	/**
	 * No-args constructor.
	 * @implSpec Traversal and imprint generation uses a thread pool that by default has the same number of threads as the number of available processors.
	 * @implSpec Production of imprints is performed in a separate thread with maximum priority, as we want the consumer to always have priority so that imprints
	 *           can be discarded as quickly as possible, lowering the memory overhead.
	 * @see Builder#newDefaultGenerateExecutor()
	 * @see Builder#newDefaultProduceExecutor()
	 */
	public PathImprintGenerator() {
		this(Builder.newDefaultGenerateExecutor(), Builder.newDefaultProduceExecutor());
	}

	/**
	 * Imprint consumer constructor.
	 * @implSpec Traversal and imprint generation uses a thread pool that by default has the same number of threads as the number of available processors.
	 * @implSpec Production of imprints is performed in a separate thread with maximum priority, as we want the consumer to always have priority so that imprints
	 *           can be discarded as quickly as possible, lowering the memory overhead.
	 * @param imprintConsumer The consumer to which imprints will be produced after being generated.
	 * @see Builder#newDefaultGenerateExecutor()
	 * @see Builder#newDefaultProduceExecutor()
	 */
	public PathImprintGenerator(@Nonnull final Consumer<PathImprint> imprintConsumer) {
		this(Builder.newDefaultGenerateExecutor(), Builder.newDefaultProduceExecutor(), imprintConsumer);
	}

	/**
	 * Same-executor constructor with no consumer.
	 * @param executor The executor for traversing and generating imprints; and producing imprints. May or may not be an instance of {@link ExecutorService}.
	 */
	public PathImprintGenerator(@Nonnull final Executor executor) {
		this(executor, executor);
	}

	/**
	 * Executors constructor with no consumer.
	 * @param generateExecutor The executor for traversing and generating imprints; may or may not be an instance of {@link ExecutorService}, and may or may not
	 *          be the same executor as the produce executor.
	 * @param produceExecutor The executor for producing imprints; may or may not be an instance of {@link ExecutorService}, and may or may not be the same
	 *          executor as the generate executor.
	 */
	public PathImprintGenerator(@Nonnull final Executor generateExecutor, @Nonnull final Executor produceExecutor) {
		this.generateExecutor = requireNonNull(generateExecutor);
		this.produceExecutor = requireNonNull(produceExecutor);
		this.foundImprintConsumer = Optional.empty();
		this.foundListener = Optional.empty();
	}

	/**
	 * Executors and imprint consumer constructor.
	 * @param generateExecutor The executor for traversing and generating imprints; may or may not be an instance of {@link ExecutorService}, and may or may not
	 *          be the same executor as the produce executor.
	 * @param produceExecutor The executor for producing imprints; may or may not be an instance of {@link ExecutorService}, and may or may not be the same
	 *          executor as the generate executor.
	 * @param imprintConsumer The consumer to which imprints will be produced after being generated.
	 */
	public PathImprintGenerator(@Nonnull final Executor generateExecutor, @Nonnull final Executor produceExecutor,
			@Nonnull final Consumer<PathImprint> imprintConsumer) {
		this.generateExecutor = requireNonNull(generateExecutor);
		this.produceExecutor = requireNonNull(produceExecutor);
		this.foundImprintConsumer = Optional.of(imprintConsumer);
		this.foundListener = Optional.empty();
	}

	/**
	 * Builder constructor.
	 * @param builder The builder providing the specification for creating a new instance.
	 */
	protected PathImprintGenerator(@Nonnull final Builder builder) {
		this.generateExecutor = builder.determineGenerateExecutor();
		this.produceExecutor = builder.determineProduceExecutor();
		this.foundImprintConsumer = builder.findImprintConsumer();
		this.foundListener = builder.findListener();
	}

	/** @return A new builder for specifying a new {@link PathImprintGenerator}. */
	public static PathImprintGenerator.Builder builder() {
		return new Builder();
	}

	/**
	 * {@inheritDoc}
	 * @implSpec This implementation shuts down each executor if it is an instance of {@link ExecutorService}.
	 * @throws IOException If one of the executor services could not be shut down.
	 * @see #getGenerateExecutor()
	 * @see #getProduceExecutor()
	 */
	@Override
	public void close() throws IOException {
		if(generateExecutor instanceof ExecutorService generateExecutorService) {
			generateExecutorService.shutdown();
			try {
				//generation has probably completed anyway at this point, as most methods eventually block to use the results of generation in the final imprint
				if(!generateExecutorService.awaitTermination(1, TimeUnit.MINUTES)) {
					generateExecutorService.shutdownNow();
				}
			} catch(final InterruptedException interruptedException) {
				generateExecutorService.shutdownNow();
				Thread.currentThread().interrupt();
			}
		}
		if(produceExecutor != generateExecutor && produceExecutor instanceof ExecutorService produceExecutorService) { //if we have separate executor services
			produceExecutorService.shutdown();
			try {
				//allow time to write the information
				if(!produceExecutorService.awaitTermination(5, TimeUnit.MINUTES)) {
					produceExecutorService.shutdownNow();
				}
			} catch(final InterruptedException interruptedException) {
				produceExecutorService.shutdownNow();
				Thread.currentThread().interrupt();
			}
		}
		try { //at this point both executor service should be shut down or requested to shut down now
			if(generateExecutor instanceof ExecutorService generateExecutorService) {
				if(!generateExecutorService.awaitTermination(5, TimeUnit.SECONDS)) {
					throw new IOException("Imprint generator service not shut down properly; imprint generation may be incomplete.");
				}
			}
			if(produceExecutor != generateExecutor && produceExecutor instanceof ExecutorService produceExecutorService) { //if we have separate executor services
				if(!produceExecutorService.awaitTermination(5, TimeUnit.SECONDS)) {
					throw new IOException("Imprint production service not shut down properly; all imprints may not have been written.");
				}
			}
		} catch(final InterruptedException interruptedException) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Generates an imprint of a single path, which must be a regular file or a directory, and then produces it to the imprint consumer.
	 * @implSpec This implementation delegates to {@link #produceImprint(Path)} to produce the imprint asynchronously and blocks until the imprint is ready.
	 * @implNote This method involves asynchronous recursion to all the descendants of the directory.
	 * @param path The path for which an imprint should be produced.
	 * @return An imprint of the path.
	 * @throws IOException if there is a problem accessing the file system.
	 * @see #findImprintConsumer()
	 * @see #getProduceExecutor()
	 * @throws CompletionException if there was an error completing asynchronous generation and production.
	 */
	public PathImprint produceImprint(@Nonnull final Path path) throws IOException { //TODO consider removing this method to simplify the API
		return produceImprintAsync(path).join();
	}

	/**
	 * Asynchronously generates an imprint of a single path, which must be a regular file or a directory, and then produces it to the imprint consumer, if there
	 * is one.
	 * @implSpec This implementation delegates to {@link #generateImprintAsync(Path)}.
	 * @implNote This method involves asynchronous recursion to all the descendants of the directory.
	 * @param path The path for which an imprint should be produced.
	 * @return A future imprint of the path.
	 * @throws IOException if there is a problem accessing the file system.
	 * @see #findImprintConsumer()
	 * @see #getProduceExecutor()
	 */
	public CompletableFuture<PathImprint> produceImprintAsync(@Nonnull final Path path) throws IOException {
		final CompletableFuture<PathImprint> futureGeneratedImprint = generateImprintAsync(path);
		//only schedule producing the imprint in the future if we have an imprint consumer
		return findImprintConsumer().map(imprintConsumer -> futureGeneratedImprint.thenApply(imprint -> {
			getProduceExecutor().execute(() -> imprintConsumer.accept(imprint));
			return imprint;
		})).orElse(futureGeneratedImprint); //otherwise the future generated imprint is all we need
	}

	/**
	 * Asynchronously generates an imprint of a single path, which must be a regular file or a directory. Any descendant imprints will be produced, but the path
	 * itself will not be produced.
	 * @apiNote This method involves asynchronous recursion to all the descendants of the directory.
	 * @param path The path for which an imprint should be generated.
	 * @return A future imprint of the path.
	 * @throws IOException if there is a problem accessing the file system.
	 */
	public CompletableFuture<PathImprint> generateImprintAsync(@Nonnull final Path path) throws IOException {
		getLogger().trace("Generating imprint for path `{}`.", path);
		//TODO handle file not exist
		findListener().ifPresent(listener -> listener.onGenerateImprint(path));
		final FileTime contentModifiedAt = getLastModifiedTime(path);
		//TODO  final CompletableFuture<FileTime> futureContentModifiedAt = CompletableFuture.supplyAsync(throwingSupplier(()->getLastModifiedTime(path)));
		if(isRegularFile(path)) {
			final CompletableFuture<Hash> futureContentFingerprint = generateFileContentFingerprintAsync(path);
			return futureContentFingerprint
					.thenApply(throwingFunction(contentFingerprint -> PathImprint.forFile(path, contentModifiedAt, contentFingerprint, FINGERPRINT_ALGORITHM)));
		} else if(isDirectory(path)) {
			final CompletableFuture<DirectoryContentChildrenFingerprints> futureContentChildrenFingerprints = generateDirectoryContentChildrenFingerprintsAsync(path);
			return futureContentChildrenFingerprints.thenApply(throwingFunction(contentChildrenFingerprints -> PathImprint.forDirectory(path, contentModifiedAt,
					contentChildrenFingerprints.contentFingerprint(), contentChildrenFingerprints.childrenFingerprint(), FINGERPRINT_ALGORITHM)));
		} else {
			throw new UnsupportedOperationException("Unsupported path `%s` is neither a regular file or a directory.".formatted(path));
		}
	}

	/**
	 * Asynchronously generates imprints for all immediate children of a directory.
	 * @apiNote Perhaps a more modularized approach would be to separate generation from production. However generation of each direct child must include
	 *          production of the second level and below via the ultimate calls to {@link #generateDirectoryContentChildrenFingerprintsAsync(Path)} (otherwise the
	 *          caller would have no way to produce the imprint of subsequent levels). It is more consistent to schedule production of the immediate children
	 *          imprints as well. Moreover this approach might gain a tiny efficiency: production of direct child imprints can be scheduled before waiting for the
	 *          entire directory listing to complete, and as production occurs in a separate thread, this could theoretically complete generation and production
	 *          of children more quickly, freeing resources for other children without needing to wait until the directory listing is finished.
	 * @implSpec This implementation uses the executor returned by {@link #getGenerateExecutor()} for traversal.
	 * @implSpec This implementation delegates to {@link #produceImprintAsync(Path)} to produce each child imprint.
	 * @implNote This method involves asynchronous recursion to all the descendants of the directory.
	 * @param directory The path for which an imprint should be produced.
	 * @return A map of all future imprints for each child mapped to the path of each child.
	 * @throws IOException if there is a problem traversing the directory or reading file contents.
	 */
	CompletableFuture<Map<Path, CompletableFuture<PathImprint>>> produceChildImprintsAsync(@Nonnull final Path directory) throws IOException {
		return CompletableFuture.supplyAsync(throwingSupplier(() -> {
			findListener().ifPresent(listener -> listener.onEnterDirectory(directory));
			try (final Stream<Path> childPaths = list(directory)) {
				return childPaths.collect(toUnmodifiableMap(identity(), throwingFunction(this::produceImprintAsync)));
			}
		}), getGenerateExecutor());
	}

	/**
	 * Generates the fingerprint of a file's contents asynchronously. Events are sent before and after fingerprint generation.
	 * @implSpec This implementation uses the executor returned by {@link #getGenerateExecutor()}.
	 * @param file The file for which a fingerprint should be generated of the contents.
	 * @return A future fingerprint of the file contents.
	 * @throws IOException if there is a problem reading the content.
	 * @see Listener#beforeGenerateFileContentFingerprint(Path)
	 * @see Listener#afterGenerateFileContentFingerprint(Path)
	 */
	CompletableFuture<Hash> generateFileContentFingerprintAsync(@Nonnull final Path file) throws IOException {
		return CompletableFuture.supplyAsync(throwingSupplier(() -> {
			findListener().ifPresent(listener -> listener.beforeGenerateFileContentFingerprint(file));
			final Hash fingerprint = FINGERPRINT_ALGORITHM.hash(file);
			findListener().ifPresent(listener -> listener.afterGenerateFileContentFingerprint(file));
			return fingerprint;
		}), getGenerateExecutor());
	}

	/**
	 * Generates fingerprints of a directory's child contents and children asynchronously.
	 * @apiNote Because each child directory imprint depends on the fingerprint of its children, this method ultimately includes recursive traversal of all
	 *          descendants.
	 * @apiNote This method inherently involves production of child imprints during generation of the directory contents fingerprint.
	 * @implSpec This method generates child imprints by delegating to {@link #produceChildImprintsAsync(Path)}.
	 * @implSpec This implementation uses the executor returned by {@link #getGenerateExecutor()}.
	 * @implNote This method involves asynchronous recursion to all the descendants of the directory.
	 * @param directory The directory for which a fingerprint should be generated of the children.
	 * @return A future fingerprint of the directory content fingerprints and children fingerprints.
	 * @throws IOException if there is a problem traversing the directory or reading file contents.
	 */
	CompletableFuture<DirectoryContentChildrenFingerprints> generateDirectoryContentChildrenFingerprintsAsync(@Nonnull final Path directory) throws IOException {
		return produceChildImprintsAsync(directory) //**important** --- join the child values asynchronously to prevent a deadlock in a chain when threads are exhausted
				.thenCompose(childImprintFuturesByPath -> {
					final CompletableFuture<?>[] childImprintFutures = childImprintFuturesByPath.values().toArray(CompletableFuture[]::new);
					//wait for all child futures to finish, and then hash their fingerprints in deterministic order
					return CompletableFuture.allOf(childImprintFutures).thenApply(__ -> {
						final MessageDigest contentFingerprintMessageDigest = FINGERPRINT_ALGORITHM.newMessageDigest();
						final MessageDigest childrenFingerprintMessageDigest = FINGERPRINT_ALGORITHM.newMessageDigest();
						//sort children to ensure deterministic hashing, but we only need to sort by filename as all children are in the same directory
						childImprintFuturesByPath.entrySet().stream().sorted(comparing(Map.Entry::getKey, filenameComparator())).map(Map.Entry::getValue)
								.map(CompletableFuture::join).forEach(childImprint -> {
									childImprint.contentFingerprint().updateMessageDigest(contentFingerprintMessageDigest);
									childImprint.fingerprint().updateMessageDigest(childrenFingerprintMessageDigest);
								});
						return new DirectoryContentChildrenFingerprints(Hash.fromDigest(contentFingerprintMessageDigest),
								Hash.fromDigest(childrenFingerprintMessageDigest));
					});
				});
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
	 * Listens for events from the generator.
	 * <p>
	 * Implementations of this class <strong>must be thread safe</strong>, as the methods may be called concurrently.
	 * </p>
	 * @author Garret Wilson
	 */
	public interface Listener {

		/**
		 * Called when generation of an imprint is being scheduled for a path.
		 * @param path The path for which the imprint is being generated.
		 */
		void onGenerateImprint(@Nonnull Path path);

		/**
		 * Called when traversal enters a directory, before any directory listing or generation takes place in the directory.
		 * @param directory The directory entered.
		 */
		void onEnterDirectory(@Nonnull Path directory);

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
	 * Builder for specification for creating a {@link PathImprintGenerator}.
	 * @author Garret Wilson
	 */
	public static class Builder {

		/** Description of type of executor to use. */
		public enum ExecutorType {
			/**
			 * Indicates using a fixed thread pool.
			 * @see Executors#newFixedThreadPool(int)
			 */
			fixedthread,
			/**
			 * Indicates using a cached thread pool.
			 * @see Executors#newCachedThreadPool()
			 */
			cachedthread,
			/**
			 * Indicates using a FIFO fork/join pool.
			 * @see ForkJoinPool
			 * @see Executors#newWorkStealingPool()
			 */
			forkjoinfifo,
			/**
			 * Indicates using a LIFO fork/join pool.
			 * @see ForkJoinPool
			 */
			forkjoinlifo
		}

		private Executor generateExecutor = null;

		private ExecutorType generateExecutorType = null;

		/**
		 * Specifies the generate executor; if not set, a {@link #newDefaultGenerateExecutor()} will be created and used.
		 * @param generateExecutor The executor for traversing and generating imprints; may or may not be an instance of {@link ExecutorService}, and may or may not
		 *          be the same executor as {@link #withProduceExecutor(Executor)}.
		 * @return This builder.
		 * @throws IllegalStateException if a generate executor-setting method is called twice on the builder.
		 */
		public Builder withGenerateExecutor(@Nonnull final Executor generateExecutor) {
			checkState(this.generateExecutor == null && this.generateExecutorType == null, "Generate executor already specified.");
			this.generateExecutor = requireNonNull(generateExecutor);
			return this;
		}

		/**
		 * Specifies the generate executor; if not set, a {@link #newDefaultGenerateExecutor()} will be created and used.
		 * @param generateExecutorType The predefined type of executor for traversing and generating imprints.
		 * @return This builder.
		 * @throws IllegalStateException if a generate executor-setting method is called twice on the builder.
		 */
		public Builder withGenerateExecutorType(@Nonnull final ExecutorType generateExecutorType) {
			checkState(this.generateExecutor == null && this.generateExecutorType == null, "Generate executor already specified.");
			this.generateExecutorType = requireNonNull(generateExecutorType);
			return this;
		}

		/**
		 * Determines the generate executor to use based upon the current settings.
		 * @return The specified generate executor.
		 */
		private Executor determineGenerateExecutor() {
			if(generateExecutor != null) {
				return generateExecutor;
			}
			if(generateExecutorType != null) { //TODO use null case when available; see https://stackoverflow.com/q/72596788
				return switch(generateExecutorType) {
					case fixedthread -> newFixedThreadPool(Runtime.getRuntime().availableProcessors());
					case cachedthread -> newCachedThreadPool();
					case forkjoinfifo -> new ForkJoinPool(1, ForkJoinPool.defaultForkJoinWorkerThreadFactory, null, true);
					case forkjoinlifo -> new ForkJoinPool(1, ForkJoinPool.defaultForkJoinWorkerThreadFactory, null, false);
				};
			}
			return newDefaultGenerateExecutor();
		}

		private Executor produceExecutor;

		/**
		 * Specifies the produce executor; if not set, a {@link #newDefaultProduceExecutor()} will be created and used.
		 * @param produceExecutor The executor for producing imprints; may or may not be an instance of {@link ExecutorService}, and may or may not be the same
		 *          executor as {@link #withGenerateExecutor(Executor)}.
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
		 * Specifies a single executor to use as the generate executor and as the produce executor.
		 * @apiNote This method is used primarily for testing; typically it is less efficient to have the same executor for generation and production.
		 * @param executor The executor for traversing, generating, and producing imprints.
		 * @return This builder.
		 * @throws IllegalStateException if a generate executor-setting method or a produce executor-setting method is called twice on the builder.
		 * @see #withGenerateExecutor(Executor)
		 * @see #withProduceExecutor(Executor)
		 */
		public Builder withExecutor(@Nonnull final Executor executor) {
			checkState(this.generateExecutor == null && this.generateExecutorType == null, "Generate executor already specified.");
			checkState(this.produceExecutor == null, "Produce executor already specified.");
			this.generateExecutor = requireNonNull(executor);
			this.produceExecutor = requireNonNull(executor);
			return this;
		}

		@Nullable
		private Consumer<PathImprint> imprintConsumer = null;

		/** @return The configured imprint consumer, if any. */
		private Optional<Consumer<PathImprint>> findImprintConsumer() {
			return Optional.ofNullable(imprintConsumer);
		}

		/**
		 * Specifies the imprint consumer.
		 * @param imprintConsumer The consumer to which imprints will be produced after being generated.
		 * @return This builder.
		 */
		public Builder withImprintConsumer(@Nonnull final Consumer<PathImprint> imprintConsumer) {
			this.imprintConsumer = requireNonNull(imprintConsumer);
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

		/** @return A new instance of the imprint generator based upon the current builder configuration. */
		public PathImprintGenerator build() {
			return new PathImprintGenerator(this);
		}

		/**
		 * Returns a default executor for traversal and imprint generation.
		 * @return A new default generate executor.
		 */
		public static Executor newDefaultGenerateExecutor() {
			return newFixedThreadPool(Runtime.getRuntime().availableProcessors());
		}

		/**
		 * Returns a default executor for production of imprints.
		 * @implSpec This implementation returns an executor using a single thread with maximum priority, as we want the consumer to always have priority so that
		 *           imprints can be discarded as quickly as possible, lowering the memory overhead.
		 * @return A new default produce executor.
		 */
		public static Executor newDefaultProduceExecutor() {
			return newSingleThreadExecutor(runnable -> {
				final Thread thread = Executors.defaultThreadFactory().newThread(runnable);
				thread.setPriority(Thread.MAX_PRIORITY);
				return thread;
			});
		}
	}

}
