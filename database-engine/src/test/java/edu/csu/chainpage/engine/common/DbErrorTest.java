package edu.csu.chainpage.engine.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DbErrorTest {

    @Test
    void createsExecutorError() {
        DbError error = DbError.executor("req-1", 2, "E001", "failed");

        assertEquals("req-1", error.getRequestId());
        assertEquals(2, error.getStatementIndex());
        assertEquals("EXECUTOR", error.getStage());
        assertEquals("E001", error.getCode());
        assertEquals("failed", error.getMessage());
    }

    @Test
    void createsApiInvalidRequestError() {
        DbError error = DbError.invalidRequest("req-1", "bad request");

        assertEquals("API", error.getStage());
        assertEquals("INVALID_REQUEST", error.getCode());
        assertEquals("bad request", error.getMessage());
    }

    @Test
    void copiesErrorWithStatementIndex() {
        DbError original = DbError.invalidRequest("req-1", "bad request");
        DbError updated = original.withStatementIndex(3);

        assertEquals(3, updated.getStatementIndex());
        assertEquals("req-1", updated.getRequestId());
        assertEquals(original.getCode(), updated.getCode());
    }
}
