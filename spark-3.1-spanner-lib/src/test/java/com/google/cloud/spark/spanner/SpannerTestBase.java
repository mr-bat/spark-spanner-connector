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

import com.google.api.gax.longrunning.OperationFuture;
import com.google.cloud.spanner.BatchClient;
import com.google.cloud.spanner.Database;
import com.google.cloud.spanner.DatabaseAdminClient;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.DatabaseId;
import com.google.cloud.spanner.Dialect;
import com.google.cloud.spanner.Instance;
import com.google.cloud.spanner.InstanceAdminClient;
import com.google.cloud.spanner.InstanceConfig;
import com.google.cloud.spanner.InstanceId;
import com.google.cloud.spanner.InstanceInfo;
import com.google.cloud.spanner.Mutation;
import com.google.cloud.spanner.Spanner;
import com.google.cloud.spanner.SpannerOptions;
import com.google.cloud.spanner.Statement;
import com.google.common.collect.Lists;
import com.google.spanner.admin.database.v1.CreateDatabaseMetadata;
import com.google.spanner.admin.database.v1.UpdateDatabaseDdlMetadata;
import com.google.spanner.admin.instance.v1.CreateInstanceMetadata;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.catalyst.expressions.GenericInternalRow;
import org.apache.spark.sql.catalyst.util.GenericArrayData;
import org.apache.spark.unsafe.types.UTF8String;
import org.junit.BeforeClass;

class SpannerTestBase {
  // It is imperative that we generate a unique databaseId since in
  // the teardown we delete the Cloud Spanner database, hence use
  // system time.Nanos to avoid any cross-pollution between concurrently
  // running tests.
  private static String databaseId = System.getenv("SPANNER_DATABASE_ID") + "-" + System.nanoTime();
  private static String databaseIdPg = databaseId + "-pg";
  private static String instanceId = System.getenv("SPANNER_INSTANCE_ID");
  private static String projectId = System.getenv("SPANNER_PROJECT_ID");
  private static String table = "ATable";
  private static String tablePg = "composite_table";
  private static Spanner spanner;
  protected static String emulatorHost = System.getenv("SPANNER_EMULATOR_HOST");

  private static SpannerOptions createSpannerOptions() {
    return emulatorHost != null
        ? SpannerOptions.newBuilder().setProjectId(projectId).setEmulatorHost(emulatorHost).build()
        : SpannerOptions.newBuilder().setProjectId(projectId).build();
  }

  private static Thread mainThread = Thread.currentThread();

  private static synchronized boolean createSpanner() {
    if (spanner != null) {
      return false;
    }

    spanner = createSpannerOptions().getService();

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread() {
              @Override
              public void run() {
                teardown();
                try {
                  mainThread.join();
                } catch (Exception e) {
                  System.out.println("mainThread::join exception: " + e);
                }
              }
            });
    return true;
  }

  protected BatchClient createBatchClient() {
    return spanner.getBatchClient(DatabaseId.of(projectId, instanceId, databaseId));
  }

  private static void initDatabase() throws Exception {
    // 1. Create the Spanner handle.
    if (!createSpanner()) {
      return;
    }

    System.out.println("\033[34minitDatabase invoked!\033[00m");
    // 2. Now create the instance.
    InstanceAdminClient instanceAdminClient = spanner.getInstanceAdminClient();
    InstanceConfig config =
        instanceAdminClient.listInstanceConfigs().iterateAll().iterator().next();
    InstanceInfo instanceInfo =
        InstanceInfo.newBuilder(InstanceId.of(projectId, instanceId))
            .setInstanceConfigId(config.getId())
            .setNodeCount(1)
            .setDisplayName("SparkSpanner Test")
            .build();
    OperationFuture<Instance, CreateInstanceMetadata> createInstanceOperation =
        instanceAdminClient.createInstance(instanceInfo);

    try {
      createInstanceOperation.get();
    } catch (Exception e) {
      if (!e.toString().contains("ALREADY_EXISTS")) {
        throw e;
      }
    }

    DatabaseAdminClient databaseAdminClient = spanner.getDatabaseAdminClient();

    // 2. Create the database.
    // TODO: Skip this process if the database already exists.
    OperationFuture<Database, CreateDatabaseMetadata> createDatabaseOperation =
        databaseAdminClient.createDatabase(instanceId, databaseId, TestData.initialDDL);
    try {
      createDatabaseOperation.get();
    } catch (Exception e) {
      if (!e.toString().contains("ALREADY_EXISTS")) {
        throw e;
      }
    }

    // 3.1. Insert data into the databse.
    DatabaseClient databaseClient =
        spanner.getDatabaseClient(DatabaseId.of(projectId, instanceId, databaseId));
    databaseClient
        .readWriteTransaction()
        .run(
            txn -> {
              try {
                TestData.initialDML.forEach(sql -> txn.executeUpdate(Statement.of(sql)));
              } catch (Exception e) {
                if (!e.toString().contains("ALREADY_EXISTS")) {
                  throw e;
                }
              }

              return null;
            });

    // 3.2. Insert the Shakespeare data.
    // Using a smaller value of 1000 statements
    int maxValuesPerTxn = 1000;
    List<List<Mutation>> partitionedMutations =
        Lists.partition(TestData.shakespearMutations, maxValuesPerTxn);
    for (List<Mutation> mutations : partitionedMutations) {
      databaseClient.write(mutations);
    }

    populatePgDatabase(databaseAdminClient);
  }

  private static void populatePgDatabase(DatabaseAdminClient databaseAdminClient) throws Exception {
    if (emulatorHost != null && !emulatorHost.isEmpty()) {
      // Spanner emulator doesn't support the PostgreSql dialect interface.
      // If the emulator is set. We return immediately here.
      return;
    }
    String createDatabasePg = "CREATE DATABASE \"" + databaseIdPg + "\"";
    OperationFuture<Database, CreateDatabaseMetadata> createDatabaseOperationPg =
        databaseAdminClient.createDatabase(
            instanceId, createDatabasePg, Dialect.POSTGRESQL, Collections.emptyList());
    try {
      createDatabaseOperationPg.get();
    } catch (Exception e) {
      if (!e.toString().contains("ALREADY_EXISTS")) {
        throw e;
      }
    }
    OperationFuture<Void, UpdateDatabaseDdlMetadata> updateDatabaseDdlPg =
        databaseAdminClient.updateDatabaseDdl(
            instanceId, databaseIdPg, TestData.initialDDLPg, null);
    try {
      updateDatabaseDdlPg.get();
    } catch (Exception e) {
      if (!e.toString().contains("ALREADY_EXISTS")) {
        throw e;
      }
    }

    DatabaseClient databaseClientPg =
        spanner.getDatabaseClient(DatabaseId.of(projectId, instanceId, databaseIdPg));
    databaseClientPg
        .readWriteTransaction()
        .run(
            txn -> {
              try {
                TestData.initialDMLPg.forEach(sql -> txn.executeUpdate(Statement.of(sql)));
              } catch (Exception e) {
                if (!e.toString().contains("ALREADY_EXISTS")) {
                  throw e;
                }
              }

              return null;
            });

    int maxValuesPerTxn = 1000;
    List<List<Mutation>> partitionedMutations =
        Lists.partition(TestData.shakespearMutations, maxValuesPerTxn);
    for (List<Mutation> mutations : partitionedMutations) {
      databaseClientPg.write(mutations);
    }
  }

  @BeforeClass
  public static void setUp() throws Exception {
    initDatabase();
  }

  private static void cleanupDatabase() {
    DatabaseAdminClient databaseAdminClient = spanner.getDatabaseAdminClient();
    databaseAdminClient.dropDatabase(instanceId, databaseId);
    databaseAdminClient.dropDatabase(instanceId, databaseIdPg);
  }

  public static void teardown() {
    System.out.println("\033[33mShutting down now!\033[00m");
    cleanupDatabase();
    spanner.close();
  }

  protected static Map<String, String> connectionProperties(boolean usePostgreSql) {
    Map<String, String> props = new HashMap<>();
    if (usePostgreSql) {
      props.put("databaseId", databaseIdPg);
      props.put("table", tablePg);
    } else {
      props.put("databaseId", databaseId);
      props.put("table", table);
    }
    props.put("instanceId", instanceId);
    props.put("projectId", projectId);
    if (emulatorHost != null) {
      props.put("emulatorHost", emulatorHost);
    }
    return props;
  }

  protected static Map<String, String> connectionProperties() {
    return connectionProperties(false);
  }

  InternalRow makeInternalRow(int A, String B, double C) {
    GenericInternalRow row = new GenericInternalRow(3);
    row.setLong(0, A);
    row.update(1, UTF8String.fromString(B));
    row.setDouble(2, C);
    return row;
  }

  InternalRow makeATableInternalRow(
      long A, String B, byte[] C, ZonedDateTime D, double E, String[] F, String G) {
    GenericInternalRow row = new GenericInternalRow(7);
    row.setLong(0, ((Long) A));
    row.update(1, UTF8String.fromString(B));
    if (C == null) {
      row.update(2, null);
    } else {
      row.update(2, new GenericArrayData(C));
    }
    row.update(3, SpannerUtils.zonedDateTimeToSparkTimestamp(D));
    SpannerUtils.toSparkDecimal(row, new java.math.BigDecimal(E), 4);

    if (F == null) {
      row.update(5, null);
    } else {
      List<UTF8String> fDest = new ArrayList<UTF8String>(F.length);
      for (String s : F) {
        fDest.add(UTF8String.fromString(s));
      }
      row.update(5, fDest);
    }
    row.update(6, G == null ? null : UTF8String.fromString(G));
    return row;
  }

  class InternalRowComparator implements Comparator<InternalRow> {
    @Override
    public int compare(InternalRow r1, InternalRow r2) {
      return r1.toString().compareTo(r2.toString());
    }
  }

  public InternalRow makeCompositeTableRow(
      String id,
      long[] A,
      String[] B,
      String C,
      java.math.BigDecimal D,
      ZonedDateTime E,
      ZonedDateTime F,
      boolean G,
      ZonedDateTime[] H,
      ZonedDateTime[] I,
      String J,
      String K) {
    GenericInternalRow row = new GenericInternalRow(12);
    row.update(0, UTF8String.fromString(id));
    row.update(1, new GenericArrayData(A));
    row.update(2, new GenericArrayData(toSparkStrList(B)));
    row.update(3, UTF8String.fromString(C));
    SpannerUtils.toSparkDecimal(row, D, 4);
    row.update(5, SpannerUtils.zonedDateTimeToSparkDate(E));
    row.update(6, SpannerUtils.zonedDateTimeToSparkTimestamp(F));
    row.setBoolean(7, G);
    row.update(8, SpannerUtils.zonedDateTimeIterToSparkDates(Arrays.asList(H)));
    row.update(9, SpannerUtils.zonedDateTimeIterToSparkTimestamps(Arrays.asList(I)));
    row.update(10, stringToBytes(J));
    row.update(11, UTF8String.fromString(K));

    return row;
  }

  public InternalRow makeCompositeTableRowPg(
      long id,
      String charvCol,
      String textCol,
      String varcharCol,
      Boolean boolCol,
      Boolean booleanCol,
      Long bigintCol,
      Long int8Col,
      Long intCol,
      Double doubleCol,
      Double float8Col,
      String byteCol,
      String dateCol,
      java.math.BigDecimal numericCol,
      java.math.BigDecimal decimalCol,
      String timewithzoneCol,
      String timestampCol,
      String jsonCol) {
    GenericInternalRow row = new GenericInternalRow(18);
    row.setLong(0, id);
    row.update(1, charvCol == null ? null : UTF8String.fromString(charvCol));
    row.update(2, textCol == null ? null : UTF8String.fromString(textCol));
    row.update(3, varcharCol == null ? null : UTF8String.fromString(varcharCol));
    if (boolCol == null) {
      row.update(4, null);
    } else {
      row.setBoolean(4, boolCol);
    }
    if (booleanCol == null) {
      row.update(5, null);
    } else {
      row.setBoolean(5, booleanCol);
    }
    if (bigintCol == null) {
      row.update(6, null);
    } else {
      row.setLong(6, bigintCol);
    }
    if (int8Col == null) {
      row.update(7, null);
    } else {
      row.setLong(7, int8Col);
    }
    if (intCol == null) {
      row.update(8, null);
    } else {
      row.setLong(8, intCol);
    }
    if (doubleCol == null) {
      row.update(9, null);
    } else {
      row.setDouble(9, doubleCol);
    }
    if (float8Col == null) {
      row.update(10, null);
    } else {
      row.setDouble(10, float8Col);
    }
    row.update(11, charvCol == null ? null : UTF8String.fromString(byteCol));
    row.update(
        12,
        dateCol == null
            ? null
            : SpannerUtils.zonedDateTimeToSparkDate(ZonedDateTime.parse(dateCol)));
    if (numericCol == null) {
      row.update(13, null);
    } else {
      SpannerUtils.toSparkDecimal(row, numericCol, 13);
    }
    if (decimalCol == null) {
      row.update(14, null);
    } else {
      SpannerUtils.toSparkDecimal(row, decimalCol, 14);
    }
    row.update(
        15,
        timewithzoneCol == null
            ? null
            : SpannerUtils.zonedDateTimeToSparkTimestamp(ZonedDateTime.parse(timewithzoneCol)));
    row.update(
        16,
        timestampCol == null
            ? null
            : SpannerUtils.zonedDateTimeToSparkTimestamp(ZonedDateTime.parse(timestampCol)));
    row.update(17, jsonCol == null ? null : UTF8String.fromString(jsonCol));
    return row;
  }

  private UTF8String[] toSparkStrList(String[] strs) {
    List<UTF8String> dest = new ArrayList<>();
    for (String s : strs) {
      dest.add(UTF8String.fromString(s));
    }
    return dest.toArray(new UTF8String[0]);
  }

  private static byte[] stringToBytes(String str) {
    byte[] val = new byte[str.length() / 2];
    for (int i = 0; i < val.length; i++) {
      int index = i * 2;
      int j = Integer.parseInt(str.substring(index, index + 2), 16);
      val[i] = (byte) j;
    }
    return val;
  }
}
