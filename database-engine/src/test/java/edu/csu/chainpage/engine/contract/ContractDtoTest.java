package edu.csu.chainpage.engine.contract;

import edu.csu.chainpage.engine.common.JsonCodec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContractDtoTest {

    private final JsonCodec codec = new JsonCodec();

    @Test
    void keepsCompileRequestAndResponseFields() {
        CatalogSnapshot snapshot = new CatalogSnapshot(List.of(
                new TableSchema("student", List.of(
                        new ColumnSchema("id", "INT"),
                        new ColumnSchema("name", "VARCHAR")
                ))
        ));
        CompileRequest request = new CompileRequest(
                "req-1",
                "SELECT name FROM student;",
                snapshot,
                true
        );
        CompiledStatement statement = new CompiledStatement(
                0,
                List.of(),
                "ast",
                "semantic",
                "plan",
                null
        );
        CompileResponse response = new CompileResponse("req-1", List.of(statement));

        assertEquals("req-1", request.getRequestId());
        assertTrue(request.isOptimize());
        assertEquals(1, response.getStatements().size());
        assertEquals(0, response.getStatements().get(0).getStatementIndex());
    }

    @Test
    void serializesAndDeserializesCompileRequest() {
        CompileRequest request = new CompileRequest(
                "req-1",
                "SELECT * FROM student;",
                new CatalogSnapshot(List.of()),
                false
        );

        String json = codec.write(request);
        CompileRequest restored = codec.read(json, CompileRequest.class);

        assertEquals(request.getRequestId(), restored.getRequestId());
        assertEquals(request.getSql(), restored.getSql());
        assertFalse(restored.isOptimize());
        assertTrue(restored.getCatalogSnapshot().getTables().isEmpty());
    }

    @Test
    void keepsPageStorageResponseFields() {
        PageData pageData = new PageData("req-1", 12, "base64", true, false);
        TablePages tablePages = new TablePages("req-1", "student", List.of(12));
        AllocatedPage allocatedPage = new AllocatedPage("req-1", "student", 13, List.of(12, 13));
        DropTablePagesResult dropped = new DropTablePagesResult(
                "req-1", "student", true, List.of(12, 13));
        FlushResult flushed = new FlushResult("req-1", List.of(12, 13));
        WritePageResult written = new WritePageResult("req-1", 12, true);

        assertEquals(12, pageData.getPageId());
        assertEquals(List.of(12), tablePages.getPageIds());
        assertEquals(13, allocatedPage.getPageId());
        assertTrue(dropped.isRemoved());
        assertEquals(2, flushed.getFlushedPageIds().size());
        assertTrue(written.isDirty());
    }
}
