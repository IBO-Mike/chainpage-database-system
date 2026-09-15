package edu.csu.chainpage.engine.contract;

import edu.csu.chainpage.engine.common.DbError;
import edu.csu.chainpage.engine.storage.Row;
import edu.csu.chainpage.engine.storage.StorageEngine;
import edu.csu.chainpage.engine.support.FakePageStorageClient;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 验证数据库引擎与页式存储系统之间的全部公开操作契约
class PageStorageContractTest {

    @Test
    void mapsEveryRequiredPageOperation() {
        PageStorageClient storage = new FakePageStorageClient();

        assertTrue(storage.createTablePages("req-create", "student").isOk());
        var allocated = storage.allocatePageForTable("req-allocate", "student");
        int pageId = allocated.data().getPageId();
        assertTrue(storage.getPage("req-get", pageId).isOk());
        assertTrue(storage.writePage(
                "req-write",
                pageId,
                Base64.getEncoder().encodeToString(new byte[4096])
        ).isOk());
        assertEquals(List.of(pageId),
                storage.listTablePages("req-list", "student").data().getPageIds());
        assertTrue(storage.flushAll("req-flush").isOk());
        assertTrue(storage.dropTablePages("req-drop", "student").data().isRemoved());
    }

    @Test
    void preservesStorageErrorFields() {
        FakePageStorageClient pages = new FakePageStorageClient();
        StorageEngine engine = new StorageEngine(pages);
        edu.csu.chainpage.engine.storage.TableSchema schema =
                new edu.csu.chainpage.engine.storage.TableSchema(
                        "student",
                        List.of(new edu.csu.chainpage.engine.storage.ColumnSchema("id", "INT"))
                );
        engine.createTableStorage("prepare", schema);
        int pageId = pages.allocatePageForTable("prepare", "student").data().getPageId();
        pages.failNext(FakePageStorageClient.GET_PAGE, new DbError(
                "lower-request", null, "STORAGE", "FILE_IO_ERROR",
                "读取数据页失败", null, null, pageId
        ));

        var result = engine.scanRows("req-scan", schema);

        assertFalse(result.isOk());
        assertEquals("STORAGE", result.error().getStage());
        assertEquals("FILE_IO_ERROR", result.error().getCode());
        assertEquals(pageId, result.error().getPageId());
        assertEquals("req-scan", result.error().getRequestId());
    }

    @Test
    void passesOnlyFullPagePayloads() {
        FakePageStorageClient pages = new FakePageStorageClient();
        StorageEngine engine = new StorageEngine(pages);
        edu.csu.chainpage.engine.storage.TableSchema schema =
                new edu.csu.chainpage.engine.storage.TableSchema(
                        "student",
                        List.of(new edu.csu.chainpage.engine.storage.ColumnSchema("id", "INT"))
                );
        engine.createTableStorage("req-create", schema);

        var inserted = engine.insertRow("req-insert", "student", new Row(Map.of("id", 1)));
        assertTrue(inserted.isOk());
        assertEquals(1, pages.callCount(FakePageStorageClient.WRITE_PAGE));

        var invalid = pages.writePage(
                "req-invalid",
                inserted.data().pageId(),
                Base64.getEncoder().encodeToString(new byte[4095])
        );

        assertFalse(invalid.isOk());
        assertEquals("INVALID_PAGE_SIZE", invalid.error().getCode());
    }
}
