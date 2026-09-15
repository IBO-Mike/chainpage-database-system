package edu.csu.chainpage.engine.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

// 表示SQL编译器成功处理后的结果
public final class CompileResponse {
    private final String requestId; // 本次编译请求的编号
    private final List<CompiledStatement> statements; // 每条SQL语句的编译结果

    // 创建SQL编译器的成功响应
    @JsonCreator
    public CompileResponse(
            @JsonProperty("requestId") String requestId,
            @JsonProperty("statements") List<CompiledStatement> statements) {
        this.requestId = requestId;
        this.statements = List.copyOf(Objects.requireNonNull(statements, "statements cannot be null"));
    }

    // 获取本次编译请求的编号
    public String getRequestId() {
        return requestId;
    }

    // 获取按输入顺序排列的语句编译结果
    public List<CompiledStatement> getStatements() {
        return statements;
    }
}
