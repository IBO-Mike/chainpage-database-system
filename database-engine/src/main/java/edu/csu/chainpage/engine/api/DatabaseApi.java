package edu.csu.chainpage.engine.api;

import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.contract.CatalogSnapshot;
import edu.csu.chainpage.engine.contract.CompileRequest;
import edu.csu.chainpage.engine.contract.CompileResponse;
import edu.csu.chainpage.engine.contract.CompiledStatement;
import edu.csu.chainpage.engine.explain.ExplainResult;
import edu.csu.chainpage.engine.explain.ExplainService;
import edu.csu.chainpage.engine.lifecycle.DatabaseLifecycle;
import edu.csu.chainpage.engine.plan.OptimizedExecutionRequest;
import edu.csu.chainpage.engine.plan.OptimizedExecutionResult;
import edu.csu.chainpage.engine.plan.OptimizedPlanExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

// 数据库系统对外提供的统一请求入口
public final class DatabaseApi {

    private final DatabaseLifecycle lifecycle; // 数据库生命周期管理器
    private final CatalogSnapshotProvider snapshotProvider; // 目录快照提供器
    private final edu.csu.chainpage.engine.contract.SqlCompilerClient compilerClient; // SQL编译器客户端
    private final StatementExecutor statementExecutor; // 计划执行器
    private final OptimizedPlanExecutor optimizedPlanExecutor; // 优化计划执行器
    private final ExplainService explainService; // EXPLAIN请求服务

    // 创建数据库API
    public DatabaseApi(
            DatabaseLifecycle lifecycle,
            CatalogSnapshotProvider snapshotProvider,
            edu.csu.chainpage.engine.contract.SqlCompilerClient compilerClient,
            StatementExecutor statementExecutor) {
        this(lifecycle, snapshotProvider, compilerClient, statementExecutor, null);
    }

    // 创建包含优化计划执行器的数据库API
    public DatabaseApi(
            DatabaseLifecycle lifecycle,
            CatalogSnapshotProvider snapshotProvider,
            edu.csu.chainpage.engine.contract.SqlCompilerClient compilerClient,
            StatementExecutor statementExecutor,
            OptimizedPlanExecutor optimizedPlanExecutor) {
        this(
                lifecycle,
                snapshotProvider,
                compilerClient,
                statementExecutor,
                optimizedPlanExecutor,
                new ExplainService(snapshotProvider, compilerClient)
        );
    }

    // 创建包含全部可替换依赖的数据库API
    public DatabaseApi(
            DatabaseLifecycle lifecycle,
            CatalogSnapshotProvider snapshotProvider,
            edu.csu.chainpage.engine.contract.SqlCompilerClient compilerClient,
            StatementExecutor statementExecutor,
            OptimizedPlanExecutor optimizedPlanExecutor,
            ExplainService explainService) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle cannot be null");
        this.snapshotProvider = Objects.requireNonNull(snapshotProvider, "snapshotProvider cannot be null");
        this.compilerClient = Objects.requireNonNull(compilerClient, "compilerClient cannot be null");
        this.statementExecutor = Objects.requireNonNull(statementExecutor, "statementExecutor cannot be null");
        this.optimizedPlanExecutor = optimizedPlanExecutor;
        this.explainService = Objects.requireNonNull(explainService, "explainService cannot be null");
    }

    // 提供优化执行器位于普通语句执行器之前的构造顺序
    public DatabaseApi(
            DatabaseLifecycle lifecycle,
            CatalogSnapshotProvider snapshotProvider,
            edu.csu.chainpage.engine.contract.SqlCompilerClient compilerClient,
            OptimizedPlanExecutor optimizedPlanExecutor,
            StatementExecutor statementExecutor) {
        this(lifecycle, snapshotProvider, compilerClient, statementExecutor, optimizedPlanExecutor);
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
                return DbResult.ok(DatabaseResponse.failure(
                        results,
                        executionResult.error().withStatementIndex(
                                statement.getStatementIndex()
                        )
                ));
            }
            results.add(executionResult.data());
        }

        return DbResult.ok(DatabaseResponse.executeSuccess(results));
    }

    // 验证就绪状态后执行优化计划，并返回实际计划与规则信息
    public DbResult<OptimizedExecutionResult> executeOptimized(
            String requestId,
            OptimizedExecutionRequest request) {
        DbResult<Void> ready = ensureReady(requestId);
        if (!ready.isOk()) {
            return DbResult.fail(ready.error());
        }
        if (optimizedPlanExecutor == null) {
            return DbResult.fail(edu.csu.chainpage.engine.common.DbError.executor(
                    requestId,
                    null,
                    "EXECUTOR_OPTIMIZED_EXECUTOR_UNAVAILABLE",
                    "数据库API未配置优化计划执行器"
            ));
        }
        return optimizedPlanExecutor.execute(requestId, request);
    }

    // 验证并处理EXPLAIN请求，且不把计划交给任何执行器
    public DbResult<ExplainResult> handleExplain(String requestId, String sql) {
        DbResult<Void> validation = explainService.validateExplainInput(sql);
        if (!validation.isOk()) {
            return DbResult.fail(new edu.csu.chainpage.engine.common.DbError(
                    requestId,
                    validation.error().getStatementIndex(),
                    validation.error().getStage(),
                    validation.error().getCode(),
                    validation.error().getMessage(),
                    validation.error().getLine(),
                    validation.error().getColumn(),
                    validation.error().getPageId()
            ));
        }

        DbResult<Void> ready = ensureReady(requestId);
        if (!ready.isOk()) {
            return DbResult.fail(ready.error());
        }
        return explainService.explain(requestId, sql);
    }

    // 检查数据库是否已经启动并处于就绪状态
    public DbResult<Void> ensureReady(String requestId) {
        return lifecycle.requireReady(requestId);
    }
}
