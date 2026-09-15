package edu.csu.chainpage.engine.tx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 验证权限授予、大小写规范化、撤销和越权拒绝
class AuthorizationServiceTest {

    @Test
    void grantsAndRequiresPermissionCaseInsensitively() {
        AuthorizationService authorization = new AuthorizationService();
        authorization.grant("Alice", "select", "Student");

        assertTrue(authorization.check("alice", "SELECT", "student").data());
        assertTrue(authorization.requireAllowed("ALICE", "select", "STUDENT").isOk());
    }

    @Test
    void revokesPermissionAndRejectsUnauthorizedAction() {
        AuthorizationService authorization = new AuthorizationService();
        authorization.grant("alice", "SELECT", "student");
        authorization.revoke("alice", "SELECT", "student");

        var checked = authorization.check("alice", "SELECT", "student");
        var required = authorization.requireAllowed("alice", "SELECT", "student");

        assertTrue(checked.isOk());
        assertFalse(checked.data());
        assertFalse(required.isOk());
        assertEquals("AUTHORIZATION_DENIED", required.error().getCode());
    }
}
