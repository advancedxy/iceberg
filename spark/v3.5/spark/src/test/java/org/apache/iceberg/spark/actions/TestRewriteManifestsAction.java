/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg.spark.actions;

import static org.apache.iceberg.ValidationHelpers.dataSeqs;
import static org.apache.iceberg.ValidationHelpers.fileSeqs;
import static org.apache.iceberg.ValidationHelpers.files;
import static org.apache.iceberg.ValidationHelpers.snapshotIds;
import static org.apache.iceberg.ValidationHelpers.validateDataManifest;
import static org.apache.iceberg.types.Types.NestedField.optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.ManifestFiles;
import org.apache.iceberg.ManifestWriter;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.StructLike;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.TestHelpers;
import org.apache.iceberg.actions.RewriteManifests;
import org.apache.iceberg.exceptions.CommitStateUnknownException;
import org.apache.iceberg.hadoop.HadoopTables;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.relocated.com.google.common.collect.Iterables;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.relocated.com.google.common.collect.Maps;
import org.apache.iceberg.spark.SparkTableUtil;
import org.apache.iceberg.spark.SparkTestBase;
import org.apache.iceberg.spark.SparkWriteOptions;
import org.apache.iceberg.spark.source.ThreeColumnRecord;
import org.apache.iceberg.types.Types;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Encoders;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.catalyst.TableIdentifier;
import org.assertj.core.api.Assertions;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class TestRewriteManifestsAction extends SparkTestBase {

  private static final HadoopTables TABLES = new HadoopTables(new Configuration());
  private static final Schema SCHEMA =
      new Schema(
          optional(1, "c1", Types.IntegerType.get()),
          optional(2, "c2", Types.StringType.get()),
          optional(3, "c3", Types.StringType.get()));

  @Parameters(name = "snapshotIdInheritanceEnabled = {0}, useCaching = {1}, formatVersion = {2}")
  public static Object[] parameters() {
    return new Object[][] {
      new Object[] {"true", "true", 1},
      new Object[] {"false", "true", 1},
      new Object[] {"true", "false", 2},
      new Object[] {"false", "false", 2}
    };
  }

  @Rule public TemporaryFolder temp = new TemporaryFolder();

  private final String snapshotIdInheritanceEnabled;
  private final String useCaching;
  private final int formatVersion;
  private final boolean shouldStageManifests;
  private String tableLocation = null;

  public TestRewriteManifestsAction(
      String snapshotIdInheritanceEnabled, String useCaching, int formatVersion) {
    this.snapshotIdInheritanceEnabled = snapshotIdInheritanceEnabled;
    this.useCaching = useCaching;
    this.formatVersion = formatVersion;
    this.shouldStageManifests = formatVersion == 1 && snapshotIdInheritanceEnabled.equals("false");
  }

  @Before
  public void setupTableLocation() throws Exception {
    File tableDir = temp.newFolder();
    this.tableLocation = tableDir.toURI().toString();
  }

  @Test
  public void testRewriteManifestsEmptyTable() throws IOException {
    PartitionSpec spec = PartitionSpec.unpartitioned();
    Map<String, String> options = Maps.newHashMap();
    options.put(TableProperties.FORMAT_VERSION, String.valueOf(formatVersion));
    options.put(TableProperties.SNAPSHOT_ID_INHERITANCE_ENABLED, snapshotIdInheritanceEnabled);
    Table table = TABLES.create(SCHEMA, spec, options, tableLocation);

    Assert.assertNull("Table must be empty", table.currentSnapshot());

    SparkActions actions = SparkActions.get();

    actions
        .rewriteManifests(table)
        .rewriteIf(manifest -> true)
        .option(RewriteManifestsSparkAction.USE_CACHING, useCaching)
        .stagingLocation(temp.newFolder().toString())
        .execute();

    Assert.assertNull("Table must stay empty", table.currentSnapshot());
  }

  @Test
  public void testRewriteSmallManifestsNonPartitionedTable() {
    PartitionSpec spec = PartitionSpec.unpartitioned();
    Map<String, String> options = Maps.newHashMap();
    options.put(TableProperties.FORMAT_VERSION, String.valueOf(formatVersion));
    options.put(TableProperties.SNAPSHOT_ID_INHERITANCE_ENABLED, snapshotIdInheritanceEnabled);
    Table table = TABLES.create(SCHEMA, spec, options, tableLocation);

    List<ThreeColumnRecord> records1 =
        Lists.newArrayList(
            new ThreeColumnRecord(1, null, "AAAA"), new ThreeColumnRecord(1, "BBBBBBBBBB", "BBBB"));
    writeRecords(records1);

    List<ThreeColumnRecord> records2 =
        Lists.newArrayList(
            new ThreeColumnRecord(2, "CCCCCCCCCC", "CCCC"),
            new ThreeColumnRecord(2, "DDDDDDDDDD", "DDDD"));
    writeRecords(records2);

    table.refresh();

    List<ManifestFile> manifests = table.currentSnapshot().allManifests(table.io());
    Assert.assertEquals("Should have 2 manifests before rewrite", 2, manifests.size());

    SparkActions actions = SparkActions.get();

    RewriteManifests.Result result =
        actions
            .rewriteManifests(table)
            .rewriteIf(manifest -> true)
            .option(RewriteManifestsSparkAction.USE_CACHING, useCaching)
            .execute();

    Assert.assertEquals(
        "Action should rewrite 2 manifests", 2, Iterables.size(result.rewrittenManifests()));
    Assert.assertEquals(
        "Action should add 1 manifests", 1, Iterables.size(result.addedManifests()));
    assertManifestsLocation(result.addedManifests());

    table.refresh();

    List<ManifestFile> newManifests = table.currentSnapshot().allManifests(table.io());
    Assert.assertEquals("Should have 1 manifests after rewrite", 1, newManifests.size());

    Assert.assertEquals(4, (long) newManifests.get(0).existingFilesCount());
    Assert.assertFalse(newManifests.get(0).hasAddedFiles());
    Assert.assertFalse(newManifests.get(0).hasDeletedFiles());

    List<ThreeColumnRecord> expectedRecords = Lists.newArrayList();
    expectedRecords.addAll(records1);
    expectedRecords.addAll(records2);

    Dataset<Row> resultDF = spark.read().format("iceberg").load(tableLocation);
    List<ThreeColumnRecord> actualRecords =
        resultDF.sort("c1", "c2").as(Encoders.bean(ThreeColumnRecord.class)).collectAsList();

    Assert.assertEquals("Rows must match", expectedRecords, actualRecords);
  }

  @Test
  public void testRewriteManifestsWithCommitStateUnknownException() {
    PartitionSpec spec = PartitionSpec.unpartitioned();
    Map<String, String> options = Maps.newHashMap();
    options.put(TableProperties.FORMAT_VERSION, String.valueOf(formatVersion));
    options.put(TableProperties.SNAPSHOT_ID_INHERITANCE_ENABLED, snapshotIdInheritanceEnabled);
    Table table = TABLES.create(SCHEMA, spec, options, tableLocation);

    List<ThreeColumnRecord> records1 =
        Lists.newArrayList(
            new ThreeColumnRecord(1, null, "AAAA"), new ThreeColumnRecord(1, "BBBBBBBBBB", "BBBB"));
    writeRecords(records1);

    List<ThreeColumnRecord> records2 =
        Lists.newArrayList(
            new ThreeColumnRecord(2, "CCCCCCCCCC", "CCCC"),
            new ThreeColumnRecord(2, "DDDDDDDDDD", "DDDD"));
    writeRecords(records2);

    table.refresh();

    List<ManifestFile> manifests = table.currentSnapshot().allManifests(table.io());
    Assert.assertEquals("Should have 2 manifests before rewrite", 2, manifests.size());

    SparkActions actions = SparkActions.get();

    // create a spy which would throw a CommitStateUnknownException after successful commit.
    org.apache.iceberg.RewriteManifests newRewriteManifests = table.rewriteManifests();
    org.apache.iceberg.RewriteManifests spyNewRewriteManifests = spy(newRewriteManifests);
    doAnswer(
            invocation -> {
              newRewriteManifests.commit();
              throw new CommitStateUnknownException(new RuntimeException("Datacenter on Fire"));
            })
        .when(spyNewRewriteManifests)
        .commit();

    Table spyTable = spy(table);
    when(spyTable.rewriteManifests()).thenReturn(spyNewRewriteManifests);

    Assertions.assertThatThrownBy(
            () -> actions.rewriteManifests(spyTable).rewriteIf(manifest -> true).execute())
        .cause()
        .isInstanceOf(RuntimeException.class)
        .hasMessage("Datacenter on Fire");

    table.refresh();

    // table should reflect the changes, since the commit was successful
    List<ManifestFile> newManifests = table.currentSnapshot().allManifests(table.io());
    Assert.assertEquals("Should have 1 manifests after rewrite", 1, newManifests.size());

    Assert.assertEquals(4, (long) newManifests.get(0).existingFilesCount());
    Assert.assertFalse(newManifests.get(0).hasAddedFiles());
    Assert.assertFalse(newManifests.get(0).hasDeletedFiles());

    List<ThreeColumnRecord> expectedRecords = Lists.newArrayList();
    expectedRecords.addAll(records1);
    expectedRecords.addAll(records2);

    Dataset<Row> resultDF = spark.read().format("iceberg").load(tableLocation);
    List<ThreeColumnRecord> actualRecords =
        resultDF.sort("c1", "c2").as(Encoders.bean(ThreeColumnRecord.class)).collectAsList();

    Assert.assertEquals("Rows must match", expectedRecords, actualRecords);
  }

  @Test
  public void testRewriteSmallManifestsPartitionedTable() {
    PartitionSpec spec = PartitionSpec.builderFor(SCHEMA).identity("c1").truncate("c2", 2).build();
    Map<String, String> options = Maps.newHashMap();
    options.put(TableProperties.FORMAT_VERSION, String.valueOf(formatVersion));
    options.put(TableProperties.SNAPSHOT_ID_INHERITANCE_ENABLED, snapshotIdInheritanceEnabled);
    Table table = TABLES.create(SCHEMA, spec, options, tableLocation);

    List<ThreeColumnRecord> records1 =
        Lists.newArrayList(
            new ThreeColumnRecord(1, null, "AAAA"), new ThreeColumnRecord(1, "BBBBBBBBBB", "BBBB"));
    writeRecords(records1);

    List<ThreeColumnRecord> records2 =
        Lists.newArrayList(
            new ThreeColumnRecord(2, "CCCCCCCCCC", "CCCC"),
            new ThreeColumnRecord(2, "DDDDDDDDDD", "DDDD"));
    writeRecords(records2);

    List<ThreeColumnRecord> records3 =
        Lists.newArrayList(
            new ThreeColumnRecord(3, "EEEEEEEEEE", "EEEE"),
            new ThreeColumnRecord(3, "FFFFFFFFFF", "FFFF"));
    writeRecords(records3);

    List<ThreeColumnRecord> records4 =
        Lists.newArrayList(
            new ThreeColumnRecord(4, "GGGGGGGGGG", "GGGG"),
            new ThreeColumnRecord(4, "HHHHHHHHHG", "HHHH"));
    writeRecords(records4);

    table.refresh();

    List<ManifestFile> manifests = table.currentSnapshot().allManifests(table.io());
    Assert.assertEquals("Should have 4 manifests before rewrite", 4, manifests.size());

    SparkActions actions = SparkActions.get();

    // we will expect to have 2 manifests with 4 entries in each after rewrite
    long manifestEntrySizeBytes = computeManifestEntrySizeBytes(manifests);
    long targetManifestSizeBytes = (long) (1.05 * 4 * manifestEntrySizeBytes);

    table
        .updateProperties()
        .set(TableProperties.MANIFEST_TARGET_SIZE_BYTES, String.valueOf(targetManifestSizeBytes))
        .commit();

    RewriteManifests.Result result =
        actions
            .rewriteManifests(table)
            .rewriteIf(manifest -> true)
            .option(RewriteManifestsSparkAction.USE_CACHING, useCaching)
            .execute();

    Assert.assertEquals(
        "Action should rewrite 4 manifests", 4, Iterables.size(result.rewrittenManifests()));
    Assert.assertEquals(
        "Action should add 2 manifests", 2, Iterables.size(result.addedManifests()));
    assertManifestsLocation(result.addedManifests());

    table.refresh();

    List<ManifestFile> newManifests = table.currentSnapshot().allManifests(table.io());
    Assert.assertEquals("Should have 2 manifests after rewrite", 2, newManifests.size());

    Assert.assertEquals(4, (long) newManifests.get(0).existingFilesCount());
    Assert.assertFalse(newManifests.get(0).hasAddedFiles());
    Assert.assertFalse(newManifests.get(0).hasDeletedFiles());

    Assert.assertEquals(4, (long) newManifests.get(1).existingFilesCount());
    Assert.assertFalse(newManifests.get(1).hasAddedFiles());
    Assert.assertFalse(newManifests.get(1).hasDeletedFiles());

    List<ThreeColumnRecord> expectedRecords = Lists.newArrayList();
    expectedRecords.addAll(records1);
    expectedRecords.addAll(records2);
    expectedRecords.addAll(records3);
    expectedRecords.addAll(records4);

    Dataset<Row> resultDF = spark.read().format("iceberg").load(tableLocation);
    List<ThreeColumnRecord> actualRecords =
        resultDF.sort("c1", "c2").as(Encoders.bean(ThreeColumnRecord.class)).collectAsList();

    Assert.assertEquals("Rows must match", expectedRecords, actualRecords);
  }

  @Test
  public void testRewriteImportedManifests() throws IOException {
    PartitionSpec spec = PartitionSpec.builderFor(SCHEMA).identity("c3").build();
    Map<String, String> options = Maps.newHashMap();
    options.put(TableProperties.FORMAT_VERSION, String.valueOf(formatVersion));
    options.put(TableProperties.SNAPSHOT_ID_INHERITANCE_ENABLED, snapshotIdInheritanceEnabled);
    Table table = TABLES.create(SCHEMA, spec, options, tableLocation);

    List<ThreeColumnRecord> records =
        Lists.newArrayList(
            new ThreeColumnRecord(1, null, "AAAA"), new ThreeColumnRecord(1, "BBBBBBBBBB", "BBBB"));
    File parquetTableDir = temp.newFolder("parquet_table");
    String parquetTableLocation = parquetTableDir.toURI().toString();

    try {
      Dataset<Row> inputDF = spark.createDataFrame(records, ThreeColumnRecord.class);
      inputDF
          .select("c1", "c2", "c3")
          .write()
          .format("parquet")
          .mode("overwrite")
          .option("path", parquetTableLocation)
          .partitionBy("c3")
          .saveAsTable("parquet_table");

      File stagingDir = temp.newFolder("staging-dir");
      SparkTableUtil.importSparkTable(
          spark, new TableIdentifier("parquet_table"), table, stagingDir.toString());

      // add some more data to create more than one manifest for the rewrite
      inputDF.select("c1", "c2", "c3").write().format("iceberg").mode("append").save(tableLocation);
      table.refresh();

      Snapshot snapshot = table.currentSnapshot();

      SparkActions actions = SparkActions.get();

      String rewriteStagingLocation = temp.newFolder().toString();

      RewriteManifests.Result result =
          actions
              .rewriteManifests(table)
              .rewriteIf(manifest -> true)
              .option(RewriteManifestsSparkAction.USE_CACHING, useCaching)
              .stagingLocation(rewriteStagingLocation)
              .execute();

      Assert.assertEquals(
          "Action should rewrite all manifests",
          snapshot.allManifests(table.io()),
          result.rewrittenManifests());
      Assert.assertEquals(
          "Action should add 1 manifest", 1, Iterables.size(result.addedManifests()));
      assertManifestsLocation(result.addedManifests(), rewriteStagingLocation);

    } finally {
      spark.sql("DROP TABLE parquet_table");
    }
  }

  @Test
  public void testRewriteLargeManifestsPartitionedTable() throws IOException {
    PartitionSpec spec = PartitionSpec.builderFor(SCHEMA).identity("c3").build();
    Map<String, String> options = Maps.newHashMap();
    options.put(TableProperties.FORMAT_VERSION, String.valueOf(formatVersion));
    options.put(TableProperties.SNAPSHOT_ID_INHERITANCE_ENABLED, snapshotIdInheritanceEnabled);
    Table table = TABLES.create(SCHEMA, spec, options, tableLocation);

    List<DataFile> dataFiles = Lists.newArrayList();
    for (int fileOrdinal = 0; fileOrdinal < 1000; fileOrdinal++) {
      dataFiles.add(newDataFile(table, "c3=" + fileOrdinal));
    }
    ManifestFile appendManifest = writeManifest(table, dataFiles);
    table.newFastAppend().appendManifest(appendManifest).commit();

    List<ManifestFile> manifests = table.currentSnapshot().allManifests(table.io());
    Assert.assertEquals("Should have 1 manifests before rewrite", 1, manifests.size());

    // set the target manifest size to a small value to force splitting records into multiple files
    table
        .updateProperties()
        .set(
            TableProperties.MANIFEST_TARGET_SIZE_BYTES,
            String.valueOf(manifests.get(0).length() / 2))
        .commit();

    SparkActions actions = SparkActions.get();

    String stagingLocation = temp.newFolder().toString();

    RewriteManifests.Result result =
        actions
            .rewriteManifests(table)
            .rewriteIf(manifest -> true)
            .option(RewriteManifestsSparkAction.USE_CACHING, useCaching)
            .stagingLocation(stagingLocation)
            .execute();

    Assertions.assertThat(result.rewrittenManifests()).hasSize(1);
    Assertions.assertThat(result.addedManifests()).hasSizeGreaterThanOrEqualTo(2);
    assertManifestsLocation(result.addedManifests(), stagingLocation);

    table.refresh();

    List<ManifestFile> newManifests = table.currentSnapshot().allManifests(table.io());
    Assertions.assertThat(newManifests).hasSizeGreaterThanOrEqualTo(2);
  }

  @Test
  public void testRewriteManifestsWithPredicate() throws IOException {
    PartitionSpec spec = PartitionSpec.builderFor(SCHEMA).identity("c1").truncate("c2", 2).build();
    Map<String, String> options = Maps.newHashMap();
    options.put(TableProperties.FORMAT_VERSION, String.valueOf(formatVersion));
    options.put(TableProperties.SNAPSHOT_ID_INHERITANCE_ENABLED, snapshotIdInheritanceEnabled);
    Table table = TABLES.create(SCHEMA, spec, options, tableLocation);

    List<ThreeColumnRecord> records1 =
        Lists.newArrayList(
            new ThreeColumnRecord(1, null, "AAAA"), new ThreeColumnRecord(1, "BBBBBBBBBB", "BBBB"));
    writeRecords(records1);

    writeRecords(records1);

    List<ThreeColumnRecord> records2 =
        Lists.newArrayList(
            new ThreeColumnRecord(2, "CCCCCCCCCC", "CCCC"),
            new ThreeColumnRecord(2, "DDDDDDDDDD", "DDDD"));
    writeRecords(records2);

    table.refresh();

    List<ManifestFile> manifests = table.currentSnapshot().allManifests(table.io());
    Assert.assertEquals("Should have 3 manifests before rewrite", 3, manifests.size());

    SparkActions actions = SparkActions.get();

    String stagingLocation = temp.newFolder().toString();

    // rewrite only the first manifest
    RewriteManifests.Result result =
        actions
            .rewriteManifests(table)
            .rewriteIf(
                manifest ->
                    (manifest.path().equals(manifests.get(0).path())
                        || (manifest.path().equals(manifests.get(1).path()))))
            .stagingLocation(stagingLocation)
            .option(RewriteManifestsSparkAction.USE_CACHING, useCaching)
            .execute();

    Assert.assertEquals(
        "Action should rewrite 2 manifest", 2, Iterables.size(result.rewrittenManifests()));
    Assert.assertEquals(
        "Action should add 1 manifests", 1, Iterables.size(result.addedManifests()));
    assertManifestsLocation(result.addedManifests(), stagingLocation);

    table.refresh();

    List<ManifestFile> newManifests = table.currentSnapshot().allManifests(table.io());
    Assert.assertEquals("Should have 2 manifests after rewrite", 2, newManifests.size());

    Assert.assertFalse("First manifest must be rewritten", newManifests.contains(manifests.get(0)));
    Assert.assertFalse(
        "Second manifest must be rewritten", newManifests.contains(manifests.get(1)));
    Assert.assertTrue(
        "Third manifest must not be rewritten", newManifests.contains(manifests.get(2)));

    List<ThreeColumnRecord> expectedRecords = Lists.newArrayList();
    expectedRecords.add(records1.get(0));
    expectedRecords.add(records1.get(0));
    expectedRecords.add(records1.get(1));
    expectedRecords.add(records1.get(1));
    expectedRecords.addAll(records2);

    Dataset<Row> resultDF = spark.read().format("iceberg").load(tableLocation);
    List<ThreeColumnRecord> actualRecords =
        resultDF.sort("c1", "c2").as(Encoders.bean(ThreeColumnRecord.class)).collectAsList();

    Assert.assertEquals("Rows must match", expectedRecords, actualRecords);
  }

  @Test
  public void testRewriteSmallManifestsNonPartitionedV2Table() {
    assumeThat(formatVersion).isGreaterThan(1);

    PartitionSpec spec = PartitionSpec.unpartitioned();
    Map<String, String> properties = ImmutableMap.of(TableProperties.FORMAT_VERSION, "2");
    Table table = TABLES.create(SCHEMA, spec, properties, tableLocation);

    List<ThreeColumnRecord> records1 = Lists.newArrayList(new ThreeColumnRecord(1, null, "AAAA"));
    writeRecords(records1);

    table.refresh();

    Snapshot snapshot1 = table.currentSnapshot();
    DataFile file1 = Iterables.getOnlyElement(snapshot1.addedDataFiles(table.io()));

    List<ThreeColumnRecord> records2 = Lists.newArrayList(new ThreeColumnRecord(2, "CCCC", "CCCC"));
    writeRecords(records2);

    table.refresh();

    Snapshot snapshot2 = table.currentSnapshot();
    DataFile file2 = Iterables.getOnlyElement(snapshot2.addedDataFiles(table.io()));

    List<ManifestFile> manifests = table.currentSnapshot().allManifests(table.io());
    Assert.assertEquals("Should have 2 manifests before rewrite", 2, manifests.size());

    SparkActions actions = SparkActions.get();
    RewriteManifests.Result result =
        actions
            .rewriteManifests(table)
            .option(RewriteManifestsSparkAction.USE_CACHING, useCaching)
            .execute();
    Assert.assertEquals(
        "Action should rewrite 2 manifests", 2, Iterables.size(result.rewrittenManifests()));
    Assert.assertEquals(
        "Action should add 1 manifests", 1, Iterables.size(result.addedManifests()));
    assertManifestsLocation(result.addedManifests());

    table.refresh();

    List<ManifestFile> newManifests = table.currentSnapshot().allManifests(table.io());
    Assert.assertEquals("Should have 1 manifests after rewrite", 1, newManifests.size());

    ManifestFile newManifest = Iterables.getOnlyElement(newManifests);
    Assert.assertEquals(2, (long) newManifest.existingFilesCount());
    Assert.assertFalse(newManifest.hasAddedFiles());
    Assert.assertFalse(newManifest.hasDeletedFiles());

    validateDataManifest(
        table,
        newManifest,
        dataSeqs(1L, 2L),
        fileSeqs(1L, 2L),
        snapshotIds(snapshot1.snapshotId(), snapshot2.snapshotId()),
        files(file1, file2));

    List<ThreeColumnRecord> expectedRecords = Lists.newArrayList();
    expectedRecords.addAll(records1);
    expectedRecords.addAll(records2);

    Dataset<Row> resultDF = spark.read().format("iceberg").load(tableLocation);
    List<ThreeColumnRecord> actualRecords =
        resultDF.sort("c1", "c2").as(Encoders.bean(ThreeColumnRecord.class)).collectAsList();

    Assert.assertEquals("Rows must match", expectedRecords, actualRecords);
  }

  @Test
  public void testRewriteLargeManifestsEvolvedUnpartitionedV1Table() throws IOException {
    assumeThat(formatVersion).isEqualTo(1);

    PartitionSpec spec = PartitionSpec.builderFor(SCHEMA).identity("c3").build();
    Map<String, String> options = Maps.newHashMap();
    options.put(TableProperties.SNAPSHOT_ID_INHERITANCE_ENABLED, snapshotIdInheritanceEnabled);
    options.put(TableProperties.FORMAT_VERSION, String.valueOf(formatVersion));
    Table table = TABLES.create(SCHEMA, spec, options, tableLocation);

    table.updateSpec().removeField("c3").commit();

    assertThat(table.spec().fields()).hasSize(1).allMatch(field -> field.transform().isVoid());

    List<DataFile> dataFiles = Lists.newArrayList();
    for (int fileOrdinal = 0; fileOrdinal < 1000; fileOrdinal++) {
      dataFiles.add(newDataFile(table, TestHelpers.Row.of(new Object[] {null})));
    }
    ManifestFile appendManifest = writeManifest(table, dataFiles);
    table.newFastAppend().appendManifest(appendManifest).commit();

    List<ManifestFile> originalManifests = table.currentSnapshot().allManifests(table.io());
    ManifestFile originalManifest = Iterables.getOnlyElement(originalManifests);

    // set the target manifest size to a small value to force splitting records into multiple files
    table
        .updateProperties()
        .set(
            TableProperties.MANIFEST_TARGET_SIZE_BYTES,
            String.valueOf(originalManifest.length() / 2))
        .commit();

    SparkActions actions = SparkActions.get();

    RewriteManifests.Result result =
        actions
            .rewriteManifests(table)
            .rewriteIf(manifest -> true)
            .option(RewriteManifestsSparkAction.USE_CACHING, useCaching)
            .execute();

    assertThat(result.rewrittenManifests()).hasSize(1);
    assertThat(result.addedManifests()).hasSizeGreaterThanOrEqualTo(2);
    assertManifestsLocation(result.addedManifests());

    List<ManifestFile> manifests = table.currentSnapshot().allManifests(table.io());
    assertThat(manifests).hasSizeGreaterThanOrEqualTo(2);
  }

  private void writeRecords(List<ThreeColumnRecord> records) {
    Dataset<Row> df = spark.createDataFrame(records, ThreeColumnRecord.class);
    writeDF(df);
  }

  private void writeDF(Dataset<Row> df) {
    df.select("c1", "c2", "c3")
        .write()
        .format("iceberg")
        .option(SparkWriteOptions.DISTRIBUTION_MODE, TableProperties.WRITE_DISTRIBUTION_MODE_NONE)
        .mode("append")
        .save(tableLocation);
  }

  private long computeManifestEntrySizeBytes(List<ManifestFile> manifests) {
    long totalSize = 0L;
    int numEntries = 0;

    for (ManifestFile manifest : manifests) {
      totalSize += manifest.length();
      numEntries +=
          manifest.addedFilesCount() + manifest.existingFilesCount() + manifest.deletedFilesCount();
    }

    return totalSize / numEntries;
  }

  private void assertManifestsLocation(Iterable<ManifestFile> manifests) {
    assertManifestsLocation(manifests, null);
  }

  private void assertManifestsLocation(Iterable<ManifestFile> manifests, String stagingLocation) {
    if (shouldStageManifests && stagingLocation != null) {
      assertThat(manifests).allMatch(manifest -> manifest.path().startsWith(stagingLocation));
    } else {
      assertThat(manifests).allMatch(manifest -> manifest.path().startsWith(tableLocation));
    }
  }

  private ManifestFile writeManifest(Table table, List<DataFile> files) throws IOException {
    File manifestFile = temp.newFile("generated-manifest.avro");
    Assert.assertTrue(manifestFile.delete());
    OutputFile outputFile = table.io().newOutputFile(manifestFile.getCanonicalPath());

    ManifestWriter<DataFile> writer =
        ManifestFiles.write(formatVersion, table.spec(), outputFile, null);

    try {
      for (DataFile file : files) {
        writer.add(file);
      }
    } finally {
      writer.close();
    }

    return writer.toManifestFile();
  }

  private DataFile newDataFile(Table table, String partitionPath) {
    return newDataFileBuilder(table).withPartitionPath(partitionPath).build();
  }

  private DataFile newDataFile(Table table, StructLike partition) {
    return newDataFileBuilder(table).withPartition(partition).build();
  }

  private DataFiles.Builder newDataFileBuilder(Table table) {
    return DataFiles.builder(table.spec())
        .withPath("/path/to/data-" + UUID.randomUUID() + ".parquet")
        .withFileSizeInBytes(10)
        .withRecordCount(1);
  }
}