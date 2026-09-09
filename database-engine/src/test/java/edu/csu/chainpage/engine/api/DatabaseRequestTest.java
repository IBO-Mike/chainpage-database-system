package edu.csu.chainpage.engine.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseRequestTest {

    @Test
    void acceptsCompileAndExecuteModes() {
        assertTrue(new DatabaseRequest("SELECT 1;", "compile")
                .validate("req-1").isOk());
        assertTrue(new DatabaseRequest("SELECT 1;", "execute")
                .validate("req-2").isOk());
    }

    @Test
    void rejectsBlankSqlAndUnknownMode() {
        assertFalse(new DatabaseRequest(" ", "execute")
                .validate("req-1").isOk());
        assertFalse(new DatabaseRequest("SELECT 1;", "other")
                .validate("req-2").isOk());
    }
}
