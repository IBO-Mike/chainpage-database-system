package edu.csu.chainpage.engine.tx;

import edu.csu.chainpage.engine.common.DbError;
import edu.csu.chainpage.engine.common.DbResult;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

// 保存用户的表级操作权限并执行访问检查
public final class AuthorizationService {

    private final Set<Permission> permissions = new HashSet<>(); // 已经授予的权限集合

    // 检查用户是否具有指定表操作权限
    public synchronized DbResult<Boolean> check(String user, String action, String table) {
        Permission permission = normalizePermission(user, action, table);
        if (permission == null) {
            return failure("AUTHORIZATION_INVALID_REQUEST", "权限检查参数不能为空");
        }
        return DbResult.ok(permissions.contains(permission));
    }

    // 要求用户必须具有指定权限
    public DbResult<Void> requireAllowed(String user, String action, String table) {
        DbResult<Boolean> checked = check(user, action, table);
        if (!checked.isOk()) {
            return DbResult.fail(checked.error());
        }
        if (!checked.data()) {
            return failure(
                    "AUTHORIZATION_DENIED",
                    "用户无权执行" + action + "操作：" + table
            );
        }
        return DbResult.ok(null);
    }

    // 授予用户一项表操作权限
    public synchronized void grant(String user, String action, String table) {
        Permission permission = normalizePermission(user, action, table);
        if (permission == null) {
            throw new IllegalArgumentException("permission fields cannot be blank");
        }
        permissions.add(permission);
    }

    // 撤销用户的一项表操作权限
    public synchronized void revoke(String user, String action, String table) {
        Permission permission = normalizePermission(user, action, table);
        if (permission == null) {
            throw new IllegalArgumentException("permission fields cannot be blank");
        }
        permissions.remove(permission);
    }

    // 规范化一项权限的用户、动作和表名
    private Permission normalizePermission(String user, String action, String table) {
        if (user == null || user.isBlank() || action == null || action.isBlank()
                || table == null || table.isBlank()) {
            return null;
        }
        return new Permission(
                user.toLowerCase(Locale.ROOT),
                action.toUpperCase(Locale.ROOT),
                table.toLowerCase(Locale.ROOT)
        );
    }

    // 创建访问控制阶段错误
    private <T> DbResult<T> failure(String code, String message) {
        return DbResult.fail(new DbError(
                null,
                null,
                "AUTHORIZATION",
                code,
                message,
                null,
                null,
                null
        ));
    }

    // 保存一项已经规范化的不可变权限
    private record Permission(String user, String action, String table) {
    }
}
