package edu.csu.chainpage.engine.support;

import edu.csu.chainpage.engine.common.DbError;
import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.contract.CompileRequest;
import edu.csu.chainpage.engine.contract.CompileResponse;
import edu.csu.chainpage.engine.contract.SqlCompilerClient;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

// 用于独立测试的SQL编译器模拟客户端
public final class FakeSqlCompilerClient implements SqlCompilerClient {

    private final Deque<DbResult<CompileResponse>> responses = new ArrayDeque<>(); // 按顺序返回的模拟结果
    private final List<CompileRequest> receivedRequests = new ArrayList<>(); // 已收到的编译请求

    // 预置一条编译成功结果
    public void enqueueSuccess(CompileResponse response) {
        responses.addLast(DbResult.ok(Objects.requireNonNull(response, "response cannot be null")));
    }

    // 预置一条编译失败结果
    public void enqueueFailure(DbError error) {
        responses.addLast(DbResult.fail(Objects.requireNonNull(error, "error cannot be null")));
    }

    // 接收数据库引擎发来的编译请求并返回下一条预置结果
    @Override
    public DbResult<CompileResponse> compile(CompileRequest request) {
        CompileRequest nonNullRequest = Objects.requireNonNull(request, "request cannot be null");
        receivedRequests.add(nonNullRequest);

        if (responses.isEmpty()) {
            return DbResult.fail(new DbError(
                    nonNullRequest.getRequestId(),
                    null,
                    "COMPILER",
                    "FAKE_NO_RESPONSE",
                    "模拟SQL编译器没有预置返回结果",
                    null,
                    null,
                    null
            ));
        }

        return responses.removeFirst();
    }

    // 获取已经收到的编译请求
    public List<CompileRequest> receivedRequests() {
        return List.copyOf(receivedRequests);
    }

    // 清空已经记录的编译请求
    public void clearCalls() {
        receivedRequests.clear();
    }
}
