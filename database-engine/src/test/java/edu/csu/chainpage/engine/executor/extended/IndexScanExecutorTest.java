package edu.csu.chainpage.engine.executor.extended;

import edu.csu.chainpage.engine.catalog.StorageCatalogRepository;
import edu.csu.chainpage.engine.catalog.SystemCatalogManager;
import edu.csu.chainpage.engine.common.DbError;
import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.contract.ColumnSchema;
import edu.csu.chainpage.engine.executor.ExecutionValue;
import edu.csu.chainpage.engine.executor.core.CreateTableExecutor;
import edu.csu.chainpage.engine.executor.core.ExecutorSupport;
import edu.csu.chainpage.engine.executor.core.InsertExecutor;
import edu.csu.chainpage.engine.executor.core.SeqScanExecutor;
import edu.csu.chainpage.engine.plan.JsonPlanNode;
import edu.csu.chainpage.engine.plan.PlanDispatcher;
import edu.csu.chainpage.engine.plan.PlanParser;
import edu.csu.chainpage.engine.storage.RowId;
import edu.csu.chainpage.engine.storage.RowSet;
import edu.csu.chainpage.engine.storage.StorageEngine;
import edu.csu.chainpage.engine.storage.index.IndexLookup;
import edu.csu.chainpage.engine.support.FakePageStorageClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 验证IndexScan的索引命中、回表读取、错误处理和顺序扫描边界
class IndexScanExecutorTest {

    @Test
    void returnsRowsForIndexHitsAndEmptyRowsForNoMatch() {
        IndexFixture fixture = new IndexFixture();
        fixture.initialize();
        fixture.indexLookup.rowIds = List.of(fixture.studentRows().rows().get(1).rowId());
        PlanDispatcher dispatcher = new PlanDispatcher();
        dispatcher.register(new IndexScanExecutor(fixture.storageEngine, fixture.catalogManager));

        var hit = dispatcher.execute("req-hit", fixture.indexScanPlan());
        fixture.indexLookup.rowIds = List.of();
        var empty = dispatcher.execute("req-empty", fixture.indexScanPlan());

        assertTrue(hit.isOk());
        assertEquals(List.of("id", "name"), hit.data().rowSet().schema().stream()
                .map(edu.csu.chainpage.engine.storage.ColumnSchema::getName)
                .toList());
        assertEquals(1, hit.data().rowSet().size());
        assertEquals(2, hit.data().rowSet().rows().get(0).values().valueOf("id"));
        assertEquals("Bob", hit.data().rowSet().rows().get(0).values().valueOf("name"));
        assertTrue(empty.isOk());
        assertEquals(0, empty.data().rowSet().size());
        assertEquals(2, fixture.indexLookup.ensureCalls);
        assertEquals(2, fixture.indexLookup.findCalls);
    }

    @Test
    void returnsIndexUnavailableWithoutFallingBackToSeqScan() {
        IndexFixture fixture = new IndexFixture();
        fixture.initialize();
        fixture.indexLookup.available = false;
        IndexScanExecutor executor = new IndexScanExecutor(
                fixture.storageEngine,
                fixture.catalogManager
        );

        var result = executor.execute("req-unavailable", fixture.indexScanPlan());

        assertFalse(result.isOk());
        assertEquals("INDEX_UNAVAILABLE", result.error().getCode());
        assertEquals(1, fixture.indexLookup.ensureCalls);
        assertEquals(0, fixture.indexLookup.findCalls);
        assertEquals(0, fixture.pageStorageClient.callCount(FakePageStorageClient.GET_PAGE));
    }

    @Test
    void rejectsInvalidRowIdAndPropagatesReadFailure() {
        IndexFixture fixture = new IndexFixture();
        fixture.initialize();
        RowId validRowId = fixture.studentRows().rows().get(0).rowId();
        IndexScanExecutor executor = new IndexScanExecutor(
                fixture.storageEngine,
                fixture.catalogManager
        );
        fixture.indexLookup.rowIds = List.of(new RowId(validRowId.pageId(), 999));

        var invalid = executor.execute("req-invalid-row", fixture.indexScanPlan());

        fixture.indexLookup.rowIds = List.of(validRowId);
        fixture.pageStorageClient.failNext(
                FakePageStorageClient.GET_PAGE,
                new DbError(
                        "storage-request",
                        null,
                        "STORAGE",
                        "FILE_IO_ERROR",
                        "模拟索引回表读取失败",
                        null,
                        null,
                        validRowId.pageId()
                )
        );
        var readFailure = executor.execute("req-read-failure", fixture.indexScanPlan());

        assertFalse(invalid.isOk());
        assertEquals("ROW_NOT_FOUND", invalid.error().getCode());
        assertFalse(readFailure.isOk());
        assertEquals("FILE_IO_ERROR", readFailure.error().getCode());
        assertEquals("req-read-failure", readFailure.error().getRequestId());
        assertEquals(validRowId.pageId(), readFailure.error().getPageId());
    }

    @Test
    void validatesRequiredPlanFieldsAndSchema() {
        IndexFixture fixture = new IndexFixture();
        fixture.initialize();
        IndexScanExecutor executor = new IndexScanExecutor(
                fixture.storageEngine,
                fixture.catalogManager
        );
        JsonPlanNode missingCondition = new JsonPlanNode(
                "IndexScan",
                Map.of("kind", "IndexScan", "table", "student", "index", "idx_student_id"),
                List.of(),
                fixture.planSchema()
        );
        JsonPlanNode wrongSchema = new JsonPlanNode(
                "IndexScan",
                fixture.indexFields(),
                List.of(),
                List.of(new ColumnSchema("id", "VARCHAR"))
        );

        var invalidFields = executor.validateIndexPlan(missingCondition);
        var mismatch = executor.execute("req-schema", wrongSchema);
        var parsed = new PlanParser().parse(fixture.rawIndexScanPlan());

        assertFalse(invalidFields.isOk());
        assertEquals("EXECUTOR_INVALID_PLAN", invalidFields.error().getCode());
        assertFalse(mismatch.isOk());
        assertEquals("EXECUTOR_INDEX_SCHEMA_MISMATCH", mismatch.error().getCode());
        assertTrue(parsed.isOk());
        assertEquals("IndexScan", parsed.data().kind());
    }

    @Test
    void leavesSeqScanChosenByUpstreamPlanUnchanged() {
        IndexFixture fixture = new IndexFixture();
        fixture.initialize();
        PlanDispatcher dispatcher = new PlanDispatcher();
        dispatcher.register(new SeqScanExecutor(fixture.storageEngine, fixture.catalogManager));
        dispatcher.register(new IndexScanExecutor(fixture.storageEngine, fixture.catalogManager));

        DbResult<ExecutionValue> result = dispatcher.execute("req-seq", fixture.seqScanPlan());

        assertTrue(result.isOk());
        assertEquals(2, result.data().rowSet().size());
        assertEquals(0, fixture.indexLookup.ensureCalls);
        assertEquals(0, fixture.indexLookup.findCalls);
    }

    // 组合真实StorageEngine、Catalog和可控制索引能力的阶段9测试夹具
    private static final class IndexFixture {

        private final RecordingIndexLookup indexLookup = new RecordingIndexLookup(); // 模拟物理索引
        private final FakePageStorageClient pageStorageClient = new FakePageStorageClient(); // 模拟页存储
        private final StorageEngine storageEngine = new StorageEngine(
                pageStorageClient,
                indexLookup
        ); // 被测试的存储引擎
        private final SystemCatalogManager catalogManager = new SystemCatalogManager(
                new StorageCatalogRepository(storageEngine)
        ); // 被测试的目录管理器

        // 初始化目录、student表和两条记录
        private void initialize() {
            assertSuccess(catalogManager.initialize("index-init"));
            assertSuccess(new CreateTableExecutor(storageEngine, catalogManager)
                    .execute("index-create", createTablePlan()));
            InsertExecutor insert = new InsertExecutor(storageEngine, catalogManager);
            assertSuccess(insert.execute("index-insert-1", insertPlan(1, "Alice")));
            assertSuccess(insert.execute("index-insert-2", insertPlan(2, "Bob")));
            pageStorageClient.clearCalls();
        }

        // 扫描student表以取得测试记录及其真实RowId
        private RowSet studentRows() {
            DbResult<RowSet> rows = storageEngine.scanRows(
                    "index-scan-fixture",
                    ExecutorSupport.toStorageSchema(contractSchema())
            );
            if (!rows.isOk()) {
                throw new AssertionError(rows.error().getMessage());
            }
            pageStorageClient.clearCalls();
            return rows.data();
        }

        // 构造IndexScan计划
        private JsonPlanNode indexScanPlan() {
            return new JsonPlanNode(
                    "IndexScan",
                    indexFields(),
                    List.of(),
                    planSchema()
            );
        }

        // 构造SeqScan计划，用于验证执行器不会擅自选择索引
        private JsonPlanNode seqScanPlan() {
            return new JsonPlanNode(
                    "SeqScan",
                    Map.of("kind", "SeqScan", "table", "student"),
                    List.of(),
                    planSchema()
            );
        }

        // 构造可以交给PlanParser的原始IndexScan计划
        private Map<String, Object> rawIndexScanPlan() {
            Map<String, Object> plan = new LinkedHashMap<>(indexFields());
            plan.put("children", new ArrayList<>());
            plan.put("schema", List.of(
                    Map.of("name", "id", "dataType", "INT"),
                    Map.of("name", "name", "dataType", "VARCHAR")
            ));
            return plan;
        }

        // 返回IndexScan除children和schema以外的字段
        private Map<String, Object> indexFields() {
            return Map.of(
                    "kind", "IndexScan",
                    "table", "student",
                    "index", "idx_student_id",
                    "condition", Map.of(
                            "kind", "BinaryExpr",
                            "operator", "=",
                            "left", Map.of("kind", "IdentifierExpr", "name", "id"),
                            "right", Map.of(
                                    "kind", "LiteralExpr",
                                    "literalType", "INT",
                                    "value", 2
                            )
                    )
            );
        }

        // 返回计划节点使用的输出模式
        private List<ColumnSchema> planSchema() {
            return List.of(
                    new ColumnSchema("id", "INT"),
                    new ColumnSchema("name", "VARCHAR")
            );
        }

        // 返回目录和存储引擎使用的表结构
        private edu.csu.chainpage.engine.contract.TableSchema contractSchema() {
            return new edu.csu.chainpage.engine.contract.TableSchema("student", planSchema());
        }

        // 构造CreateTable计划
        private JsonPlanNode createTablePlan() {
            return new JsonPlanNode(
                    "CreateTable",
                    Map.of(
                            "kind", "CreateTable",
                            "table", "student",
                            "columns", List.of(
                                    Map.of("name", "id", "dataType", "INT"),
                                    Map.of("name", "name", "dataType", "VARCHAR")
                            )
                    ),
                    List.of(),
                    List.of()
            );
        }

        // 构造Insert计划
        private JsonPlanNode insertPlan(int id, String name) {
            return new JsonPlanNode(
                    "Insert",
                    Map.of(
                            "kind", "Insert",
                            "table", "student",
                            "columns", List.of("id", "name"),
                            "values", List.of(
                                    Map.of(
                                            "kind", "LiteralExpr",
                                            "literalType", "INT",
                                            "value", id
                                    ),
                                    Map.of(
                                            "kind", "LiteralExpr",
                                            "literalType", "VARCHAR",
                                            "value", name
                                    )
                            )
                    ),
                    List.of(),
                    List.of()
            );
        }

        // 把夹具准备失败转换成明确的测试失败
        private void assertSuccess(DbResult<?> result) {
            if (!result.isOk()) {
                throw new AssertionError(result.error().getMessage());
            }
        }
    }

    // 模拟可用或不可用的物理索引，并记录是否被调用
    private static final class RecordingIndexLookup implements IndexLookup {

        private List<RowId> rowIds = List.of(); // 下一次索引查询结果
        private boolean available = true; // 索引是否可用
        private int ensureCalls; // 可用性检查次数
        private int findCalls; // 索引查询次数

        // 返回预置的RowId列表
        @Override
        public DbResult<List<RowId>> find(
                String requestId,
                String table,
                String index,
                Object condition) {
            findCalls++;
            return DbResult.ok(rowIds);
        }

        // 根据测试开关返回可用结果或INDEX_UNAVAILABLE
        @Override
        public DbResult<Void> ensureAvailable(String requestId, String table, String index) {
            ensureCalls++;
            if (available) {
                return DbResult.ok(null);
            }
            return DbResult.fail(new DbError(
                    requestId,
                    null,
                    "STORAGE",
                    "INDEX_UNAVAILABLE",
                    "索引不可用：" + index,
                    null,
                    null,
                    null
            ));
        }
    }
}
