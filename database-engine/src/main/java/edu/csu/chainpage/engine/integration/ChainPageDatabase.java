package edu.csu.chainpage.engine.integration;

import edu.csu.chainpage.engine.api.DatabaseApi;
import edu.csu.chainpage.engine.api.DatabaseCli;
import edu.csu.chainpage.engine.api.StatementExecutionResult;
import edu.csu.chainpage.engine.catalog.StorageCatalogRepository;
import edu.csu.chainpage.engine.catalog.SystemCatalogManager;
import edu.csu.chainpage.engine.common.DbError;
import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.common.JsonCodec;
import edu.csu.chainpage.engine.executor.CommandResult;
import edu.csu.chainpage.engine.executor.ExecutionValue;
import edu.csu.chainpage.engine.executor.core.CreateTableExecutor;
import edu.csu.chainpage.engine.executor.core.DeleteExecutor;
import edu.csu.chainpage.engine.executor.core.FilterExecutor;
import edu.csu.chainpage.engine.executor.core.InsertExecutor;
import edu.csu.chainpage.engine.executor.core.ProjectExecutor;
import edu.csu.chainpage.engine.executor.core.SeqScanExecutor;
import edu.csu.chainpage.engine.executor.extended.GroupByExecutor;
import edu.csu.chainpage.engine.executor.extended.IndexScanExecutor;
import edu.csu.chainpage.engine.executor.extended.JoinExecutor;
import edu.csu.chainpage.engine.executor.extended.SortExecutor;
import edu.csu.chainpage.engine.executor.extended.UpdateExecutor;
import edu.csu.chainpage.engine.lifecycle.DatabaseLifecycle;
import edu.csu.chainpage.engine.lifecycle.RecoveryService;
import edu.csu.chainpage.engine.plan.OptimizedPlanExecutor;
import edu.csu.chainpage.engine.plan.PlanDispatcher;
import edu.csu.chainpage.engine.plan.PlanNode;
import edu.csu.chainpage.engine.plan.PlanParser;
import edu.csu.chainpage.engine.storage.StorageEngine;
import edu.csu.chainpage.engine.tx.TransactionalPageStorageClient;
import edu.csu.chainpage.engine.tx.AuthorizationService;
import edu.csu.chainpage.engine.tx.TransactionManager;
import org.chainpage.storage.StorageManager;

import java.nio.file.Path;
import java.util.Objects;

/** 组装并管理三个真实模块的数据库入口。 */
public final class ChainPageDatabase implements AutoCloseable {
    private final StorageManager storageManager;
    private final DatabaseLifecycle lifecycle;
    private final DatabaseApi api;
    private final DatabaseCli cli;
    private final PlanDispatcher dispatcher;
    private final AuthorizationService authorization;
    private final TransactionManager transactions;
    private boolean closed;

    public ChainPageDatabase(Path dataDirectory) {
        storageManager = new StorageManager(Objects.requireNonNull(dataDirectory, "dataDirectory cannot be null"));
        PageStorageModuleClient physicalPages = new PageStorageModuleClient(storageManager);
        TransactionalPageStorageClient pages = new TransactionalPageStorageClient(physicalPages);
        StorageEngine storage = new StorageEngine(pages);
        SystemCatalogManager catalog = new SystemCatalogManager(new StorageCatalogRepository(storage));
        dispatcher = new PlanDispatcher();
        registerExecutors(storage, catalog);
        authorization = new AuthorizationService();
        transactions = new TransactionManager(dispatcher, pages, storage, catalog,
                new edu.csu.chainpage.engine.tx.LockManager(), authorization,
                new edu.csu.chainpage.engine.tx.PlanActionResolver());
        lifecycle = new DatabaseLifecycle(new RecoveryService(requestId -> {
            DbResult<Void> initialized = catalog.initialize(requestId);
            return initialized.isOk()
                    ? DbResult.ok(catalog.snapshot())
                    : DbResult.fail(initialized.error());
        }, storage), pages);
        CompilerModuleClient compiler = new CompilerModuleClient();
        api = new DatabaseApi(lifecycle, requestId -> DbResult.ok(catalog.snapshot()), compiler,
                this::executePlan, new OptimizedPlanExecutor(dispatcher));
        cli = new DatabaseCli(api, new JsonCodec());

        DbResult<?> started = lifecycle.startup("startup");
        if (!started.isOk()) {
            storageManager.close();
            throw new DatabaseStartupException(started.error());
        }
    }

    public DatabaseApi api() {
        return api;
    }

    public DatabaseCli cli() {
        return cli;
    }

    /** 事务 API；执行前依次检查授权并获取表锁。 */
    public TransactionManager transactions() {
        return transactions;
    }

    /** 与事务管理器共享的表级授权策略。 */
    public AuthorizationService authorization() {
        return authorization;
    }

    public DbResult<?> shutdown() {
        if (closed) {
            return DbResult.ok(null);
        }
        DbResult<?> result = lifecycle.shutdown("shutdown");
        if (result.isOk()) {
            storageManager.close();
            closed = true;
        }
        return result;
    }

    @Override
    public void close() {
        DbResult<?> result = shutdown();
        if (!result.isOk()) {
            throw new IllegalStateException("数据库关闭失败：" + result.error().getMessage());
        }
    }

    private void registerExecutors(StorageEngine storage, SystemCatalogManager catalog) {
        dispatcher.register(new CreateTableExecutor(storage, catalog));
        dispatcher.register(new InsertExecutor(storage, catalog));
        dispatcher.register(new SeqScanExecutor(storage, catalog));
        dispatcher.register(new FilterExecutor(dispatcher));
        dispatcher.register(new ProjectExecutor(dispatcher));
        dispatcher.register(new DeleteExecutor(storage, catalog));
        dispatcher.register(new UpdateExecutor(storage, catalog));
        dispatcher.register(new SortExecutor(dispatcher));
        dispatcher.register(new GroupByExecutor(dispatcher));
        dispatcher.register(new JoinExecutor(dispatcher));
        dispatcher.register(new IndexScanExecutor(storage, catalog));
    }

    private DbResult<StatementExecutionResult> executePlan(String requestId, int statementIndex, Object rawPlan) {
        DbResult<PlanNode> parsed = new PlanParser().parse(rawPlan);
        if (!parsed.isOk()) {
            return DbResult.fail(withRequest(parsed.error(), requestId, statementIndex));
        }
        DbResult<ExecutionValue> executed = dispatcher.execute(requestId, parsed.data());
        if (!executed.isOk()) {
            return DbResult.fail(withRequest(executed.error(), requestId, statementIndex));
        }
        if (executed.data() == null || executed.data().commandResult() == null) {
            return DbResult.fail(new DbError(requestId, statementIndex, "EXECUTOR", "EXECUTOR_INVALID_RESULT",
                    "计划没有返回用户可见结果", null, null, null));
        }
        CommandResult command = executed.data().commandResult();
        return DbResult.ok(new StatementExecutionResult(statementIndex, command.kind(), command));
    }

    private static DbError withRequest(DbError source, String requestId, int statementIndex) {
        return new DbError(requestId, statementIndex, source.getStage(), source.getCode(), source.getMessage(),
                source.getLine(), source.getColumn(), source.getPageId());
    }

    public static final class DatabaseStartupException extends IllegalStateException {
        private final DbError error;

        DatabaseStartupException(DbError error) {
            super(error.getMessage());
            this.error = error;
        }

        public DbError error() {
            return error;
        }
    }
}
