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

import static com.globalmentor.java.Appendables.*;
import static com.globalmentor.java.CharSequences.*;
import static com.globalmentor.java.Characters.*;
import static com.globalmentor.java.Conditions.*;
import static java.nio.charset.StandardCharsets.*;
import static java.util.Collections.*;
import static java.util.Objects.*;
import static java.util.stream.Collectors.*;
import static org.zalando.fauxpas.FauxPas.*;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

import javax.annotation.*;

import com.globalmentor.io.BOMInputStreamReader;
import com.globalmentor.java.Characters;
import com.globalmentor.security.Hash;

/**
 * Definition and implementation of an imprints <code>.datim</code> file.
 * @author Garret Wilson
 */
public class Datim {

	/** An extension for imprints file filenames. */
	public static final String FILENAME_EXTENSION = "datim";

	/** The default charset for an imprints file. */
	public static final Charset DEFAULT_CHARSET = UTF_8;

	/** The delimiter used to separate fields in each row. */
	public static final char FIELD_DELIMITER = CHARACTER_TABULATION_CHAR;

	/** Definitions of the fields/columns, in default order. */
	public static enum Field {

		/** The name of the imprint number field. */
		NUMBER("#"),

		/** The name of the imprint mini-fingerprint field. */
		MINIPRINT("miniprint"),

		/** The name of the imprint path field. */
		PATH("path"),

		/** The name of the imprint content timestamp field. */
		CONTENT_MODIFIED_AT("content-modifiedAt"),

		/** The name of the imprint content fingerprint field. */
		CONTENT_FINGERPRINT("content-fingerprint"),

		/** The name of the imprint fingerprint field. */
		FINGERPRINT("fingerprint");

		private final String headerName;

		/**
		 * Returns the name of the field for the header.
		 * @return The header name of the field.
		 */
		public String headerName() {
			return headerName;
		}

		/**
		 * Constructor.
		 * @param headerName The header name of the field.
		 */
		private Field(@Nonnull final String headerName) {
			this.headerName = requireNonNull(headerName);
		}

		/**
		 * Finds the field corresponding to the given header name.
		 * @implNote This implementation is not particularly efficient, usually this is only called for determining each field, which is only done once per file.
		 * @param headerName The name of the field as found in the header.
		 * @return The matching field, if any.
		 */
		public static Optional<Field> findByHeaderName(@Nonnull String headerName) {
			return Stream.of(Field.values()).filter(field -> field.headerName().equals(headerName)).findAny();
		}

	}

	/** The identification of a line containing a base path designation. */
	public static final String RECORD_TYPE_BASE_PATH = "/";

	/**
	 * Implementation for parsing Datim files.
	 * <p>
	 * This class keeps state about which features have been read, such as the header and base path; these features are read automatically and for the most part
	 * implicitly from the caller's point of view.
	 * </p>
	 * @implNote This class is not thread safe.
	 * @author Garret Wilson
	 */
	public static class Parser {

		private final BufferedReader reader;

		/** @return The reader from which data is parsed. */
		protected BufferedReader getReader() {
			return reader;
		}

		private int nextLineIndex = 0;

		/**
		 * Retrieves the next index of the next line to read.
		 * @apiNote The returned value is equivalent to the one-based line number of the _previous_ line read.
		 * @return The zero-based next line index.
		 */
		protected int getNextLineIndex() {
			return nextLineIndex;
		}

		/**
		 * Reads a line and splits out the fields.
		 * @implNote This method increases the line number.
		 * @return The next line of fields, which will be empty if the end of file was reached without reading any characters.
		 * @throws IOException if there was an error reading the line.
		 * @see #getNextLineIndex()
		 */
		protected Optional<String[]> readRecord() throws IOException {
			final String line = getReader().readLine();
			if(line == null) {
				return Optional.empty();
			}
			nextLineIndex++;
			return Optional.of(line.split(String.valueOf(FIELD_DELIMITER), -1)); //catch trailing delimiters
		}

		@Nullable
		private Map<Field, Integer> fieldIndexes = null;

		private Optional<Path> foundCurrentBasePath = Optional.empty();

		/** @return The current base path, which may not be present if no base path line yet been encountered. */
		public Optional<Path> findCurrentBasePath() {
			return foundCurrentBasePath;
		}

		/**
		 * Input stream parser. The charset is attempted to be determined from the Byte Order Mark (BOM), if any; defaulting to {@link Datim#DEFAULT_CHARSET}.
		 * @param inputStream The input stream from which to parse.
		 * @throws IOException if there is an error attempting to read the byte order mark from the input stream.
		 */
		public Parser(@Nonnull final InputStream inputStream) throws IOException {
			this(new BOMInputStreamReader(inputStream, DEFAULT_CHARSET));
		}

		/**
		 * Reader parser.
		 * @implNote A {@link BufferedReader} will be wrapped around the given reader unless the reader is already a {@link BufferedReader}.
		 * @param reader The reader from which to parse.
		 */
		public Parser(@Nonnull final Reader reader) {
			this.reader = reader instanceof BufferedReader ? (BufferedReader)reader : new BufferedReader(reader);
		}

		/**
		 * Returns the fields defined in the file and their indexes, reading them if necessary. When the fields are first read, they are also validated to ensure
		 * all the required fields are present.
		 * @apiNote This method is called implicitly by the line-reading methods to ensure the fields have been read. There is no need to call it manually for
		 *          reading the fields unless the caller actually desires to access the fields.
		 * @return The fields defined in the file and their indexes.
		 * @throws EOFException if the fields haven't been read and there is no more data in the file.
		 * @throws IOException if the fields haven't been read and there is an error reading the fields, or if there is a required field missing.
		 */
		public Map<Field, Integer> getFieldIndexes() throws IOException {
			if(this.fieldIndexes == null) {
				final List<Field> fields = readHeader();
				final Map<Field, Integer> fieldIndexes = new EnumMap<>(Field.class);
				for(int i = fields.size() - 1; i >= 0; i--) {
					fieldIndexes.put(fields.get(i), i);
				}
				for(final Field field : Field.values()) { //validate the fields; currently all are required
					if(!fieldIndexes.containsKey(field)) {
						throw new IOException("Header missing required field `%s`.".formatted(field.headerName));
					}
				}
				this.fieldIndexes = unmodifiableMap(fieldIndexes);
			}
			return this.fieldIndexes;
		}

		/**
		 * Reads the field definition line in the file from the header at the current position.
		 * @implSpec This method returns an unmodifiable list.
		 * @return The header fields in the order they are defined in the file.
		 * @throws EOFException if there is no more data in the file.
		 * @throws IOException if there some other error reading the fields.
		 */
		List<Field> readHeader() throws IOException {
			final String[] headerNames = readRecord()
					.orElseThrow(() -> new EOFException("Header missing; end of data reached attempting to read line #%d.".formatted(getNextLineIndex() + 1)));
			return Stream.of(headerNames).map(throwingFunction(
					headerName -> Field.findByHeaderName(headerName).orElseThrow(() -> new IOException("Unrecognized field header name `%s`.".formatted(headerName)))))
					.collect(toUnmodifiableList());
		}

		/**
		 * Reads the next imprint from the input. For each base path record, if any, the current base path returned by {@link #findCurrentBasePath()} will be
		 * updated and the record will be skipped.
		 * @implNote This implementation combines base path record processing/skipping in its logic. If other record types are added in the future, the reading of
		 *           an "entity" will need to be extracted into a separate method, which this method can delegate to and check the returned entity type.
		 * @return The imprint that was read, which will not be present if the end of the file was reached.
		 * @throws IOException If there was an error attempting to reading the imprint or if the imprint record did not have valid information.
		 */
		public Optional<PathImprint> readImprint() throws IOException {
			final Map<Field, Integer> fieldIndexes = getFieldIndexes();
			Optional<String[]> record;
			while(!(record = readRecord()).isEmpty()) { //keep reading records until there are no more
				Optional<PathImprint> foundImprint = record.flatMap(fields -> { //this lambda has side effects: it may update foundCurrentBasePath 
					final Path path = Path.of(fields[fieldIndexes.get(Field.PATH)]);
					final String recordType = fields[fieldIndexes.get(Field.NUMBER)];
					if(recordType.equals(RECORD_TYPE_BASE_PATH)) { //if this is a base path record
						foundCurrentBasePath = Optional.of(path); //update the base path
						return Optional.empty(); //skip the record
					}
					final FileTime contentModifiedAt = FileTime.from(Instant.parse(fields[fieldIndexes.get(Field.CONTENT_MODIFIED_AT)]));
					final Hash contentFingerprint = Hash.fromChecksum(fields[fieldIndexes.get(Field.CONTENT_FINGERPRINT)]);
					final Hash fingerprint = Hash.fromChecksum(fields[fieldIndexes.get(Field.FINGERPRINT)]);
					return Optional.of(new PathImprint(path, contentModifiedAt, contentFingerprint, fingerprint));
				});
				if(foundImprint.isPresent()) { //if this record was mapped to an imprint, short circuit --- we found what we were looking for
					return foundImprint;
				}
			}
			return Optional.empty(); //we ran out of records without finding an imprint line
		}
	}

	/**
	 * Implementation for serializing Datim files.
	 * @implNote This class is not thread safe.
	 * @author Garret Wilson
	 */
	public static class Serializer {

		private final String lineSeparator;

		/** @return The line separator being used for serialization. */
		public String getLineSeparator() {
			return lineSeparator;
		}

		/**
		 * Default settings constructor. The system line separator is used.
		 * @see System#lineSeparator()
		 */
		public Serializer() {
			this(System.lineSeparator());
		}

		/**
		 * Line separator constructor.
		 * @param lineSeparator The newline character to separate each line.
		 */
		public Serializer(@Nonnull final String lineSeparator) {
			this.lineSeparator = requireNonNull(lineSeparator);
		}

		/**
		 * Writes the header of an imprints file using the configured line separator.
		 * @param <A> The type of appendable.
		 * @param appendable The appendable for writing the imprint header.
		 * @return The same appendable after appending the header.
		 * @throws IOException if an I/O error occurs writing the data.
		 * @see Field#values()
		 * @see #getLineSeparator()
		 */
		public <A extends Appendable> A appendHeader(@Nonnull final A appendable) throws IOException {
			appendJoined(appendable, FIELD_DELIMITER, (Iterable<String>)Stream.of(Field.values()).map(Field::headerName)::iterator).append(getLineSeparator());
			//TODO add "levels" column with e.g. `+++` designation for number of levels below root
			return appendable;
		}

		/**
		 * Writes a base path line using the configured line separator.
		 * @param <A> The type of appendable.
		 * @param appendable The appendable for writing the imprint.
		 * @param basePath The base path to write; will be converted to absolute.
		 * @return The same appendable after appending the base path line.
		 * @throws IllegalArgumentException if the base path contains {@link #FIELD_DELIMITER}.
		 * @throws IOException if an I/O error occurs writing the data.
		 * @see #getLineSeparator()
		 */
		public <A extends Appendable> A appendBasePath(@Nonnull final A appendable, @Nonnull final Path basePath) throws IOException {
			final String basePathString = basePath.toAbsolutePath().toString();
			checkArgument(!contains(basePathString, FIELD_DELIMITER), "Base path `%s` cannot contain field delimiter %s.", basePathString,
					Characters.getLabel(FIELD_DELIMITER));
			appendJoined(appendable, FIELD_DELIMITER, RECORD_TYPE_BASE_PATH, "", basePathString, "", "", "").append(getLineSeparator());
			return appendable;
		}

		/**
		 * Writes a single imprint record using the configured line separator.
		 * @param <A> The type of appendable.
		 * @param appendable The appendable for writing the imprint.
		 * @param imprint The imprint to write.
		 * @param number The number of the line being written.
		 * @return The same appendable after appending the imprint.
		 * @throws IllegalArgumentException if the imprint path contains {@link #FIELD_DELIMITER}.
		 * @throws IOException if an I/O error occurs writing the data.
		 * @see #getLineSeparator()
		 */
		public <A extends Appendable> A appendImprint(@Nonnull final A appendable, @Nonnull final PathImprint imprint, @Nonnull final long number)
				throws IOException {
			final String pathString = imprint.path().toString(); //the imprint path is already absolute
			checkArgument(!contains(pathString, FIELD_DELIMITER), "Path `%s` cannot contain field delimiter %s.", pathString, Characters.getLabel(FIELD_DELIMITER));
			appendJoined(appendable, FIELD_DELIMITER, Long.toUnsignedString(number), imprint.miniprintChecksum(), pathString, imprint.contentModifiedAt().toString(),
					imprint.contentFingerprint().toChecksum(), imprint.fingerprint().toChecksum()).append(getLineSeparator());
			return appendable;
		}

	}

}
