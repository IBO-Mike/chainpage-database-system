package edu.csu.chainpage.engine.api;

import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.contract.CatalogSnapshot;
import edu.csu.chainpage.engine.contract.CompileRequest;
import edu.csu.chainpage.engine.contract.CompileResponse;
import edu.csu.chainpage.engine.contract.CompiledStatement;
import edu.csu.chainpage.engine.lifecycle.DatabaseLifecycle;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

// 数据库系统对外提供的统一请求入口
public final class DatabaseApi {

    private final DatabaseLifecycle lifecycle; // 数据库生命周期管理器
    private final CatalogSnapshotProvider snapshotProvider; // 目录快照提供器
    private final edu.csu.chainpage.engine.contract.SqlCompilerClient compilerClient; // SQL编译器客户端
    private final StatementExecutor statementExecutor; // 计划执行器

    // 创建数据库API
    public DatabaseApi(
            DatabaseLifecycle lifecycle,
            CatalogSnapshotProvider snapshotProvider,
            edu.csu.chainpage.engine.contract.SqlCompilerClient compilerClient,
            StatementExecutor statementExecutor) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle cannot be null");
        this.snapshotProvider = Objects.requireNonNull(snapshotProvider, "snapshotProvider cannot be null");
        this.compilerClient = Objects.requireNonNull(compilerClient, "compilerClient cannot be null");
        this.statementExecutor = Objects.requireNonNull(statementExecutor, "statementExecutor cannot be null");
    }

    // 处理一条数据库入口请求
    public DbResult<DatabaseResponse> handle(
            String requestId,
            DatabaseRequest request) {
        if (request == null) {
            return DbResult.fail(edu.csu.chainpage.engine.common.DbError.invalidRequest(
                    requestId,
                    "请求不能为null"
            ));
        }

        DbResult<Void> validation = request.validate(requestId);
        if (!validation.isOk()) {
            return DbResult.fail(validation.error());
        }

        DbResult<Void> ready = ensureReady(requestId);
        if (!ready.isOk()) {
            return DbResult.fail(ready.error());
        }

        if (request.isCompileMode()) {
            return handleCompile(requestId, request);
        }
        return handleExecute(requestId, request);
    }

    // 处理compile模式请求
    public DbResult<DatabaseResponse> handleCompile(
            String requestId,
            DatabaseRequest request) {
        DbResult<CatalogSnapshot> snapshotResult = snapshotProvider.snapshot(requestId);
        if (!snapshotResult.isOk()) {
            return DbResult.fail(snapshotResult.error());
        }

        DbResult<CompileResponse> compileResult = compilerClient.compile(
                new CompileRequest(
                        requestId,
                        request.getSql(),
                        snapshotResult.data(),
                        true
                )
        );
        if (!compileResult.isOk()) {
            return DbResult.fail(compileResult.error());
        }

        return DbResult.ok(DatabaseResponse.compileSuccess(
                compileResult.data().getStatements()
        ));
    }

    // 处理execute模式请求
    public DbResult<DatabaseResponse> handleExecute(
            String requestId,
            DatabaseRequest request) {
        DbResult<CatalogSnapshot> snapshotResult = snapshotProvider.snapshot(requestId);
        if (!snapshotResult.isOk()) {
            return DbResult.fail(snapshotResult.error());
        }

        DbResult<CompileResponse> compileResult = compilerClient.compile(
                new CompileRequest(
                        requestId,
                        request.getSql(),
                        snapshotResult.data(),
                        true
                )
        );
        if (!compileResult.isOk()) {
            return DbResult.fail(compileResult.error());
        }

        List<StatementExecutionResult> results = new ArrayList<>();
        for (CompiledStatement statement : compileResult.data().getStatements()) {
            Object plan = statement.getOptimizedPlan() != null
                    ? statement.getOptimizedPlan()
                    : statement.getPlan();

            DbResult<StatementExecutionResult> executionResult = statementExecutor.execute(
                    requestId,
                    statement.getStatementIndex(),
                    plan
            );
            if (!executionResult.isOk()) {
                return DbResult.fail(executionResult.error().withStatementIndex(
                        statement.getStatementIndex()
                ));
            }
            results.add(executionResult.data());
        }

        return DbResult.ok(DatabaseResponse.executeSuccess(results));
    }

    // 检查数据库是否已经启动并处于就绪状态
    public DbResult<Void> ensureReady(String requestId) {
        return lifecycle.requireReady(requestId);
    }
}
