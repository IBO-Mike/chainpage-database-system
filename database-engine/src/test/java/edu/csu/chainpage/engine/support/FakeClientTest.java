package edu.csu.chainpage.engine.support;

import edu.csu.chainpage.engine.common.DbError;
import edu.csu.chainpage.engine.contract.CatalogSnapshot;
import edu.csu.chainpage.engine.contract.CompileRequest;
import edu.csu.chainpage.engine.contract.CompiledStatement;
import edu.csu.chainpage.engine.contract.CompileResponse;
import edu.csu.chainpage.engine.contract.PageData;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FakeClientTest {

    @Test
    void fakeCompilerReturnsQueuedSuccessAndRecordsRequest() {
        FakeSqlCompilerClient client = new FakeSqlCompilerClient();
        CompileResponse response = new CompileResponse(
                "req-1",
                List.of(new CompiledStatement(0, List.of(), null, null, null, null))
        );
        client.enqueueSuccess(response);

        CompileRequest request = new CompileRequest(
                "req-1",
                "SELECT * FROM student;",
                new CatalogSnapshot(List.of()),
                false
        );

        var result = client.compile(request);

        assertTrue(result.isOk());
        assertEquals(response, result.data());
        assertEquals(List.of(request), client.receivedRequests());
    }

    @Test
    void fakeCompilerReturnsQueuedFailure() {
        FakeSqlCompilerClient client = new FakeSqlCompilerClient();
        DbError error = new DbError(
                "req-1", 0, "PARSER", "PARSER_ERROR", "语法错误", 1, 1, null);
        client.enqueueFailure(error);

        var result = client.compile(new CompileRequest(
                "req-1", "bad sql", new CatalogSnapshot(List.of()), false));

        assertFalse(result.isOk());
        assertEquals(error, result.error());
    }

    @Test
    void fakePageStorageManagesTablePagesAndPages() {
        FakePageStorageClient client = new FakePageStorageClient();

        assertTrue(client.createTablePages("req-1", "student").isOk());
        var allocated = client.allocatePageForTable("req-1", "student");

        assertTrue(allocated.isOk());
        int pageId = allocated.data().getPageId();
        var page = client.getPage("req-1", pageId);
        assertTrue(page.isOk());
        assertEquals(pageId, page.data().getPageId());
        assertEquals(1, client.listTablePages("req-1", "student").data().getPageIds().size());
    }

    @Test
    void fakePageStorageCanInjectFailureAndCountCalls() {
        FakePageStorageClient client = new FakePageStorageClient();
        client.failNext(
                FakePageStorageClient.GET_PAGE,
                new DbError("req-1", null, "STORAGE", "FILE_IO_ERROR", "读取失败", null, null, 1)
        );

        var result = client.getPage("req-1", 1);

        assertFalse(result.isOk());
        assertEquals("FILE_IO_ERROR", result.error().getCode());
        assertEquals(1, client.callCount(FakePageStorageClient.GET_PAGE));
    }

    @Test
    void fakePageStorageFlushesDirtyPages() {
        FakePageStorageClient client = new FakePageStorageClient();
        client.createTablePages("req-1", "student");
        int pageId = client.allocatePageForTable("req-1", "student").data().getPageId();
        client.writePage(
                "req-1",
                pageId,
                Base64.getEncoder().encodeToString(new byte[4096])
        );

        var flushed = client.flushAll("req-1");
        PageData page = client.getPage("req-1", pageId).data();

        assertTrue(flushed.isOk());
        assertEquals(List.of(pageId), flushed.data().getFlushedPageIds());
        assertFalse(page.isDirty());
    }
}
