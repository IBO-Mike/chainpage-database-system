package edu.csu.chainpage.engine.storage.index;

import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.storage.ColumnSchema;
import edu.csu.chainpage.engine.storage.RowId;
import edu.csu.chainpage.engine.storage.StorageEngine;
import edu.csu.chainpage.engine.storage.TableSchema;
import edu.csu.chainpage.engine.support.FakePageStorageClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 验证StorageIndexLookup只通过StorageEngine检查和访问索引
class StorageIndexLookupTest {

    @Test
    void delegatesAvailabilityAndLookupWithNormalizedNames() {
        RecordingIndexLookup backend = new RecordingIndexLookup();
        RowId expected = new RowId(12, 3);
        backend.rowIds = List.of(expected);
        StorageEngine storageEngine = storageEngine(backend);
        StorageIndexLookup lookup = new StorageIndexLookup(storageEngine);
        Object condition = Map.of("kind", "LiteralExpr", "literalType", "INT", "value", 7);

        var available = lookup.ensureAvailable("req-index", "STUDENT", "IDX_STUDENT_ID");
        var found = lookup.find("req-index", "STUDENT", "IDX_STUDENT_ID", condition);

        assertTrue(available.isOk());
        assertTrue(found.isOk());
        assertEquals(List.of(expected), found.data());
        assertEquals("student", backend.lastTable);
        assertEquals("idx_student_id", backend.lastIndex);
        assertSame(condition, backend.lastCondition);
        assertEquals(1, backend.ensureCalls);
        assertEquals(1, backend.findCalls);
    }

    @Test
    void returnsIndexUnavailableWhenNoPhysicalIndexCapabilityIsConfigured() {
        StorageEngine storageEngine = new StorageEngine(new FakePageStorageClient());
        assertTrue(storageEngine.createTableStorage("create", schema()).isOk());
        StorageIndexLookup lookup = new StorageIndexLookup(storageEngine);

        var result = lookup.ensureAvailable("req-unavailable", "student", "idx_student_id");

        assertFalse(result.isOk());
        assertEquals("INDEX_UNAVAILABLE", result.error().getCode());
        assertEquals("req-unavailable", result.error().getRequestId());
    }

    @Test
    void rejectsMissingTableBeforeCallingIndexBackend() {
        RecordingIndexLookup backend = new RecordingIndexLookup();
        StorageEngine storageEngine = new StorageEngine(new FakePageStorageClient(), backend);
        StorageIndexLookup lookup = new StorageIndexLookup(storageEngine);

        var result = lookup.ensureAvailable("req-missing", "missing", "idx_id");

        assertFalse(result.isOk());
        assertEquals("TABLE_NOT_FOUND", result.error().getCode());
        assertEquals(0, backend.ensureCalls);
    }

    // 创建已经登记student表且使用指定索引后端的存储引擎
    private StorageEngine storageEngine(IndexLookup backend) {
        StorageEngine storageEngine = new StorageEngine(new FakePageStorageClient(), backend);
        DbResult<?> created = storageEngine.createTableStorage("create", schema());
        if (!created.isOk()) {
            throw new AssertionError(created.error().getMessage());
        }
        return storageEngine;
    }

    // 创建索引测试使用的student表结构
    private TableSchema schema() {
        return new TableSchema(
                "student",
                List.of(
                        new ColumnSchema("id", "INT"),
                        new ColumnSchema("name", "VARCHAR")
                )
        );
    }

    // 记录StorageEngine传给物理索引能力的参数
    private static final class RecordingIndexLookup implements IndexLookup {

        private List<RowId> rowIds = List.of(); // 下一次查询返回的记录位置
        private String lastTable; // 最近一次收到的表名
        private String lastIndex; // 最近一次收到的索引名
        private Object lastCondition; // 最近一次收到的索引条件
        private int ensureCalls; // 可用性检查次数
        private int findCalls; // 索引查找次数

        // 返回预置的索引查询结果并记录调用参数
        @Override
        public DbResult<List<RowId>> find(
                String requestId,
                String table,
                String index,
                Object condition) {
            findCalls++;
            lastTable = table;
            lastIndex = index;
            lastCondition = condition;
            return DbResult.ok(rowIds);
        }

        // 将测试索引视为可用并记录规范化后的名称
        @Override
        public DbResult<Void> ensureAvailable(String requestId, String table, String index) {
            ensureCalls++;
            lastTable = table;
            lastIndex = index;
            return DbResult.ok(null);
        }
    }
}
