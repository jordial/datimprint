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
import static com.globalmentor.java.Longs.*;
import static java.nio.file.Files.*;
import static java.util.Objects.*;
import static java.util.concurrent.Executors.*;
import static java.util.function.Function.*;
import static java.util.stream.Collectors.*;
import static org.zalando.fauxpas.FauxPas.*;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.Stream;

import javax.annotation.*;

import com.globalmentor.io.Paths;
import com.globalmentor.security.*;

import io.clogr.Clogged;

/**
 * Data imprinting file system implementation. The {@link #close()} method must be called after the datimprinter is finished being used to ensure that
 * generation and especially production of imprints is complete and resources are cleaned up.
 * @apiNote Generally method with names beginning with <code>generate…()</code> only generate information and do not pass it to the consumer, while methods with
 *          names beginning with <code>produce…</code> will involve generating information and producing it to the consumer, although some methods (notably
 *          {@link #generateDirectoryContentsFingerprintAsync(Path)}) inherently involves producing child imprints.
 * @implSpec By default symbolic links are followed.
 * @author Garret Wilson
 */
public class FileSystemDatimprinter implements Closeable, Clogged {

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

	private final Consumer<PathImprint> imprintConsumer;

	/** @return The consumer to which imprints will be produced after being generated. */
	protected Consumer<PathImprint> getImprintConsumer() {
		return imprintConsumer;
	}

	/**
	 * No-args constructor.
	 * @implSpec Traversal and imprint generation uses a thread pool that by default has the same number of threads as the number of available processors.
	 * @implSpec Production of imprints is performed in a separate thread with maximum priority, as we want the consumer to always have priority so that imprints
	 *           can be discarded as quickly as possible, lowering the memory overhead.
	 */
	public FileSystemDatimprinter() {
		this((Consumer<PathImprint>)__ -> {}); //ignore produced imprints
	}

	/**
	 * Imprint consumer constructor.
	 * @implSpec Traversal and imprint generation uses a thread pool that by default has the same number of threads as the number of available processors.
	 * @implSpec Production of imprints is performed in a separate thread with maximum priority, as we want the consumer to always have priority so that imprints
	 *           can be discarded as quickly as possible, lowering the memory overhead.
	 * @param imprintConsumer The consumer to which imprints will be produced after being generated.
	 */
	public FileSystemDatimprinter(@Nonnull final Consumer<PathImprint> imprintConsumer) {
		this(newFixedThreadPool(Runtime.getRuntime().availableProcessors()), newSingleThreadExecutor(runnable -> {
			final Thread thread = Executors.defaultThreadFactory().newThread(runnable);
			thread.setPriority(Thread.MAX_PRIORITY);
			return thread;
		}), imprintConsumer);
	}

	/**
	 * Same-executor constructor with no consumer.
	 * @param executor The executor for traversing and generating imprints; and producing imprints. May or may not be an instance of {@link ExecutorService}.
	 */
	public FileSystemDatimprinter(@Nonnull final Executor executor) {
		this(executor, executor); //ignore produced imprints
	}

	/**
	 * Executors constructor with no consumer.
	 * @param generateExecutor The executor for traversing and generating imprints; may or may not be an instance of {@link ExecutorService}, and may or may not
	 *          be the same executor as the produce executor.
	 * @param produceExecutor The executor for producing imprints; may or may not be an instance of {@link ExecutorService}, and may or may not be the same
	 *          executor as the generate executor.
	 */
	public FileSystemDatimprinter(@Nonnull final Executor generateExecutor, @Nonnull final Executor produceExecutor) {
		this(generateExecutor, produceExecutor, __ -> {}); //ignore produced imprints
	}

	/**
	 * Executors and imprint consumer constructor.
	 * @param generateExecutor The executor for traversing and generating imprints; may or may not be an instance of {@link ExecutorService}, and may or may not
	 *          be the same executor as the produce executor.
	 * @param produceExecutor The executor for producing imprints; may or may not be an instance of {@link ExecutorService}, and may or may not be the same
	 *          executor as the generate executor.
	 * @param imprintConsumer The consumer to which imprints will be produced after being generated.
	 */
	public FileSystemDatimprinter(@Nonnull final Executor generateExecutor, @Nonnull final Executor produceExecutor,
			@Nonnull final Consumer<PathImprint> imprintConsumer) {
		this.generateExecutor = requireNonNull(generateExecutor);
		this.produceExecutor = requireNonNull(produceExecutor);
		this.imprintConsumer = requireNonNull(imprintConsumer);
	}

	/**
	 * Builder constructor.
	 * @param builder The builder providing the specification for creating a new instance.
	 */
	protected FileSystemDatimprinter(@Nonnull final Builder builder) {
		this.generateExecutor = builder.determineGenerateExecutor();
		this.produceExecutor = builder.determineProduceExecutor();
		this.imprintConsumer = builder.imprintConsumer;
	}

	/** @return A new builder for specifying a new {@link FileSystemDatimprinter}. */
	public static FileSystemDatimprinter.Builder builder() {
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
	 * @see #getImprintConsumer()
	 * @see #getProduceExecutor()
	 * @throws CompletionException if there was an error completing asynchronous generation and production.
	 */
	public PathImprint produceImprint(@Nonnull final Path path) throws IOException {
		return produceImprintAsync(path).join();
	}

	/**
	 * Generates an imprint of a single path given the pre-generated hash of the path contents.
	 * @param path The path for which an imprint should be generated.
	 * @param fileAttributesSupplier The source for supplying the file attributes of the path.
	 * @param contentFingerprint The fingerprint of the contents of a file, or of the child fingerprints of a directory.
	 * @return An imprint of the path.
	 * @throws IOException if there is a problem accessing the path in the file system.
	 */
	PathImprint generateImprint(@Nonnull final Path path, @Nonnull final Supplier<BasicFileAttributes> fileAttributesSupplier,
			@Nonnull final Hash contentFingerprint) throws IOException {
		final Hash filenameFingerprint = FINGERPRINT_ALGORITHM
				.hash(Paths.findFilename(path).orElseThrow(() -> new IllegalArgumentException("Path `%s` has no filename.".formatted(path))));
		final Instant modifiedAt = fileAttributesSupplier.get().lastModifiedTime().toInstant();
		return new PathImprint(path, filenameFingerprint, modifiedAt, contentFingerprint, generateFingerprint(filenameFingerprint, modifiedAt, contentFingerprint));
	}

	/**
	 * Asynchronously generates an imprint of a single path, which must be a regular file or a directory, and then produces it to the imprint consumer.
	 * @implSpec This implementation delegates to {@link #generateImprintAsync(Path)}.
	 * @implNote This method involves asynchronous recursion to all the descendants of the directory.
	 * @param path The path for which an imprint should be produced.
	 * @return A future imprint of the path.
	 * @throws IOException if there is a problem accessing the file system.
	 * @see #getImprintConsumer()
	 * @see #getProduceExecutor()
	 */
	public CompletableFuture<PathImprint> produceImprintAsync(@Nonnull final Path path) throws IOException {
		return generateImprintAsync(path).thenApplyAsync(imprint -> {
			getImprintConsumer().accept(imprint);
			return imprint;
		}, getProduceExecutor());
	}

	/**
	 * Asynchronously generates an imprint of a single path, which must be a regular file or a directory.
	 * @implNote This method involves asynchronous recursion to all the descendants of the directory.
	 * @param path The path for which an imprint should be generated.
	 * @return A future imprint of the path.
	 * @throws IOException if there is a problem accessing the file system.
	 */
	public CompletableFuture<PathImprint> generateImprintAsync(@Nonnull final Path path) throws IOException {
		getLogger().trace("Generating imprint for path `{}`.", path);
		final CompletableFuture<Hash> futureContentHash;
		if(isRegularFile(path)) {
			futureContentHash = generateFileContentsFingerprintAsync(path, throwingSupplier(() -> Files.newInputStream(path)));
		} else if(isDirectory(path)) {
			futureContentHash = generateDirectoryContentsFingerprintAsync(path);
		} else {
			throw new UnsupportedOperationException("Unsupported path `%s` is neither a regular file or a directory.".formatted(path));
		}
		return futureContentHash
				.thenApply(throwingFunction(contentFingerprint -> generateImprint(path, throwingSupplier(() -> readAttributes(path, BasicFileAttributes.class)), //TODO consolidate attribute supplier
						contentFingerprint)));
	}

	/**
	 * Asynchronously generates imprints for all immediate children of a directory.
	 * @implSpec This implementation uses the executor returned by {@link #getGenerateExecutor()} for traversal.
	 * @implSpec This implementation delegates to {@link #produceImprintAsync(Path)} to produce each child imprint.
	 * @implNote This method involves asynchronous recursion to all the descendants of the directory.
	 * @param directory The path for which an imprint should be produced.
	 * @param fileAttributesSupplier The source for supplying the file attributes of the path.
	 * @return A map of all future imprints for each child mapped to the path of each child.
	 * @throws IOException if there is a problem traversing the directory or reading file contents.
	 */
	CompletableFuture<Map<Path, CompletableFuture<PathImprint>>> produceChildImprintsAsync(@Nonnull final Path directory,
			@Nonnull final Supplier<BasicFileAttributes> fileAttributesSupplier) throws IOException {
		return CompletableFuture.supplyAsync(throwingSupplier(() -> {
			try (final Stream<Path> childPaths = Files.list(directory)) {
				return childPaths.collect(toUnmodifiableMap(identity(), throwingFunction(this::produceImprintAsync)));
			}
		}), getGenerateExecutor());
	}

	/**
	 * Generates the fingerprint of a file's contents asynchronously.
	 * @implSpec This implementation uses the executor returned by {@link #getGenerateExecutor()}.
	 * @param file The file for which a fingerprint should be generated of the contents.
	 * @param contentSupplier The source for supplying the content of the file.
	 * @return A future fingerprint of the file contents.
	 * @throws IOException if there is a problem reading the content.
	 */
	CompletableFuture<Hash> generateFileContentsFingerprintAsync(@Nonnull final Path file, @Nonnull final Supplier<InputStream> contentSupplier)
			throws IOException {
		return CompletableFuture.supplyAsync(throwingSupplier(() -> {
			try (final InputStream inputStream = contentSupplier.get()) {
				return FINGERPRINT_ALGORITHM.hash(inputStream);
			}
		}), getGenerateExecutor());
	}

	/**
	 * Generates the fingerprint of a directory's contents asynchronously.
	 * @apiNote This method inherently involves production of child imprints during generation of the directory contents fingerprint.
	 * @implSpec This method generates child imprints by delegating to {@link #produceChildImprintsAsync(Path, Supplier)}.
	 * @implSpec This implementation uses the executor returned by {@link #getGenerateExecutor()}.
	 * @implNote This method involves asynchronous recursion to all the descendants of the directory.
	 * @param directory The directory for which a fingerprint should be generated of the children.
	 * @return A future fingerprint of the directory contents.
	 * @throws IOException if there is a problem traversing the directory or reading file contents.
	 */
	CompletableFuture<Hash> generateDirectoryContentsFingerprintAsync(@Nonnull final Path directory) throws IOException {
		return produceChildImprintsAsync(directory, throwingSupplier(() -> readAttributes(directory, BasicFileAttributes.class))) //TODO consolidate attribute supplier
				.thenCompose(childImprintFuturesByPath -> { //**important** --- join the child values asynchronously to prevent a deadlock in a chain when threads are exhausted
					final CompletableFuture<?>[] childImprintFutures = childImprintFuturesByPath.values().toArray(CompletableFuture[]::new);
					//wait for all child futures to finish, and then hash their fingerprints in deterministic order
					return CompletableFuture.allOf(childImprintFutures).thenApply(__ -> {
						final MessageDigest fingerprintMessageDigest = FINGERPRINT_ALGORITHM.getInstance();
						//sort children to ensure deterministic hashing, but we only need to sort by filename as all children are in the same directory
						childImprintFuturesByPath.entrySet().stream().sorted(Comparator.comparing(Map.Entry::getKey, filenameComparator())).map(Map.Entry::getValue)
								.map(CompletableFuture::join).map(PathImprint::contentFingerprint)
								.forEach(fingerprint -> fingerprint.updateMessageDigest(fingerprintMessageDigest));
						return Hash.fromDigest(fingerprintMessageDigest);
					});
				});
	}

	/**
	 * Returns an overall fingerprint for the components of an imprint.
	 * @param filenameFingerprint The fingerprint of the path filename.
	 * @param modifiedAt The modification timestamp of the file.
	 * @param contentFingerprint The fingerprint of the contents of a file, or of the child fingerprints of a directory.
	 * @return A fingerprint of all the components.
	 */
	public static Hash generateFingerprint(@Nonnull final Hash filenameFingerprint, @Nonnull final Instant modifiedAt, @Nonnull final Hash contentFingerprint) {
		final MessageDigest fingerprintMessageDigest = FINGERPRINT_ALGORITHM.getInstance();
		filenameFingerprint.updateMessageDigest(fingerprintMessageDigest);
		fingerprintMessageDigest.update(toBytes(modifiedAt.toEpochMilli()));
		contentFingerprint.updateMessageDigest(fingerprintMessageDigest);
		return Hash.fromDigest(fingerprintMessageDigest);
	}

	/**
	 * Builder for specification for creating a {@link FileSystemDatimprinter}.
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
		 * @return This builder.
		 */
		public Builder withProduceExecutor(@Nonnull final Executor produceExecutor) {
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

		@Nonnull
		private Consumer<PathImprint> imprintConsumer = __ -> {};

		/**
		 * Specifies the imprint consumer.
		 * @param imprintConsumer The consumer to which imprints will be produced after being generated.
		 * @return This builder.
		 */
		public Builder withImprintConsumer(@Nonnull final Consumer<PathImprint> imprintConsumer) {
			this.imprintConsumer = requireNonNull(imprintConsumer);
			return this;
		}

		/** @return A new instance of the datimprinter based upon the current builder configuration. */
		public FileSystemDatimprinter build() {
			return new FileSystemDatimprinter(this);
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
