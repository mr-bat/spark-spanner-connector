// Copyright 2023 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.cloud.spark.spanner;

import com.google.cloud.spanner.BatchReadOnlyTransaction;
import com.google.cloud.spanner.Options;
import com.google.cloud.spanner.PartitionOptions;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.TimestampBound;
import com.google.common.collect.Streams;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.spark.Partition;
import org.apache.spark.sql.connector.read.Batch;
import org.apache.spark.sql.connector.read.InputPartition;
import org.apache.spark.sql.connector.read.PartitionReaderFactory;
import org.apache.spark.sql.connector.read.Scan;
import org.apache.spark.sql.types.StructType;

/*
 * SpannerScanner implements Scan.
 */
public class SpannerScanner implements Batch, Scan {
  private SpannerTable spannerTable;
  private Map<String, String> opts;

  public SpannerScanner(Map<String, String> opts) {
    this.opts = opts;
    this.spannerTable = new SpannerTable(null, opts);
  }

  @Override
  public StructType readSchema() {
    return this.spannerTable.schema();
  }

  @Override
  public Batch toBatch() {
    return this;
  }

  @Override
  public PartitionReaderFactory createReaderFactory() {
    return new SpannerPartitionReaderFactory(this.opts);
  }

  @Override
  public InputPartition[] planInputPartitions() {
    // TODO: Receive the columns and filters that were pushed down.
    BatchClientWithCloser batchClient = SpannerUtils.batchClientFromProperties(this.opts);
    String sqlStmt = "SELECT * FROM " + this.spannerTable.name();
    try (BatchReadOnlyTransaction txn =
        batchClient.batchClient.batchReadOnlyTransaction(TimestampBound.strong())) {
      List<com.google.cloud.spanner.Partition> rawPartitions =
          txn.partitionQuery(
              PartitionOptions.getDefaultInstance(),
              Statement.of(sqlStmt),
              Options.dataBoostEnabled(true));

      List<Partition> parts =
          Streams.mapWithIndex(
                  rawPartitions.stream(),
                  (part, index) ->
                      new SpannerPartition(
                          part,
                          Math.toIntExact(index),
                          new SpannerInputPartitionContext(
                              part, txn.getBatchTransactionId(), this.opts)))
              .collect(Collectors.toList());

      return parts.toArray(new InputPartition[0]);
    }
  }
}
