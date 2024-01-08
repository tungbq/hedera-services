/*
 * Copyright (C) 2016-2024 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.platform.event.preconsensus;

import static com.swirlds.common.formatting.StringFormattingUtils.parseSanitizedTimestamp;
import static com.swirlds.common.formatting.StringFormattingUtils.sanitizeTimestamp;

import com.swirlds.common.io.utility.RecycleBin;
import com.swirlds.common.utility.NonCryptographicHashing;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * <p>
 * Describes a preconsensus event stream file.
 * </p>
 *
 * <p>
 * Files have the following format. Deviation from this format is not allowed. A {@link PcesFileManager}
 * will be unable to correctly read files with a different format.
 * </p>
 * <pre>
 * [Instant.toString().replace(":", "+")]-seq[sequence number]-ming[minimum legal generation]-maxg[maximum legal generation]-orgn[origin round].pces
 * </pre>
 * <p>
 * By default, files are stored with the following directory structure. Note that files are not required to be stored
 * with this directory structure in order to be read by a {@link PcesFileManager}.
 * </p>
 * <pre>
 * [root directory]/[4 digit year][2 digit month][2 digit day]/[file name]
 * </pre>
 */
public final class PcesFile implements Comparable<PcesFile> {

    /**
     * The file extension for standard files. Stands for "PreConsensus Event Stream".
     */
    public static final String EVENT_FILE_EXTENSION = ".pces";

    /**
     * The character used to separate fields in the file name.
     */
    public static final String EVENT_FILE_SEPARATOR = "_";

    /**
     * Written before the sequence number in the file name. Improves readability for humans.
     */
    public static final String SEQUENCE_NUMBER_PREFIX = "seq";

    /**
     * Written before the minimum generation in the file name. Improves readability for humans.
     */
    public static final String MINIMUM_GENERATION_PREFIX = "ming";

    /**
     * Written before the maximum generation in the file name. Improves readability for humans.
     */
    public static final String MAXIMUM_GENERATION_PREFIX = "maxg";

    /**
     * Written before the origin round. Improves readability for humans.
     */
    public static final String ORIGIN_PREFIX = "orgn";

    /**
     * The initial capacity of the string builder used to build file names.
     */
    private static final int STRING_BUILDER_INITIAL_CAPACITY = 128;

    /**
     * The sequence number of the file. All file sequence numbers are unique. Sequence numbers are allocated in
     * monotonically increasing order.
     */
    private final long sequenceNumber;

    /**
     * The minimum generation of events that are permitted to be in this file.
     */
    private final long minimumGeneration;

    /**
     * The maximum generation of events that are permitted to be in this file.
     */
    private final long maximumGeneration;

    /**
     * The round number from which an unbroken stream of events has been written. If two sequential files have different
     * origin rounds, this signals a discontinuity in the stream.
     */
    private final long origin;

    /**
     * The timestamp of when the writing of this file began.
     */
    private final Instant timestamp;

    /**
     * The on-disk location of the file.
     */
    private final Path path;

    /**
     * Construct a new PreConsensusEventFile.
     *
     * @param timestamp         the timestamp of when the writing of this file began
     * @param sequenceNumber    the sequence number of the file. All file sequence numbers are unique. Sequence numbers
     *                          are allocated in monotonically increasing order.
     * @param minimumGeneration the minimum generation of events that are permitted to be in this file
     * @param maximumGeneration the maximum generation of events that are permitted to be in this file
     * @param origin            the origin of the stream file, signals the round from which the stream is unbroken
     * @param path              the location where this file can be found
     */
    private PcesFile(
            @NonNull final Instant timestamp,
            final long sequenceNumber,
            final long minimumGeneration,
            final long maximumGeneration,
            final long origin,
            @NonNull final Path path) {

        if (sequenceNumber < 0) {
            throw new IllegalArgumentException("sequence number " + minimumGeneration + " is negative");
        }

        if (minimumGeneration < 0) {
            throw new IllegalArgumentException("minimum generation " + minimumGeneration + " is negative");
        }

        if (maximumGeneration < 0) {
            throw new IllegalArgumentException("maximum generation " + maximumGeneration + " is negative");
        }

        if (origin < 0) {
            throw new IllegalArgumentException("origin " + origin + " is negative");
        }

        if (maximumGeneration < minimumGeneration) {
            throw new IllegalArgumentException("maximum generation " + maximumGeneration
                    + " is less than minimum generation " + minimumGeneration);
        }

        this.sequenceNumber = sequenceNumber;
        this.minimumGeneration = minimumGeneration;
        this.maximumGeneration = maximumGeneration;
        this.origin = origin;
        this.timestamp = Objects.requireNonNull(timestamp);
        this.path = Objects.requireNonNull(path);
    }

    /**
     * Create a new event file descriptor.
     *
     * @param timestamp         the timestamp when this file was created (wall clock time)
     * @param sequenceNumber    the sequence number of the descriptor
     * @param minimumGeneration the minimum event generation permitted to be in this file (inclusive)
     * @param maximumGeneration the maximum event generation permitted to be in this file (inclusive)
     * @param origin            the origin round number, i.e. the round after which the stream is unbroken
     * @param rootDirectory     the directory where event stream files are stored
     * @return a description of the file
     */
    @NonNull
    public static PcesFile of(
            @NonNull final Instant timestamp,
            final long sequenceNumber,
            final long minimumGeneration,
            final long maximumGeneration,
            final long origin,
            @NonNull final Path rootDirectory) {

        final Path parentDirectory = buildParentDirectory(rootDirectory, timestamp);
        final String fileName = buildFileName(timestamp, sequenceNumber, minimumGeneration, maximumGeneration, origin);
        final Path path = parentDirectory.resolve(fileName);

        return new PcesFile(timestamp, sequenceNumber, minimumGeneration, maximumGeneration, origin, path);
    }

    /**
     * Create a new event file descriptor by parsing a file path.
     *
     * @param filePath the path to the file
     * @return a description of the file
     * @throws IOException if the file could not be parsed
     */
    @NonNull
    public static PcesFile of(@NonNull final Path filePath) throws IOException {
        Objects.requireNonNull(filePath, "filePath");

        if (!filePath.toString().endsWith(EVENT_FILE_EXTENSION)) {
            throw new IOException("File " + filePath + " has the wrong type");
        }

        final String fileName = filePath.getFileName().toString();

        final String[] elements = fileName.substring(0, fileName.length() - EVENT_FILE_EXTENSION.length())
                .split(EVENT_FILE_SEPARATOR);

        if (elements.length != 5) {
            throw new IOException("Unable to parse fields from " + filePath);
        }

        try {
            return new PcesFile(
                    parseSanitizedTimestamp(elements[0]),
                    Long.parseLong(elements[1].replace(SEQUENCE_NUMBER_PREFIX, "")),
                    Long.parseLong(elements[2].replace(MINIMUM_GENERATION_PREFIX, "")),
                    Long.parseLong(elements[3].replace(MAXIMUM_GENERATION_PREFIX, "")),
                    Long.parseLong(elements[4].replace(ORIGIN_PREFIX, "")),
                    filePath);
        } catch (final DateTimeParseException | IllegalArgumentException ex) {
            throw new IOException("unable to parse " + filePath, ex);
        }
    }

    /**
     * Create a new event file descriptor for span compaction.
     *
     * @param maximumGenerationInFile the maximum generation that is actually in the file
     * @return a description of the new file
     */
    @NonNull
    public PcesFile buildFileWithCompressedSpan(final long maximumGenerationInFile) {
        if (maximumGenerationInFile < minimumGeneration) {
            throw new IllegalArgumentException("maximumGenerationInFile " + maximumGenerationInFile
                    + " is less than minimumGeneration " + minimumGeneration);
        }

        if (maximumGenerationInFile > maximumGeneration) {
            throw new IllegalArgumentException("maximumGenerationInFile " + maximumGenerationInFile
                    + " is greater than maximumGeneration " + maximumGeneration);
        }

        final Path parentDirectory = path.getParent();
        final String fileName =
                buildFileName(timestamp, sequenceNumber, minimumGeneration, maximumGenerationInFile, origin);
        final Path newPath = parentDirectory.resolve(fileName);

        return new PcesFile(timestamp, sequenceNumber, minimumGeneration, maximumGenerationInFile, origin, newPath);
    }

    /**
     * @return the timestamp when this file was created (wall clock time)
     */
    @NonNull
    public Instant getTimestamp() {
        return timestamp;
    }

    /**
     * @return the sequence number of the file. All file sequence numbers are unique. Sequence numbers are allocated in
     * monotonically increasing order.
     */
    public long getSequenceNumber() {
        return sequenceNumber;
    }

    /**
     * @return the minimum event generation permitted to be in this file (inclusive)
     */
    public long getMinimumGeneration() {
        return minimumGeneration;
    }

    /**
     * @return the maximum event generation permitted to be in this file (inclusive)
     */
    public long getMaximumGeneration() {
        return maximumGeneration;
    }

    /**
     * Get the origin of the stream containing this file. A stream's origin is defined as the round number after which
     * the stream is unbroken. When the origin of two sequential files is different, this signals a discontinuity in the
     * stream (i.e. the end of one stream and the beginning of another). When replaying events, it is never ok to stream
     * events from files with different origins.
     *
     * @return the origin round number
     */
    public long getOrigin() {
        return origin;
    }

    /**
     * @return the path to this file
     */
    @NonNull
    public Path getPath() {
        return path;
    }

    /**
     * Get an object that can be used to write events to this file. Throws if there already exists a file on disk with
     * the same path.
     *
     * @return a writer for this file
     */
    @NonNull
    public PcesMutableFile getMutableFile() throws IOException {
        return new PcesMutableFile(this);
    }

    /**
     * Delete a file (permanently). Automatically deletes parent directories if empty up until the root directory is
     * reached, which is never deleted.
     *
     * @param rootDirectory the root directory where event files are stored
     */
    public void deleteFile(@NonNull final Path rootDirectory) throws IOException {
        deleteFile(rootDirectory, null);
    }

    /**
     * Delete a file (permanently). Automatically deletes parent directories if empty up until the root directory is
     * reached, which is never deleted.
     *
     * @param rootDirectory the root directory where event files are stored
     * @param recycleBin    if not null, then recycle the files instead of deleting them
     */
    public void deleteFile(@NonNull final Path rootDirectory, @Nullable final RecycleBin recycleBin)
            throws IOException {
        if (!Files.exists(path)) {
            // Nothing to delete.
            return;
        }

        if (recycleBin == null) {
            Files.delete(path);
        } else {
            recycleBin.recycle(path);
        }

        // Delete parent directories if they are empty
        Path target = path.getParent();
        while (!target.equals(rootDirectory)) {
            try (final Stream<Path> list = Files.list(target)) {
                if (list.findAny().isPresent()) {
                    // This directory is not empty, stop deleting
                    return;
                }
            }

            // This will fail if we attempt to delete a non-empty directory, so there is no danger
            // of this loop walking all the way up to / and deleting the entire file system.
            Files.delete(target);

            target = target.getParent();
        }
    }

    /**
     * Get an iterator that walks over the events in this file. The iterator will only return events that have a
     * generation equal to or greater to the minimum generation.
     *
     * @param minimumGeneration the minimum generation of the events to return
     * @return an iterator over the events in this file
     */
    @NonNull
    public PcesFileIterator iterator(final long minimumGeneration) throws IOException {
        return new PcesFileIterator(this, minimumGeneration);
    }

    /**
     * Build the parent directory for a new event file.
     *
     * @param rootDirectory the root directory where all event files are stored
     * @param timestamp     the timestamp of the new file
     * @return the parent directory of the new file
     */
    @NonNull
    private static Path buildParentDirectory(@NonNull final Path rootDirectory, @NonNull final Instant timestamp) {
        final ZonedDateTime zonedDateTime = timestamp.atZone(ZoneId.systemDefault());
        return rootDirectory
                .resolve(String.format("%04d", zonedDateTime.getYear()))
                .resolve(String.format("%02d", zonedDateTime.getMonthValue()))
                .resolve(String.format("%02d", zonedDateTime.getDayOfMonth()));
    }

    /**
     * Derive the name for this file.
     *
     * @param timestamp         the timestamp of when the file was created
     * @param sequenceNumber    the sequence number of the file
     * @param minimumGeneration the minimum generation of events permitted in this file
     * @param maximumGeneration the maximum generation of events permitted in this file
     * @param origin            the origin round number, i.e. the round after which the stream is unbroken
     * @return the file name
     */
    @NonNull
    private static String buildFileName(
            @NonNull final Instant timestamp,
            final long sequenceNumber,
            final long minimumGeneration,
            final long maximumGeneration,
            final long origin) {

        return new StringBuilder(STRING_BUILDER_INITIAL_CAPACITY)
                .append(sanitizeTimestamp(timestamp))
                .append(EVENT_FILE_SEPARATOR)
                .append(SEQUENCE_NUMBER_PREFIX)
                .append(sequenceNumber)
                .append(EVENT_FILE_SEPARATOR)
                .append(MINIMUM_GENERATION_PREFIX)
                .append(minimumGeneration)
                .append(EVENT_FILE_SEPARATOR)
                .append(MAXIMUM_GENERATION_PREFIX)
                .append(maximumGeneration)
                .append(EVENT_FILE_SEPARATOR)
                .append(ORIGIN_PREFIX)
                .append(origin)
                .append(EVENT_FILE_EXTENSION)
                .toString();
    }

    /**
     * Get the file name of this file.
     *
     * @return this file's name
     */
    @NonNull
    public String getFileName() {
        return path.getFileName().toString();
    }

    /**
     * Check if it is legal for the file described by this object to contain a particular event.
     *
     * @param generation the generation of the event in question
     * @return true if it is legal for this event to be in the file described by this object
     */
    public boolean canContain(final long generation) {
        return generation >= minimumGeneration && generation <= maximumGeneration;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int compareTo(@NonNull final PcesFile that) {
        return Long.compare(sequenceNumber, that.sequenceNumber);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return getFileName();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(@Nullable final Object obj) {
        if (obj == this) {
            return true;
        }

        if (obj instanceof final PcesFile that) {
            return this.sequenceNumber == that.sequenceNumber;
        }

        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return NonCryptographicHashing.hash32(sequenceNumber);
    }
}
