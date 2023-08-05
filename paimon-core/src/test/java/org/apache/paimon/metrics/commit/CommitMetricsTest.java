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

import org.apache.paimon.CoreOptions;
import org.apache.paimon.data.GenericRow;
import org.apache.paimon.fs.Path;
import org.apache.paimon.fs.local.LocalFileIO;
import org.apache.paimon.manifest.FileKind;
import org.apache.paimon.manifest.ManifestEntry;
import org.apache.paimon.metrics.Counter;
import org.apache.paimon.metrics.Gauge;
import org.apache.paimon.metrics.Histogram;
import org.apache.paimon.metrics.Metric;
import org.apache.paimon.metrics.MetricGroup;
import org.apache.paimon.metrics.Metrics;
import org.apache.paimon.schema.Schema;
import org.apache.paimon.schema.SchemaManager;
import org.apache.paimon.schema.SchemaUtils;
import org.apache.paimon.schema.TableSchema;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.FileStoreTableFactory;
import org.apache.paimon.table.sink.BatchTableCommit;
import org.apache.paimon.table.sink.BatchTableWrite;
import org.apache.paimon.table.sink.BatchWriteBuilder;
import org.apache.paimon.types.DataType;
import org.apache.paimon.types.DataTypes;
import org.apache.paimon.types.IntType;
import org.apache.paimon.types.RowType;
import org.apache.paimon.utils.FileStorePathFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.apache.paimon.CoreOptions.BUCKET;
import static org.apache.paimon.CoreOptions.BUCKET_KEY;
import static org.apache.paimon.CoreOptions.CHANGELOG_PRODUCER;
import static org.apache.paimon.manifest.ManifestFileMetaTestBase.makeEntry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/** Tests for {@link CommitMetrics}. */
public class CommitMetricsTest {
    @TempDir static java.nio.file.Path tempDir;
    private static final String TABLE_NAME = "myTable";

    private CommitMetrics commitMetrics;

    @BeforeEach
    public void beforeEach() {
        Metrics.getInstance().getMetricGroups().clear();
        commitMetrics = getCommitMetrics();
    }

    /** Tests the registration of the commit metrics. */
    @Test
    public void testGenericMetricsRegistration() {
        MetricGroup genericMetricGroup = commitMetrics.getGenericMetricGroup();
        assertThat(Metrics.getInstance().getMetricGroups().size()).isEqualTo(1);
        assertThat(genericMetricGroup.getGroupName()).isEqualTo(CommitMetrics.GROUP_NAME);
        Map<String, Metric> registeredMetrics = genericMetricGroup.getMetrics();
        assertThat(registeredMetrics.keySet())
                .containsExactlyInAnyOrder(
                        CommitMetrics.LAST_COMMIT_DURATION,
                        CommitMetrics.LAST_COMMIT_ATTEMPTS,
                        CommitMetrics.LAST_GENERATED_SNAPSHOTS,
                        CommitMetrics.LAST_PARTITIONS_WRITTEN,
                        CommitMetrics.LAST_BUCKETS_WRITTEN,
                        CommitMetrics.COMMIT_DURATION,
                        CommitMetrics.TOTAL_TABLE_FILES,
                        CommitMetrics.TOTAL_CHANGELOG_FILES,
                        CommitMetrics.LAST_TABLE_FILES_ADDED,
                        CommitMetrics.LAST_TABLE_FILES_DELETED,
                        CommitMetrics.LAST_TABLE_FILES_APPENDED,
                        CommitMetrics.LAST_TABLE_FILES_COMMIT_COMPACTED,
                        CommitMetrics.LAST_CHANGELOG_FILES_APPENDED,
                        CommitMetrics.LAST_CHANGELOG_FILES_COMMIT_COMPACTED,
                        CommitMetrics.LAST_DELTA_RECORDS_APPENDED,
                        CommitMetrics.LAST_CHANGELOG_RECORDS_APPENDED,
                        CommitMetrics.LAST_DELTA_RECORDS_COMMIT_COMPACTED,
                        CommitMetrics.LAST_CHANGELOG_RECORDS_COMMIT_COMPACTED);

        reportOnce(commitMetrics);
        assertThat(Metrics.getInstance().getMetricGroups().size()).isEqualTo(1);
    }

    @Test
    public void testInitDataFilesCountWithPartition() throws Exception {
        CommitMetrics commitMetrics = getCommitMetricsWithTableWrite(true, "table1");
        commitMetrics.initDataFilesCount();
        assertThat(commitMetrics.getInitTableFilesCount()).isEqualTo(4);
        assertThat(commitMetrics.getInitChangelogFilesCount()).isEqualTo(4);
    }

    @Test
    public void testInitDataFilesCountWithoutPartition() throws Exception {
        CommitMetrics commitMetrics = getCommitMetricsWithTableWrite(false, "table2");
        commitMetrics.initDataFilesCount();
        assertThat(commitMetrics.getInitTableFilesCount()).isEqualTo(2);
        assertThat(commitMetrics.getInitChangelogFilesCount()).isEqualTo(2);
    }

    private CommitMetrics getCommitMetricsWithTableWrite(boolean withPartition, String tableName)
            throws Exception {
        Map<String, String> options = new HashMap<>();
        options.put(BUCKET.key(), "2");
        options.put(BUCKET_KEY.key(), "a");
        options.put(CHANGELOG_PRODUCER.key(), "input");
        Path tablePath = new Path(tempDir.toString(), tableName);
        RowType rowType =
                RowType.of(
                        new DataType[] {DataTypes.INT(), DataTypes.INT(), DataTypes.INT()},
                        new String[] {"pt", "a", "b"});
        List<String> partitionList = new ArrayList<>();
        if (withPartition) {
            partitionList.add("pt");
        }
        TableSchema tableSchema =
                SchemaUtils.forceCommit(
                        new SchemaManager(LocalFileIO.create(), tablePath),
                        new Schema(
                                rowType.getFields(),
                                partitionList,
                                Arrays.asList("pt", "a"),
                                options,
                                ""));
        FileStoreTable table =
                FileStoreTableFactory.create(LocalFileIO.create(), tablePath, tableSchema);
        BatchWriteBuilder writeBuilder = table.newBatchWriteBuilder();
        BatchTableWrite write = writeBuilder.newWrite();
        BatchTableCommit commit = writeBuilder.newCommit();
        write.write(GenericRow.of(1, 1, 2));
        write.write(GenericRow.of(1, 2, 2));
        write.write(GenericRow.of(2, 1, 2));
        write.write(GenericRow.of(2, 2, 2));

        commit.commit(write.prepareCommit());
        write.close();
        commit.close();
        FileStorePathFactory pathFactory =
                new FileStorePathFactory(
                        tablePath,
                        RowType.of(new IntType()),
                        "default",
                        CoreOptions.FILE_FORMAT.defaultValue().toString());
        return new CommitMetrics(pathFactory, LocalFileIO.create());
    }

    /** Tests that the metrics are updated properly. */
    @Test
    public void testMetricsAreUpdated() {
        Map<String, Metric> registeredGenericMetrics =
                commitMetrics.getGenericMetricGroup().getMetrics();

        // Check initial values
        Gauge<Long> lastCommitDuration =
                (Gauge<Long>) registeredGenericMetrics.get(CommitMetrics.LAST_COMMIT_DURATION);
        Histogram commitDuration =
                (Histogram) registeredGenericMetrics.get(CommitMetrics.COMMIT_DURATION);
        Gauge<Long> lastCommitAttempts =
                (Gauge<Long>) registeredGenericMetrics.get(CommitMetrics.LAST_COMMIT_ATTEMPTS);
        Gauge<Long> lastGeneratedSnapshots =
                (Gauge<Long>) registeredGenericMetrics.get(CommitMetrics.LAST_GENERATED_SNAPSHOTS);
        Gauge<Long> lastPartitionsWritten =
                (Gauge<Long>) registeredGenericMetrics.get(CommitMetrics.LAST_PARTITIONS_WRITTEN);
        Gauge<Long> lastBucketsWritten =
                (Gauge<Long>) registeredGenericMetrics.get(CommitMetrics.LAST_BUCKETS_WRITTEN);
        Counter totalTableFiles =
                (Counter) registeredGenericMetrics.get(CommitMetrics.TOTAL_TABLE_FILES);
        Counter totalChangelogFiles =
                (Counter) registeredGenericMetrics.get(CommitMetrics.TOTAL_CHANGELOG_FILES);
        Gauge<Long> lastTableFilesAdded =
                (Gauge<Long>) registeredGenericMetrics.get(CommitMetrics.LAST_TABLE_FILES_ADDED);
        Gauge<Long> lastTableFilesDeleted =
                (Gauge<Long>) registeredGenericMetrics.get(CommitMetrics.LAST_TABLE_FILES_DELETED);

        Gauge<Long> lastTableFilesAppended =
                (Gauge<Long>) registeredGenericMetrics.get(CommitMetrics.LAST_TABLE_FILES_APPENDED);

        Gauge<Long> lastTableFilesCompacted =
                (Gauge<Long>)
                        registeredGenericMetrics.get(
                                CommitMetrics.LAST_TABLE_FILES_COMMIT_COMPACTED);

        Gauge<Long> lastChangelogFilesAppended =
                (Gauge<Long>)
                        registeredGenericMetrics.get(CommitMetrics.LAST_CHANGELOG_FILES_APPENDED);

        Gauge<Long> lastChangelogFilesCompacted =
                (Gauge<Long>)
                        registeredGenericMetrics.get(
                                CommitMetrics.LAST_CHANGELOG_FILES_COMMIT_COMPACTED);

        Gauge<Long> lastDeltaRecordsAppended =
                (Gauge<Long>)
                        registeredGenericMetrics.get(CommitMetrics.LAST_DELTA_RECORDS_APPENDED);

        Gauge<Long> lastChangelogRecordsAppended =
                (Gauge<Long>)
                        registeredGenericMetrics.get(CommitMetrics.LAST_CHANGELOG_RECORDS_APPENDED);

        Gauge<Long> lastDeltaRecordsCompacted =
                (Gauge<Long>)
                        registeredGenericMetrics.get(
                                CommitMetrics.LAST_DELTA_RECORDS_COMMIT_COMPACTED);

        Gauge<Long> lastChangelogRecordsCompacted =
                (Gauge<Long>)
                        registeredGenericMetrics.get(
                                CommitMetrics.LAST_CHANGELOG_RECORDS_COMMIT_COMPACTED);

        assertThat(lastCommitDuration.getValue()).isEqualTo(0);
        assertThat(commitDuration.getCount()).isEqualTo(0);
        assertThat(commitDuration.getStatistics().size()).isEqualTo(0);
        assertThat(lastCommitAttempts.getValue()).isEqualTo(0);
        assertThat(lastGeneratedSnapshots.getValue()).isEqualTo(0);
        assertThat(lastPartitionsWritten.getValue()).isEqualTo(0);
        assertThat(lastBucketsWritten.getValue()).isEqualTo(0);
        assertThat(totalTableFiles.getCount()).isEqualTo(0);
        assertThat(totalChangelogFiles.getCount()).isEqualTo(0);
        assertThat(lastTableFilesAdded.getValue()).isEqualTo(0);
        assertThat(lastTableFilesDeleted.getValue()).isEqualTo(0);
        assertThat(lastTableFilesAppended.getValue()).isEqualTo(0);
        assertThat(lastTableFilesCompacted.getValue()).isEqualTo(0);
        assertThat(lastChangelogFilesAppended.getValue()).isEqualTo(0);
        assertThat(lastChangelogFilesCompacted.getValue()).isEqualTo(0);
        assertThat(lastDeltaRecordsAppended.getValue()).isEqualTo(0);
        assertThat(lastChangelogRecordsAppended.getValue()).isEqualTo(0);
        assertThat(lastDeltaRecordsCompacted.getValue()).isEqualTo(0);
        assertThat(lastChangelogRecordsCompacted.getValue()).isEqualTo(0);

        // report once
        reportOnce(commitMetrics);

        // generic metrics value updated
        assertThat(lastCommitDuration.getValue()).isEqualTo(200);
        assertThat(commitDuration.getCount()).isEqualTo(1);
        assertThat(commitDuration.getStatistics().size()).isEqualTo(1);
        assertThat(commitDuration.getStatistics().getValues()[0]).isEqualTo(200L);
        assertThat(commitDuration.getStatistics().getMin()).isEqualTo(200);
        assertThat(commitDuration.getStatistics().getQuantile(0.5)).isCloseTo(200.0, offset(0.001));
        assertThat(commitDuration.getStatistics().getMean()).isEqualTo(200);
        assertThat(commitDuration.getStatistics().getMax()).isEqualTo(200);
        assertThat(commitDuration.getStatistics().getStdDev()).isEqualTo(0);
        assertThat(lastCommitAttempts.getValue()).isEqualTo(1);
        assertThat(lastGeneratedSnapshots.getValue()).isEqualTo(2);
        assertThat(lastPartitionsWritten.getValue()).isEqualTo(3);
        assertThat(lastBucketsWritten.getValue()).isEqualTo(3);
        assertThat(totalTableFiles.getCount()).isEqualTo(3);
        assertThat(totalChangelogFiles.getCount()).isEqualTo(4);
        assertThat(lastTableFilesAdded.getValue()).isEqualTo(4);
        assertThat(lastTableFilesDeleted.getValue()).isEqualTo(1);
        assertThat(lastTableFilesAppended.getValue()).isEqualTo(2);
        assertThat(lastTableFilesCompacted.getValue()).isEqualTo(3);
        assertThat(lastChangelogFilesAppended.getValue()).isEqualTo(2);
        assertThat(lastChangelogFilesCompacted.getValue()).isEqualTo(2);
        assertThat(lastDeltaRecordsAppended.getValue()).isEqualTo(503);
        assertThat(lastChangelogRecordsAppended.getValue()).isEqualTo(503);
        assertThat(lastDeltaRecordsCompacted.getValue()).isEqualTo(613);
        assertThat(lastChangelogRecordsCompacted.getValue()).isEqualTo(512);

        // report again
        reportAgain(commitMetrics);

        // generic metrics value updated
        assertThat(lastCommitDuration.getValue()).isEqualTo(500);
        assertThat(commitDuration.getCount()).isEqualTo(2);
        assertThat(commitDuration.getStatistics().size()).isEqualTo(2);
        assertThat(commitDuration.getStatistics().getValues()[1]).isEqualTo(500L);
        assertThat(commitDuration.getStatistics().getMin()).isEqualTo(200);
        assertThat(commitDuration.getStatistics().getQuantile(0.5)).isCloseTo(350.0, offset(0.001));
        assertThat(commitDuration.getStatistics().getMean()).isEqualTo(350);
        assertThat(commitDuration.getStatistics().getMax()).isEqualTo(500);
        assertThat(commitDuration.getStatistics().getStdDev()).isCloseTo(212.132, offset(0.001));
        assertThat(lastCommitAttempts.getValue()).isEqualTo(2);
        assertThat(lastGeneratedSnapshots.getValue()).isEqualTo(1);
        assertThat(lastPartitionsWritten.getValue()).isEqualTo(2);
        assertThat(lastBucketsWritten.getValue()).isEqualTo(3);
        assertThat(totalTableFiles.getCount()).isEqualTo(6);
        assertThat(totalChangelogFiles.getCount()).isEqualTo(8);
        assertThat(lastTableFilesAdded.getValue()).isEqualTo(4);
        assertThat(lastTableFilesDeleted.getValue()).isEqualTo(1);
        assertThat(lastTableFilesAppended.getValue()).isEqualTo(2);
        assertThat(lastTableFilesCompacted.getValue()).isEqualTo(3);
        assertThat(lastChangelogFilesAppended.getValue()).isEqualTo(2);
        assertThat(lastChangelogFilesCompacted.getValue()).isEqualTo(2);
        assertThat(lastDeltaRecordsAppended.getValue()).isEqualTo(805);
        assertThat(lastChangelogRecordsAppended.getValue()).isEqualTo(213);
        assertThat(lastDeltaRecordsCompacted.getValue()).isEqualTo(506);
        assertThat(lastChangelogRecordsCompacted.getValue()).isEqualTo(601);
    }

    private void reportOnce(CommitMetrics commitMetrics) {
        List<ManifestEntry> appendTableFiles = new ArrayList<>();
        List<ManifestEntry> appendChangelogFiles = new ArrayList<>();
        List<ManifestEntry> compactTableFiles = new ArrayList<>();
        List<ManifestEntry> compactChangelogFiles = new ArrayList<>();

        appendTableFiles.add(makeEntry(FileKind.ADD, 1, 1, 201));
        appendTableFiles.add(makeEntry(FileKind.ADD, 2, 3, 302));
        appendChangelogFiles.add(makeEntry(FileKind.ADD, 1, 1, 202));
        appendChangelogFiles.add(makeEntry(FileKind.ADD, 2, 3, 301));
        compactTableFiles.add(makeEntry(FileKind.ADD, 1, 1, 203));
        compactTableFiles.add(makeEntry(FileKind.ADD, 2, 3, 304));
        compactTableFiles.add(makeEntry(FileKind.DELETE, 3, 5, 106));
        compactChangelogFiles.add(makeEntry(FileKind.ADD, 1, 1, 205));
        compactChangelogFiles.add(makeEntry(FileKind.ADD, 2, 3, 307));

        CommitStats commitStats =
                new CommitStats(
                        appendTableFiles,
                        appendChangelogFiles,
                        compactTableFiles,
                        compactChangelogFiles,
                        200,
                        2,
                        1);

        commitMetrics.reportCommit(commitStats);
    }

    private void reportAgain(CommitMetrics commitMetrics) {
        List<ManifestEntry> appendTableFiles = new ArrayList<>();
        List<ManifestEntry> appendChangelogFiles = new ArrayList<>();
        List<ManifestEntry> compactTableFiles = new ArrayList<>();
        List<ManifestEntry> compactChangelogFiles = new ArrayList<>();

        appendTableFiles.add(makeEntry(FileKind.ADD, 1, 1, 400));
        appendTableFiles.add(makeEntry(FileKind.ADD, 3, 4, 405));
        appendChangelogFiles.add(makeEntry(FileKind.ADD, 1, 1, 102));
        appendChangelogFiles.add(makeEntry(FileKind.ADD, 3, 4, 111));
        compactTableFiles.add(makeEntry(FileKind.ADD, 1, 1, 200));
        compactTableFiles.add(makeEntry(FileKind.ADD, 3, 4, 201));
        compactTableFiles.add(makeEntry(FileKind.DELETE, 3, 5, 105));
        compactChangelogFiles.add(makeEntry(FileKind.ADD, 1, 1, 300));
        compactChangelogFiles.add(makeEntry(FileKind.ADD, 3, 4, 301));

        CommitStats commitStats =
                new CommitStats(
                        appendTableFiles,
                        appendChangelogFiles,
                        compactTableFiles,
                        compactChangelogFiles,
                        500,
                        1,
                        2);

        commitMetrics.reportCommit(commitStats);
    }

    private CommitMetrics getCommitMetrics() {
        Path path = new Path(tempDir.toString(), TABLE_NAME);
        FileStorePathFactory pathFactory =
                new FileStorePathFactory(
                        path,
                        RowType.of(new IntType()),
                        "default",
                        CoreOptions.FILE_FORMAT.defaultValue().toString());
        return new CommitMetrics(pathFactory, LocalFileIO.create());
    }
}
