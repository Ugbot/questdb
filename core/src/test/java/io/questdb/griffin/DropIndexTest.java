/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2022 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.griffin;

import io.questdb.cairo.*;
import io.questdb.cairo.security.AllowAllCairoSecurityContext;
import io.questdb.cairo.sql.RecordCursor;
import io.questdb.cairo.sql.RecordCursorFactory;
import io.questdb.mp.SOCountDownLatch;
import io.questdb.std.Misc;
import io.questdb.std.str.Path;
import io.questdb.test.tools.TestUtils;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class DropIndexTest extends AbstractGriffinTest {
    private static final String CREATE_TABLE_STMT = "CREATE TABLE sensors AS (\n" +
            "    select\n" +
            "        rnd_symbol('ALPHA', 'OMEGA', 'THETA') sensor_id, \n" +
            "        rnd_int() temperature, \n" +
            "        timestamp_sequence(0, 3600000) ts \n" +
            "    FROM long_sequence(2000)\n" +
            "), INDEX(sensor_id CAPACITY 32) TIMESTAMP(ts)";

    @Test
    public void testDropIndexOfNonIndexedColumnShouldFail() throws Exception {
        assertMemoryLeak(() -> {
            compiler.compile(
                    "CREATE TABLE підрахунок AS (select rnd_symbol('K1', 'K2') колонка from long_sequence(317))",
                    sqlExecutionContext
            );
            try {
                compile("alter table підрахунок alter column колонка drop index", sqlExecutionContext);
                Assert.fail();
            } catch (SqlException e) {
                Assert.assertEquals(12, e.getPosition());
                TestUtils.assertContains(e.getFlyweightMessage(), "Column is not indexed [name=колонка][errno=-100]");
            }
        });
    }

    @Test
    public void testAlterTableAlterColumnDropIndexSyntaxErrors0() throws Exception {
        assertFailure(
                "alter table sensors alter column sensor_id dope index",
                CREATE_TABLE_STMT,
                43,
                "'add', 'drop', 'cache' or 'nocache' expected found 'dope'"
        );
    }

    @Test
    public void testAlterTableAlterColumnDropIndexSyntaxErrors1() throws Exception {
        assertFailure(
                "alter table sensors alter column sensor_id drop",
                CREATE_TABLE_STMT,
                47,
                "'index' expected"
        );
    }

    @Test
    public void testAlterTableAlterColumnDropIndexSyntaxErrors2() throws Exception {
        assertFailure(
                "alter table sensors alter column sensor_id drop index,",
                CREATE_TABLE_STMT,
                53,
                "unexpected token [,] while trying to drop index"
        );
    }

    @Test
    public void testVanillaDropIndexOfIndexedColumnPartitionedTable() throws Exception {
        testVanillaDropIndexOfIndexedColumn(
                CREATE_TABLE_STMT + " PARTITION BY HOUR",
                "sensors",
                "sensor_id",
                32
        );
    }

    @Test
    public void testVanillaDropIndexOfIndexedColumnNonPartitionedTable() throws Exception {
        testVanillaDropIndexOfIndexedColumn(
                CREATE_TABLE_STMT + " PARTITION BY NONE",
                "sensors",
                "sensor_id",
                32
        );
    }

    @Test
    public void testDropIndex() throws Exception {
        assertMemoryLeak(configuration.getFilesFacade(), () -> {

            String expected = "sensor_id\tts\n" +
                    "ALPHA\t1970-01-01T00:00:00.000000Z\n" +
                    "ALPHA\t1970-01-01T00:00:36.000000Z\n" +
                    "OMEGA\t1970-01-01T00:01:12.000000Z\n";

            String tableName = "sensors";
            String columnName = "sensor_id";

            compile(
                    "create table " + tableName + " as (" +
                            "    select" +
                            "        rnd_symbol('ALPHA', 'OMEGA', 'THETA') " + columnName + "," +
                            "        timestamp_sequence(0, 36000000) ts" +
                            "    from long_sequence(3)" +
                            "), index(" + columnName + " capacity 4) timestamp(ts) partition by DAY;",
                    sqlExecutionContext
            );
            assertSql(tableName, expected);

            Path path = new Path().put(configuration.getRoot()).concat(tableName);
            try {
                int pathLen = path.length();
                checkMetadataAndTxn(path, tableName, columnName, 0, 1, 0, true);
                assertSql(tableName, expected);
                verifyColumnIsIndexed(tableName, columnName, true, 4);

                executeOperation(
                        "ALTER TABLE " + tableName + " ALTER COLUMN " + columnName + " DROP INDEX",
                        CompiledQuery.ALTER,
                        CompiledQuery::getAlterOperation
                );

                path.trimTo(pathLen);
                checkMetadataAndTxn(path, tableName, columnName, 1, 2, 1, false);
                assertSql(tableName, expected);
                verifyColumnIsIndexed(tableName, columnName, false, 256);
            } finally {
                Misc.free(path);
            }
        });
    }

    // TODO fix this test: compiler is not multi threaded
    @Test
    @Ignore
    public void testParallelDropIndexPreservesIndexFilesWhenThereIsATransactionReadingIt() throws Exception {
        assertMemoryLeak(() -> {
            compiler.compile(CREATE_TABLE_STMT + " PARTITION BY HOUR", sqlExecutionContext);
            verifyColumnIsIndexed("sensors", "sensor_id", true, 32);
            engine.releaseAllWriters();

            final String select = "SELECT ts, sensor_id " +
                    "FROM sensors " +
                    "WHERE sensor_id = 'OMEGA' and ts > '1970-01-01T01:59:06.000000Z'";
            final String expected = "ts\tsensor_id\n" +
                    "1970-01-01T01:59:24.000000Z\tOMEGA\n" +
                    "1970-01-01T01:59:38.400000Z\tOMEGA\n" +
                    "1970-01-01T01:59:42.000000Z\tOMEGA\n" +
                    "1970-01-01T01:59:45.600000Z\tOMEGA\n" +
                    "1970-01-01T01:59:56.400000Z\tOMEGA\n";

            final CyclicBarrier startBarrier = new CyclicBarrier(2);
            final SOCountDownLatch endLatch = new SOCountDownLatch(1);
            final AtomicReference<Throwable> failureReason = new AtomicReference<>();

            new Thread(() -> {
                try {
                    startBarrier.await();
                    for (int i = 0; i < 5; i++) {
                        System.out.printf("ROUND %d BEGIN%n", i);
                        try (RecordCursorFactory factory = compiler.compile(select, sqlExecutionContext).getRecordCursorFactory()) {
                            try (RecordCursor cursor = factory.getCursor(sqlExecutionContext)) {
                                sink.clear();
                                TestUtils.assertCursor(expected, cursor, factory.getMetadata(), true, sink);
                            }
                        }
                        System.out.printf("ROUND %d END%n", i);
                        Thread.sleep(100L);
                    }
                } catch (Throwable e) {
                    failureReason.set(e);
                    e.printStackTrace();
                } finally {
                    engine.releaseAllReaders();
                    endLatch.countDown();
                }
            }).start();
            startBarrier.await();

            compile(
                    "alter table sensors alter column sensor_id drop index",
                    sqlExecutionContext
            );
            verifyColumnIsIndexed("sensors", "sensor_id", false, configuration.getIndexValueBlockSize());

            endLatch.await();
            Throwable excpt = failureReason.get();
            if (excpt != null) {
                Assert.fail(excpt.getMessage());
            }

            Assert.assertTrue(findIndexFiles(configuration, "sensors", "sensor_id").isEmpty());
        });
    }

    private void testVanillaDropIndexOfIndexedColumn(
            String createStatement,
            String tableName,
            String columnName,
            int indexValueBockSize
    ) throws Exception {
        assertMemoryLeak(() -> {
            compiler.compile(createStatement, sqlExecutionContext);
            verifyColumnIsIndexed(tableName, columnName, true, indexValueBockSize);
            compile(
                    "alter table " + tableName + " alter column " + columnName + " drop index",
                    sqlExecutionContext
            );
            verifyColumnIsIndexed(tableName, columnName, false, configuration.getIndexValueBlockSize());
        });
    }

    private void checkMetadataAndTxn(
            io.questdb.std.str.Path path,
            String tableName,
            String columnName,
            long expectedStructureVersion,
            long expectedReaderVersion,
            long expectedColumnVersion,
            boolean isColumnIndexed
    ) {
        try (TxReader txReader = new TxReader(ff)) {
            txReader.ofRO(path, PartitionBy.DAY);
            txReader.unsafeLoadAll();
            Assert.assertEquals(expectedStructureVersion, txReader.getStructureVersion());
            Assert.assertEquals(expectedReaderVersion, txReader.getTxn());
            Assert.assertEquals(expectedReaderVersion, txReader.getVersion());
            Assert.assertEquals(expectedColumnVersion, txReader.getColumnVersion());
            Assert.assertEquals(3, txReader.getTransientRowCount());
            try (TableReader reader = engine.getReader(AllowAllCairoSecurityContext.INSTANCE, tableName)) {
                TableReaderMetadata metadata = reader.getMetadata();
                Assert.assertEquals(PartitionBy.DAY, metadata.getPartitionBy());
                Assert.assertEquals(expectedStructureVersion, metadata.getStructureVersion());
                Assert.assertEquals(2, metadata.getColumnCount());
                int columnIndex = metadata.getColumnIndex(columnName);
                Assert.assertEquals(0, columnIndex);
                Assert.assertEquals(isColumnIndexed, metadata.isColumnIndexed(columnIndex));
            }
        }
    }

    private void verifyColumnIsIndexed(
            String tableName,
            String columnName,
            boolean isIndexed,
            int indexValueBlockSize
    ) throws Exception {
        try (TableReader reader = engine.getReader(AllowAllCairoSecurityContext.INSTANCE, tableName)) {
            TableReaderMetadata metadata = reader.getMetadata();
            final int columnIndex = metadata.getColumnIndex(columnName);
            Assert.assertEquals(isIndexed, metadata.isColumnIndexed(columnIndex));
            Assert.assertEquals(indexValueBlockSize, metadata.getIndexValueBlockCapacity(columnIndex));
            if (isIndexed) {
                final Set<?> indexFiles = findIndexFiles(configuration, tableName, columnName);
                Assert.assertTrue(indexFiles.size() >= 2);
            }
        }
    }

    private static Set<java.nio.file.Path> findIndexFiles(
            CairoConfiguration config,
            String tableName,
            String columnName
    ) throws Exception {
        final java.nio.file.Path tablePath = FileSystems.getDefault().getPath((String) config.getRoot(), tableName);
        return Files.find(
                tablePath,
                Integer.MAX_VALUE,
                (path, _attrs) -> isIndexRelatedFile(tablePath, columnName, path)
        ).collect(Collectors.toSet());
    }

    private static boolean isIndexRelatedFile(java.nio.file.Path tablePath, String colName, java.nio.file.Path filePath) {
        final String fn = filePath.getFileName().toString();
        return (fn.equals(colName + ".k") || fn.equals(colName + ".v")) && !filePath.getParent().equals(tablePath);
    }
}
