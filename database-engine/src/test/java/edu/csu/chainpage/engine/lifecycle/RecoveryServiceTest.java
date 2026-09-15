package edu.csu.chainpage.engine.lifecycle;

import edu.csu.chainpage.engine.common.DbError;
import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.contract.CatalogSnapshot;
import edu.csu.chainpage.engine.contract.ColumnSchema;
import edu.csu.chainpage.engine.contract.TableSchema;
import edu.csu.chainpage.engine.storage.StorageEngine;
import edu.csu.chainpage.engine.support.FakePageStorageClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 验证恢复服务能够校验目录并读取每张表的持久化页面
class RecoveryServiceTest {

    @Test
    void restoresCatalogSchemas() {
        FakePageStorageClient pages = new FakePageStorageClient();
        pages.createTablePages("prepare", "student");
        TableSchema student = studentSchema();
        RecoveryService service = new RecoveryService(
                requestId -> DbResult.ok(new CatalogSnapshot(List.of(student))),
                new StorageEngine(pages)
        );

        var result = service.recover("req-recover");

        assertTrue(result.isOk());
        assertEquals(List.of(student), result.data());
        assertEquals(1, pages.callCount(FakePageStorageClient.LIST_TABLE_PAGES));
    }

    @Test
    void failsWhenCatalogIsInvalid() {
        FakePageStorageClient pages = new FakePageStorageClient();
        RecoveryService service = new RecoveryService(
                requestId -> DbResult.ok(new CatalogSnapshot(List.of(
                        studentSchema(),
                        new TableSchema("STUDENT", List.of(new ColumnSchema("id", "INT")))
                ))),
                new StorageEngine(pages)
        );

        var result = service.recover("req-invalid-catalog");

        assertFalse(result.isOk());
        assertEquals("RECOVERY_DUPLICATE_TABLE", result.error().getCode());
        assertEquals(0, pages.callCount(FakePageStorageClient.LIST_TABLE_PAGES));
    }

    @Test
    void failsWhenTablePageCannotBeRead() {
        FakePageStorageClient pages = new FakePageStorageClient();
        pages.createTablePages("prepare", "student");
        pages.allocatePageForTable("prepare", "student");
        pages.failNext(
                FakePageStorageClient.GET_PAGE,
                new DbError(
                        "prepare",
                        null,
                        "STORAGE",
                        "FILE_IO_ERROR",
                        "数据页读取失败",
                        null,
                        null,
                        0
                )
        );
        RecoveryService service = new RecoveryService(
                requestId -> DbResult.ok(new CatalogSnapshot(List.of(studentSchema()))),
                new StorageEngine(pages)
        );

        var result = service.recover("req-page-error");

        assertFalse(result.isOk());
        assertEquals("FILE_IO_ERROR", result.error().getCode());
        assertEquals("req-page-error", result.error().getRequestId());
        assertEquals(0, result.error().getPageId());
    }

    // 构造一张合法的学生表目录定义
    private TableSchema studentSchema() {
        return new TableSchema(
                "student",
                List.of(
                        new ColumnSchema("id", "INT"),
                        new ColumnSchema("name", "VARCHAR")
                )
        );
    }
}
