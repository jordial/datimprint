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
import static java.util.Objects.*;

import java.io.*;
import java.nio.charset.Charset;
import java.util.List;

import javax.annotation.*;

import com.globalmentor.java.Characters;

/**
 * Definition and implementation of an imprints <code>.datim</code> file.
 * @author Garret Wilson
 */
public class Datim {

	/** An extension for imprints file filenames. */
	public static final String FILENAME_EXTENSION = "datim";

	/** The default charset for an imprints file. */
	public static final Charset CHARSET = UTF_8;

	/** The delimiter used to separate fields in each row. */
	public static final char FIELD_DELIMITER = CHARACTER_TABULATION_CHAR;

	/** The name of the imprint number field. */
	public static final String FIELD_NAME_NUMBER = "#";

	/** The name of the imprint mini-fingerprint field. */
	public static final String FIELD_NAME_MINIPRINT = "miniprint";

	/** The name of the imprint path field. */
	public static final String FIELD_NAME_PATH = "path";

	/** The name of the imprint content timestamp field. */
	public static final String FIELD_NAME_CONTENT_MODIFIED_AT = "content-modifiedAt";

	/** The name of the imprint content fingerprint field. */
	public static final String FIELD_NAME_CONTENT_FINGERPRINT = "content-fingerprint";

	/** The name of the imprint fingerprint field. */
	public static final String FIELD_NAME_FINGERPRINT = "fingerprint";

	/** The list of field names for column headers. */
	public static List<String> FIELD_NAMES = List.of(FIELD_NAME_NUMBER, FIELD_NAME_MINIPRINT, FIELD_NAME_PATH, FIELD_NAME_CONTENT_MODIFIED_AT,
			FIELD_NAME_CONTENT_FINGERPRINT, FIELD_NAME_FINGERPRINT);

	/**
	 * Implementation for serializing Datim files.
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
		 * @see #FIELD_NAMES
		 * @see #getLineSeparator()
		 */
		public <A extends Appendable> A appendHeader(@Nonnull final A appendable) throws IOException {
			return appendHeader(appendable, getLineSeparator());
		}

		/**
		 * Writes the header of an imprints file.
		 * @param <A> The type of appendable.
		 * @param appendable The appendable for writing the imprint header.
		 * @param lineSeparator The end-of-line character sequence.
		 * @return The same appendable after appending the header.
		 * @throws IOException if an I/O error occurs writing the data.
		 * @see #FIELD_NAMES
		 */
		static <A extends Appendable> A appendHeader(@Nonnull final A appendable, @Nonnull final CharSequence lineSeparator) throws IOException {
			appendJoined(appendable, FIELD_DELIMITER, FIELD_NAMES).append(lineSeparator);
			//TODO add "levels" column with e.g. `+++` designation for number of levels below root
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
			return appendImprint(appendable, imprint, number, getLineSeparator());
		}

		/**
		 * Writes a single imprint record.
		 * @param <A> The type of appendable.
		 * @param appendable The appendable for writing the imprint.
		 * @param imprint The imprint to write.
		 * @param number The number of the line being written.
		 * @param lineSeparator The end-of-line character.
		 * @return The same appendable after appending the imprint.
		 * @throws IllegalArgumentException if the imprint path contains {@link #FIELD_DELIMITER}.
		 * @throws IOException if an I/O error occurs writing the data.
		 */
		static <A extends Appendable> A appendImprint(@Nonnull final A appendable, @Nonnull final PathImprint imprint, @Nonnull final long number,
				@Nonnull final CharSequence lineSeparator) throws IOException {
			final String pathString = imprint.path().toString();
			checkArgument(!contains(pathString, FIELD_DELIMITER), "Path `%s` cannot contain field delimiter .", pathString, Characters.getLabel(FIELD_DELIMITER));
			appendJoined(appendable, FIELD_DELIMITER, Long.toUnsignedString(number), imprint.miniprintChecksum(), pathString, imprint.modifiedAt().toString(),
					imprint.contentFingerprint().toChecksum(), imprint.fingerprint().toChecksum()).append(lineSeparator);
			return appendable;
		}

	}

}
