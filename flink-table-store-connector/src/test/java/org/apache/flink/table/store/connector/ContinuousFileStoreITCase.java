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

package org.apache.flink.table.store.connector;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.config.ExecutionConfigOptions;
import org.apache.flink.table.store.file.utils.BlockingIterator;
import org.apache.flink.test.util.AbstractTestBase;
import org.apache.flink.types.Row;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.apache.flink.streaming.api.environment.ExecutionCheckpointingOptions.CHECKPOINTING_INTERVAL;
import static org.apache.flink.table.store.file.FileStoreOptions.PATH;
import static org.apache.flink.table.store.file.FileStoreOptions.TABLE_STORE_PREFIX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SQL ITCase for continuous file store. */
public class ContinuousFileStoreITCase extends AbstractTestBase {

    private TableEnvironment bEnv;
    private TableEnvironment sEnv;

    @Before
    public void before() throws IOException {
        bEnv = TableEnvironment.create(EnvironmentSettings.newInstance().inBatchMode().build());
        sEnv = TableEnvironment.create(EnvironmentSettings.newInstance().inStreamingMode().build());
        sEnv.getConfig().getConfiguration().set(CHECKPOINTING_INTERVAL, Duration.ofMillis(100));
        String path = TEMPORARY_FOLDER.newFolder().toURI().toString();
        prepareEnv(bEnv, path);
        prepareEnv(sEnv, path);
    }

    private void prepareEnv(TableEnvironment env, String path) {
        Configuration config = env.getConfig().getConfiguration();
        config.set(ExecutionConfigOptions.TABLE_EXEC_RESOURCE_DEFAULT_PARALLELISM, 2);
        config.setString(TABLE_STORE_PREFIX + PATH.key(), path);
        env.executeSql("CREATE TABLE IF NOT EXISTS T1 (a STRING, b STRING, c STRING)");
        env.executeSql(
                "CREATE TABLE IF NOT EXISTS T2 (a STRING, b STRING, c STRING, PRIMARY KEY (a) NOT ENFORCED)");
    }

    @Test
    public void testWithoutPrimaryKey() throws Exception {
        testSimple("T1");
    }

    @Test
    public void testWithPrimaryKey() throws Exception {
        testSimple("T2");
    }

    @Test
    public void testProjectionWithoutPrimaryKey() throws Exception {
        testProjection("T1");
    }

    @Test
    public void testProjectionWithPrimaryKey() throws Exception {
        testProjection("T2");
    }

    private void testSimple(String table)
            throws ExecutionException, InterruptedException, TimeoutException {
        BlockingIterator<Row, Row> iterator =
                BlockingIterator.of(sEnv.executeSql("SELECT * FROM " + table).collect());

        bEnv.executeSql(
                        String.format(
                                "INSERT INTO %s VALUES ('1', '2', '3'), ('4', '5', '6')", table))
                .await();
        assertThat(iterator.collect(2))
                .containsExactlyInAnyOrder(Row.of("1", "2", "3"), Row.of("4", "5", "6"));

        bEnv.executeSql(String.format("INSERT INTO %s VALUES ('7', '8', '9')", table)).await();
        assertThat(iterator.collect(1)).containsExactlyInAnyOrder(Row.of("7", "8", "9"));
    }

    private void testProjection(String table)
            throws ExecutionException, InterruptedException, TimeoutException {
        BlockingIterator<Row, Row> iterator =
                BlockingIterator.of(sEnv.executeSql("SELECT b, c FROM " + table).collect());

        bEnv.executeSql(
                        String.format(
                                "INSERT INTO %s VALUES ('1', '2', '3'), ('4', '5', '6')", table))
                .await();
        assertThat(iterator.collect(2))
                .containsExactlyInAnyOrder(Row.of("2", "3"), Row.of("5", "6"));

        bEnv.executeSql(String.format("INSERT INTO %s VALUES ('7', '8', '9')", table)).await();
        assertThat(iterator.collect(1)).containsExactlyInAnyOrder(Row.of("8", "9"));
    }

    @Test
    public void testContinuousLatest()
            throws ExecutionException, InterruptedException, TimeoutException {
        bEnv.executeSql("INSERT INTO T1 VALUES ('1', '2', '3'), ('4', '5', '6')").await();

        BlockingIterator<Row, Row> iterator =
                BlockingIterator.of(
                        sEnv.executeSql("SELECT * FROM T1 /*+ OPTIONS('log.scan'='latest') */")
                                .collect());

        bEnv.executeSql("INSERT INTO T1 VALUES ('7', '8', '9'), ('10', '11', '12')").await();
        assertThat(iterator.collect(2))
                .containsExactlyInAnyOrder(Row.of("7", "8", "9"), Row.of("10", "11", "12"));
    }

    @Test
    public void testIgnoreOverwrite()
            throws ExecutionException, InterruptedException, TimeoutException {
        BlockingIterator<Row, Row> iterator =
                BlockingIterator.of(sEnv.executeSql("SELECT * FROM T1").collect());

        bEnv.executeSql("INSERT INTO T1 VALUES ('1', '2', '3'), ('4', '5', '6')").await();
        assertThat(iterator.collect(2))
                .containsExactlyInAnyOrder(Row.of("1", "2", "3"), Row.of("4", "5", "6"));

        // should ignore this overwrite
        bEnv.executeSql("INSERT OVERWRITE T1 VALUES ('7', '8', '9')").await();

        bEnv.executeSql("INSERT INTO T1 VALUES ('9', '10', '11')").await();
        assertThat(iterator.collect(1)).containsExactlyInAnyOrder(Row.of("9", "10", "11"));
    }

    @Test
    public void testUnsupportedUpsert() {
        assertThatThrownBy(
                () ->
                        sEnv.executeSql(
                                        "SELECT * FROM T1 /*+ OPTIONS('log.changelog-mode'='upsert') */")
                                .collect(),
                "File store continuous reading dose not support upsert changelog mode");
    }

    @Test
    public void testUnsupportedEventual() {
        assertThatThrownBy(
                () ->
                        sEnv.executeSql(
                                        "SELECT * FROM T1 /*+ OPTIONS('log.consistency'='eventual') */")
                                .collect(),
                "File store continuous reading dose not support eventual consistency mode");
    }

    @Test
    public void testUnsupportedStartupTimestamp() {
        assertThatThrownBy(
                () ->
                        sEnv.executeSql(
                                        "SELECT * FROM T1 /*+ OPTIONS('log.scan'='from-timestamp') */")
                                .collect(),
                "File store continuous reading dose not support from_timestamp scan mode, "
                        + "you can add timestamp filters instead.");
    }
}