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

package org.apache.paimon.utils;

import org.apache.paimon.Snapshot;
import org.apache.paimon.fs.FileIO;
import org.apache.paimon.fs.FileStatus;
import org.apache.paimon.fs.Path;
import org.apache.paimon.manifest.ManifestEntry;
import org.apache.paimon.operation.TagDeletion;
import org.apache.paimon.table.sink.TagCallback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.apache.paimon.utils.FileUtils.listVersionedFileStatus;
import static org.apache.paimon.utils.Preconditions.checkArgument;

/** Manager for {@code Tag}. */
public class TagManager {

    private static final Logger LOG = LoggerFactory.getLogger(TagManager.class);

    private static final String TAG_PREFIX = "tag-";

    private final FileIO fileIO;
    private final Path tablePath;

    public TagManager(FileIO fileIO, Path tablePath) {
        this.fileIO = fileIO;
        this.tablePath = tablePath;
    }

    /** Return the root Directory of tags. */
    public Path tagDirectory() {
        return new Path(tablePath + "/tag");
    }

    /** Return the path of a tag. */
    public Path tagPath(String tagName) {
        return new Path(tablePath + "/tag/" + TAG_PREFIX + tagName);
    }

    /** Create a tag from given snapshot and save it in the storage. */
    public void createTag(Snapshot snapshot, String tagName, List<TagCallback> callbacks) {
        checkArgument(!StringUtils.isBlank(tagName), "Tag name '%s' is blank.", tagName);
        checkArgument(!tagExists(tagName), "Tag name '%s' already exists.", tagName);
        checkArgument(
                !tagName.chars().allMatch(Character::isDigit),
                "Tag name cannot be pure numeric string but is '%s'.",
                tagName);

        Path newTagPath = tagPath(tagName);
        try {
            fileIO.writeFileUtf8(newTagPath, snapshot.toJson());
        } catch (IOException e) {
            throw new RuntimeException(
                    String.format(
                            "Exception occurs when committing tag '%s' (path %s). "
                                    + "Cannot clean up because we can't determine the success.",
                            tagName, newTagPath),
                    e);
        }

        try {
            callbacks.forEach(callback -> callback.notifyCreation(tagName));
        } finally {
            for (TagCallback tagCallback : callbacks) {
                IOUtils.closeQuietly(tagCallback);
            }
        }
    }

    public void deleteTag(
            String tagName, TagDeletion tagDeletion, SnapshotManager snapshotManager) {
        checkArgument(!StringUtils.isBlank(tagName), "Tag name '%s' is blank.", tagName);
        checkArgument(tagExists(tagName), "Tag '%s' doesn't exist.", tagName);

        Snapshot taggedSnapshot = taggedSnapshot(tagName);
        List<Snapshot> taggedSnapshots;

        // skip file deletion if snapshot exists
        if (snapshotManager.snapshotExists(taggedSnapshot.id())) {
            fileIO.deleteQuietly(tagPath(tagName));
            return;
        } else {
            // FileIO discovers tags by tag file, so we should read all tags before we delete tag
            taggedSnapshots = taggedSnapshots();
            fileIO.deleteQuietly(tagPath(tagName));
        }

        // collect skipping sets from the left neighbor tag and the nearest right neighbor (either
        // the earliest snapshot or right neighbor tag)
        List<Snapshot> skippedSnapshots = new ArrayList<>();

        int index = findIndex(taggedSnapshot, taggedSnapshots);
        // the left neighbor tag
        if (index - 1 >= 0) {
            skippedSnapshots.add(taggedSnapshots.get(index - 1));
        }
        // the nearest right neighbor
        Snapshot right = snapshotManager.earliestSnapshot();
        if (index + 1 < taggedSnapshots.size()) {
            Snapshot rightTag = taggedSnapshots.get(index + 1);
            right = right.id() < rightTag.id() ? right : rightTag;
        }
        skippedSnapshots.add(right);

        // delete data files and empty directories
        Predicate<ManifestEntry> dataFileSkipper = null;
        boolean success = true;
        try {
            dataFileSkipper = tagDeletion.dataFileSkipper(skippedSnapshots);
        } catch (Exception e) {
            LOG.info(
                    String.format(
                            "Skip cleaning data files of tag '%s' due to failed to build skipping set.",
                            tagName),
                    e);
            success = false;
        }
        if (success) {
            tagDeletion.cleanUnusedDataFiles(taggedSnapshot, dataFileSkipper);
            tagDeletion.cleanDataDirectories();
        }

        // delete manifests
        tagDeletion.cleanUnusedManifests(
                taggedSnapshot, tagDeletion.manifestSkippingSet(skippedSnapshots));
    }

    /** Check if a tag exists. */
    public boolean tagExists(String tagName) {
        Path path = tagPath(tagName);
        try {
            return fileIO.exists(path);
        } catch (IOException e) {
            throw new RuntimeException(
                    String.format(
                            "Failed to determine if tag '%s' exists in path %s.", tagName, path),
                    e);
        }
    }

    /** Get the tagged snapshot by name. */
    public Snapshot taggedSnapshot(String tagName) {
        checkArgument(tagExists(tagName), "Tag '%s' doesn't exist.", tagName);
        return Snapshot.fromPath(fileIO, tagPath(tagName));
    }

    public long tagCount() {
        try {
            return listVersionedFileStatus(fileIO, tagDirectory(), TAG_PREFIX).count();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Get all tagged snapshots sorted by snapshot id. */
    public List<Snapshot> taggedSnapshots() {
        return new ArrayList<>(tags().keySet());
    }

    /** Get all tagged snapshots with names sorted by snapshot id. */
    public SortedMap<Snapshot, String> tags() {
        return tags(tagName -> true);
    }

    /**
     * Retrieves a sorted map of snapshots filtered based on a provided predicate. The predicate
     * determines which tag names should be included in the result. Only snapshots with tag names
     * that pass the predicate test are included.
     *
     * @param filter A Predicate that tests each tag name. Snapshots with tag names that fail the
     *     test are excluded from the result.
     * @return A sorted map of filtered snapshots keyed by their IDs, each associated with its tag
     *     name.
     * @throws RuntimeException if an IOException occurs during retrieval of snapshots.
     */
    public SortedMap<Snapshot, String> tags(Predicate<String> filter) {
        TreeMap<Snapshot, String> tags = new TreeMap<>(Comparator.comparingLong(Snapshot::id));
        try {
            List<Path> paths =
                    listVersionedFileStatus(fileIO, tagDirectory(), TAG_PREFIX)
                            .map(FileStatus::getPath)
                            .collect(Collectors.toList());

            for (Path path : paths) {
                String tagName = path.getName().substring(TAG_PREFIX.length());

                if (!filter.test(tagName)) {
                    continue;
                }
                // If the tag file is not found, it might be deleted by
                // other processes, so just skip this tag
                Snapshot.safelyFromPath(fileIO, path)
                        .ifPresent(snapshot -> tags.put(snapshot, tagName));
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return tags;
    }

    private int findIndex(Snapshot taggedSnapshot, List<Snapshot> taggedSnapshots) {
        for (int i = 0; i < taggedSnapshots.size(); i++) {
            if (taggedSnapshot.id() == taggedSnapshots.get(i).id()) {
                return i;
            }
        }
        throw new RuntimeException(
                String.format(
                        "Didn't find tag with snapshot id '%s'.This is unexpected.",
                        taggedSnapshot.id()));
    }

    public static List<Snapshot> findOverlappedSnapshots(
            List<Snapshot> taggedSnapshots, long beginInclusive, long endExclusive) {
        List<Snapshot> snapshots = new ArrayList<>();
        int right = findPreviousTag(taggedSnapshots, endExclusive);
        if (right >= 0) {
            int left = Math.max(findPreviousOrEqualTag(taggedSnapshots, beginInclusive), 0);
            for (int i = left; i <= right; i++) {
                snapshots.add(taggedSnapshots.get(i));
            }
        }
        return snapshots;
    }

    public static int findPreviousTag(List<Snapshot> taggedSnapshots, long targetSnapshotId) {
        for (int i = taggedSnapshots.size() - 1; i >= 0; i--) {
            if (taggedSnapshots.get(i).id() < targetSnapshotId) {
                return i;
            }
        }
        return -1;
    }

    private static int findPreviousOrEqualTag(
            List<Snapshot> taggedSnapshots, long targetSnapshotId) {
        for (int i = taggedSnapshots.size() - 1; i >= 0; i--) {
            if (taggedSnapshots.get(i).id() <= targetSnapshotId) {
                return i;
            }
        }
        return -1;
    }
}
