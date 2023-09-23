/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.metrics.commit;

import org.apache.paimon.annotation.VisibleForTesting;
import org.apache.paimon.data.BinaryRow;
import org.apache.paimon.io.DataFileMeta;
import org.apache.paimon.manifest.FileKind;
import org.apache.paimon.manifest.ManifestEntry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Statistics for a commit. */
public class CommitStats {
    private final long duration;
    private final long attempts;
    private final long tableFilesAdded;
    private final long tableFilesDeleted;
    private final long changelogFilesCommitAppended;
    private final long changelogFilesCompacted;
    private final long generatedSnapshots;
    private final long numPartitionsWritten;
    private final long numBucketsWritten;
    private final Map<BinaryRow, Set<Integer>> bucketsWritten;
    private final Map<BinaryRow, Map<Integer, Long>> bucketedNumTableFilesAdded = new HashMap<>();
    private final Map<BinaryRow, Map<Integer, Long>> bucketedNumTableFilesDeleted = new HashMap<>();
    private final Map<BinaryRow, Map<Integer, Long>> bucketedNumTableFilesAppended =
            new HashMap<>();
    private final Map<BinaryRow, Map<Integer, Long>> bucketedNumTableFilesCompacted =
            new HashMap<>();
    private final Map<BinaryRow, Map<Integer, Long>> bucketedNumChangelogFilesAppended =
            new HashMap<>();
    private final Map<BinaryRow, Map<Integer, Long>> bucketedNumChangelogFilesCompacted =
            new HashMap<>();
    private final Map<BinaryRow, Map<Integer, Long>> bucketedNumDeltaRecordsAppended =
            new HashMap<>();
    private final Map<BinaryRow, Map<Integer, Long>> bucketedNumChangelogRecordsAppended =
            new HashMap<>();
    private final Map<BinaryRow, Map<Integer, Long>> bucketedNumDeltaRecordsCompacted =
            new HashMap<>();
    private final Map<BinaryRow, Map<Integer, Long>> bucketedNumChangelogRecordsCompacted =
            new HashMap<>();

    public CommitStats(
            List<ManifestEntry> appendTableFiles,
            List<ManifestEntry> appendChangelogFiles,
            List<ManifestEntry> compactTableFiles,
            List<ManifestEntry> compactChangelogFiles,
            long commitDuration,
            int generatedSnapshots,
            long attempts) {
        List<ManifestEntry> addedTableFiles = new ArrayList<>(appendTableFiles);
        addedTableFiles.addAll(
                compactTableFiles.stream()
                        .filter(f -> FileKind.ADD.equals(f.kind()))
                        .collect(Collectors.toList()));
        List<ManifestEntry> deletedTableFiles =
                new ArrayList<>(
                        compactTableFiles.stream()
                                .filter(f -> FileKind.DELETE.equals(f.kind()))
                                .collect(Collectors.toList()));

        Map<BinaryRow, Map<Integer, List<DataFileMeta>>> bucketedTableFilesAdded =
                groupByBucket(addedTableFiles);
        Map<BinaryRow, Map<Integer, List<DataFileMeta>>> bucketedTableFilesDeleted =
                groupByBucket(deletedTableFiles);
        Map<BinaryRow, Map<Integer, List<DataFileMeta>>> bucketedTableFilesAppended =
                groupByBucket(appendTableFiles);
        Map<BinaryRow, Map<Integer, List<DataFileMeta>>> bucketedTableFilesCompacted =
                groupByBucket(compactTableFiles);
        Map<BinaryRow, Map<Integer, List<DataFileMeta>>> bucketedChangelogFilesAppended =
                groupByBucket(appendChangelogFiles);
        Map<BinaryRow, Map<Integer, List<DataFileMeta>>> bucketedChangelogFilesCompacted =
                groupByBucket(compactChangelogFiles);

        bucketedTableFilesAdded.forEach(
                (k, v) ->
                        this.bucketedNumTableFilesAdded.put(
                                k, getBucketStats(v, e -> (long) e.size())));

        bucketedTableFilesDeleted.forEach(
                (k, v) ->
                        this.bucketedNumTableFilesDeleted.put(
                                k, getBucketStats(v, e -> (long) e.size())));

        bucketedTableFilesAppended.forEach(
                (k, v) ->
                        this.bucketedNumTableFilesAppended.put(
                                k, getBucketStats(v, e -> (long) e.size())));

        bucketedTableFilesCompacted.forEach(
                (k, v) ->
                        this.bucketedNumTableFilesCompacted.put(
                                k, getBucketStats(v, e -> (long) e.size())));

        bucketedChangelogFilesAppended.forEach(
                (k, v) ->
                        this.bucketedNumChangelogFilesAppended.put(
                                k, getBucketStats(v, e -> (long) e.size())));

        bucketedChangelogFilesCompacted.forEach(
                (k, v) ->
                        this.bucketedNumChangelogFilesCompacted.put(
                                k, getBucketStats(v, e -> (long) e.size())));

        bucketedTableFilesAppended.forEach(
                (k, v) ->
                        this.bucketedNumDeltaRecordsAppended.put(
                                k, getBucketStats(v, e -> getRowCounts(e))));

        bucketedChangelogFilesAppended.forEach(
                (k, v) ->
                        this.bucketedNumChangelogRecordsAppended.put(
                                k, getBucketStats(v, e -> getRowCounts(e))));

        bucketedTableFilesCompacted.forEach(
                (k, v) ->
                        this.bucketedNumDeltaRecordsCompacted.put(
                                k, getBucketStats(v, e -> getRowCounts(e))));

        bucketedChangelogFilesCompacted.forEach(
                (k, v) ->
                        this.bucketedNumChangelogRecordsCompacted.put(
                                k, getBucketStats(v, e -> getRowCounts(e))));

        this.tableFilesAdded = addedTableFiles.size();
        this.tableFilesDeleted = deletedTableFiles.size();
        this.changelogFilesCommitAppended = appendChangelogFiles.size();
        this.changelogFilesCompacted = compactChangelogFiles.size();
        this.numPartitionsWritten = numChangedPartitions(appendTableFiles, compactTableFiles);
        this.numBucketsWritten = numChangedBuckets(appendTableFiles, compactTableFiles);
        this.bucketsWritten = changedPartBuckets(appendTableFiles, compactTableFiles);
        this.duration = commitDuration;
        this.generatedSnapshots = generatedSnapshots;
        this.attempts = attempts;
    }

    private long getRowCounts(List<DataFileMeta> files) {
        return files.stream().mapToLong(file -> file.rowCount()).sum();
    }

    private Map<Integer, Long> getBucketStats(
            Map<Integer, List<DataFileMeta>> bucketFiles, Function<List<DataFileMeta>, Long> func) {
        return bucketFiles.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> func.apply(e.getValue())));
    }

    protected static Map<BinaryRow, Map<Integer, List<DataFileMeta>>> groupByBucket(
            List<ManifestEntry> files) {
        Map<BinaryRow, Map<Integer, List<DataFileMeta>>> groupBy = new LinkedHashMap<>();
        for (ManifestEntry entry : files) {
            groupBy.computeIfAbsent(entry.partition(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(entry.bucket(), k -> new ArrayList<>())
                    .add(entry.file());
        }
        return groupBy;
    }

    @VisibleForTesting
    protected static long numChangedPartitions(List<ManifestEntry>... changes) {
        return Arrays.stream(changes)
                .flatMap(Collection::stream)
                .map(ManifestEntry::partition)
                .distinct()
                .collect(Collectors.toList())
                .size();
    }

    @VisibleForTesting
    protected static long numChangedBuckets(List<ManifestEntry>... changes) {
        return changedPartBuckets(changes).values().stream()
                .map(Set::size)
                .reduce((a, b) -> a + b)
                .orElseGet(() -> 0);
    }

    @VisibleForTesting
    protected static List<BinaryRow> changedPartitions(List<ManifestEntry>... changes) {
        return Arrays.stream(changes)
                .flatMap(Collection::stream)
                .map(ManifestEntry::partition)
                .distinct()
                .collect(Collectors.toList());
    }

    @VisibleForTesting
    protected static Map<BinaryRow, Set<Integer>> changedPartBuckets(
            List<ManifestEntry>... changes) {
        Map<BinaryRow, Set<Integer>> changedPartBuckets = new LinkedHashMap<>();
        Arrays.stream(changes)
                .flatMap(Collection::stream)
                .forEach(
                        entry ->
                                changedPartBuckets
                                        .computeIfAbsent(
                                                entry.partition(), k -> new LinkedHashSet<>())
                                        .add(entry.bucket()));
        return changedPartBuckets;
    }

    @VisibleForTesting
    protected long getTableFilesAdded() {
        return tableFilesAdded;
    }

    @VisibleForTesting
    protected long getBucketedTableFilesAdded(BinaryRow partition, int bucket) {
        if (bucketedNumTableFilesAdded.containsKey(partition)) {
            return bucketedNumTableFilesAdded.get(partition).getOrDefault(bucket, 0L);
        }
        return 0L;
    }

    @VisibleForTesting
    protected long getTableFilesDeleted() {
        return tableFilesDeleted;
    }

    @VisibleForTesting
    protected long getBucketedTableFilesDeleted(BinaryRow partition, int bucket) {
        if (bucketedNumTableFilesDeleted.containsKey(partition)) {
            return bucketedNumTableFilesDeleted.get(partition).getOrDefault(bucket, 0L);
        }
        return 0L;
    }

    @VisibleForTesting
    protected long getBucketedTableFilesAppended(BinaryRow partition, int bucket) {
        if (bucketedNumTableFilesAppended.containsKey(partition)) {
            return bucketedNumTableFilesAppended.get(partition).getOrDefault(bucket, 0L);
        }
        return 0L;
    }

    @VisibleForTesting
    protected long getBucketedTableFilesCompacted(BinaryRow partition, int bucket) {
        if (bucketedNumTableFilesCompacted.containsKey(partition)) {
            return bucketedNumTableFilesCompacted.get(partition).getOrDefault(bucket, 0L);
        }
        return 0L;
    }

    @VisibleForTesting
    protected long getChangelogFilesCommitAppended() {
        return changelogFilesCommitAppended;
    }

    @VisibleForTesting
    protected long getBucketedChangelogFilesAppended(BinaryRow partition, int bucket) {
        if (bucketedNumChangelogFilesAppended.containsKey(partition)) {
            return bucketedNumChangelogFilesAppended.get(partition).getOrDefault(bucket, 0L);
        }
        return 0L;
    }

    @VisibleForTesting
    protected long getChangelogFilesCompacted() {
        return changelogFilesCompacted;
    }

    @VisibleForTesting
    protected long getBucketedChangelogFilesCompacted(BinaryRow partition, int bucket) {
        if (bucketedNumChangelogFilesCompacted.containsKey(partition)) {
            return bucketedNumChangelogFilesCompacted.get(partition).getOrDefault(bucket, 0L);
        }
        return 0L;
    }

    @VisibleForTesting
    protected long getGeneratedSnapshots() {
        return generatedSnapshots;
    }

    @VisibleForTesting
    protected long getBucketedDeltaRecordsAppended(BinaryRow partition, int bucket) {
        if (bucketedNumDeltaRecordsAppended.containsKey(partition)) {
            return bucketedNumDeltaRecordsAppended.get(partition).getOrDefault(bucket, 0L);
        }
        return 0L;
    }

    @VisibleForTesting
    protected long getBucketedChangelogRecordsAppended(BinaryRow partition, int bucket) {
        if (bucketedNumChangelogRecordsAppended.containsKey(partition)) {
            return bucketedNumChangelogRecordsAppended.get(partition).getOrDefault(bucket, 0L);
        }
        return 0L;
    }

    @VisibleForTesting
    protected long getBucketedDeltaRecordsCompacted(BinaryRow partition, int bucket) {
        if (bucketedNumDeltaRecordsCompacted.containsKey(partition)) {
            return bucketedNumDeltaRecordsCompacted.get(partition).getOrDefault(bucket, 0L);
        }
        return 0L;
    }

    @VisibleForTesting
    protected long getBucketedChangelogRecordsCompacted(BinaryRow partition, int bucket) {
        if (bucketedNumChangelogRecordsCompacted.containsKey(partition)) {
            return bucketedNumChangelogRecordsCompacted.get(partition).getOrDefault(bucket, 0L);
        }
        return 0L;
    }

    @VisibleForTesting
    protected long getNumPartitionsWritten() {
        return numPartitionsWritten;
    }

    @VisibleForTesting
    protected long getNumBucketsWritten() {
        return numBucketsWritten;
    }

    @VisibleForTesting
    protected Map<BinaryRow, Set<Integer>> getPartBucketsWritten() {
        return bucketsWritten;
    }

    @VisibleForTesting
    protected long getDuration() {
        return duration;
    }

    @VisibleForTesting
    protected long getAttempts() {
        return attempts;
    }
}
