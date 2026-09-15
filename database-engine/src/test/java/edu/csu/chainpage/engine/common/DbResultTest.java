package edu.csu.chainpage.engine.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbResultTest {

    @Test
    void createsSuccessfulResult() {
        DbResult<String> result = DbResult.ok("data");

        assertTrue(result.isOk());
        assertEquals("data", result.data());
        assertNull(result.error());
    }

    @Test
    void createsFailedResult() {
        DbError error = DbError.executor("req-1", 0, "E001", "failed");
        DbResult<String> result = DbResult.fail(error);

        assertFalse(result.isOk());
        assertNull(result.data());
        assertEquals(error, result.error());
    }

    @Test
    void rejectsNullFailureError() {
        assertThrows(NullPointerException.class, () -> DbResult.fail(null));
    }
}
