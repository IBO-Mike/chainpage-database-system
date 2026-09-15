package edu.csu.chainpage.engine.api;

import edu.csu.chainpage.engine.common.DbError;
import edu.csu.chainpage.engine.contract.CompiledStatement;

import java.util.List;
import java.util.Objects;

// 表示数据库入口返回给调用方的业务结果
public final class DatabaseResponse {

    private final List<?> results; // 编译结果或执行结果
    private final DbError error; // 部分执行失败时的错误信息

    private DatabaseResponse(List<?> results, DbError error) {
        this.results = List.copyOf(Objects.requireNonNull(results, "results cannot be null"));
        this.error = error;
    }

    // 创建编译模式成功响应
    public static DatabaseResponse compileSuccess(List<CompiledStatement> results) {
        return new DatabaseResponse(results, null);
    }

    // 创建执行模式成功响应
    public static DatabaseResponse executeSuccess(List<StatementExecutionResult> results) {
        return new DatabaseResponse(results, null);
    }

    // 创建包含已完成结果的失败响应
    public static DatabaseResponse failure(
            List<StatementExecutionResult> completed,
            DbError error) {
        return new DatabaseResponse(
                completed,
                Objects.requireNonNull(error, "error cannot be null")
        );
    }

    // 获取编译或执行结果列表
    public List<?> getResults() {
        return results;
    }

    // 获取部分执行失败时的错误信息
    public DbError getError() {
        return error;
    }
}
