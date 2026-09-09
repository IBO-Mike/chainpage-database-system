package edu.csu.chainpage.engine.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import edu.csu.chainpage.engine.common.DbError;
import edu.csu.chainpage.engine.common.DbResult;

// 表示数据库入口接收到的一次用户请求
public final class DatabaseRequest {

    private final String sql; // 用户提交的SQL文本
    private final String mode; // 请求模式，只允许compile或execute

    // 创建数据库请求
    @JsonCreator
    public DatabaseRequest(
            @JsonProperty("sql") String sql,
            @JsonProperty("mode") String mode) {
        this.sql = sql;
        this.mode = mode;
    }

    // 获取用户提交的SQL文本
    public String getSql() {
        return sql;
    }

    // 获取请求模式
    public String getMode() {
        return mode;
    }

    // 判断请求是否为execute模式
    public boolean isExecuteMode() {
        return "execute".equals(mode);
    }

    // 判断请求是否为compile模式
    public boolean isCompileMode() {
        return "compile".equals(mode);
    }

    // 校验请求字段是否合法
    public DbResult<Void> validate(String requestId) {
        if (sql == null || sql.isBlank()) {
            return DbResult.fail(DbError.invalidRequest(requestId, "sql不能为空"));
        }
        if (!isExecuteMode() && !isCompileMode()) {
            return DbResult.fail(DbError.invalidRequest(
                    requestId,
                    "mode只能是execute或compile"
            ));
        }
        return DbResult.ok(null);
    }
}
