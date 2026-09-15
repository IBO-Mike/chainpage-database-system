package edu.csu.chainpage.engine.integration;

import edu.csu.chainpage.engine.api.DatabaseApi;
import edu.csu.chainpage.engine.api.DatabaseCli;
import edu.csu.chainpage.engine.api.CliOutputFormat;
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
    // 负责页、缓冲池、日志和表页映射等底层持久化工作的存储管理器。
    private final StorageManager storageManager;
    // 管理数据库启动、就绪检查、刷新和关闭流程的生命周期对象。
    private final DatabaseLifecycle lifecycle;
    // 对外提供SQL编译、执行和EXPLAIN能力的数据库API。
    private final DatabaseApi api;
    // 负责从标准输入读取请求并向标准输出打印响应的命令行接口。
    private final DatabaseCli cli;
    // 根据执行计划的kind选择并调用对应执行器的计划分派器。
    private final PlanDispatcher dispatcher;
    // 保存当前数据库使用的表级授权策略。
    private final AuthorizationService authorization;
    // 管理事务创建、提交、回滚和事务内计划执行的事务管理器。
    private final TransactionManager transactions;
    // 标记数据库是否已经完成关闭，避免重复关闭底层资源。
    private boolean closed;

    // 创建并启动一个默认使用人类可读文本输出的完整数据库实例。
    public ChainPageDatabase(Path dataDirectory) {
        this(dataDirectory, CliOutputFormat.HUMAN);
    }

    // 创建并启动一个可以选择命令行输出格式的完整数据库实例。
    public ChainPageDatabase(Path dataDirectory, CliOutputFormat outputFormat) {
        CliOutputFormat selectedOutputFormat = Objects.requireNonNull(outputFormat, "outputFormat cannot be null");
        // 创建页式存储管理器，并打开或初始化数据目录。
        storageManager = new StorageManager(Objects.requireNonNull(dataDirectory, "dataDirectory cannot be null"));
        // 创建把数据库引擎请求转换为页式存储请求的物理客户端。
        PageStorageModuleClient physicalPages = new PageStorageModuleClient(storageManager);
        // 在物理客户端外增加事务写入前镜像记录能力。
        TransactionalPageStorageClient pages = new TransactionalPageStorageClient(physicalPages);
        // 创建面向表和记录的存储引擎。
        StorageEngine storage = new StorageEngine(pages);
        // 创建使用存储引擎持久化系统目录的目录管理器。
        SystemCatalogManager catalog = new SystemCatalogManager(new StorageCatalogRepository(storage));
        // 创建执行计划分派器。
        dispatcher = new PlanDispatcher();
        // 登记CREATE、INSERT、SELECT等计划节点对应的执行器。
        registerExecutors(storage, catalog);
        // 创建表级授权服务。
        authorization = new AuthorizationService();
        // 创建与计划分派器、页客户端、存储引擎和目录共享依赖的事务管理器。
        transactions = new TransactionManager(dispatcher, pages, storage, catalog,
                new edu.csu.chainpage.engine.tx.LockManager(), authorization,
                new edu.csu.chainpage.engine.tx.PlanActionResolver());
        // 创建数据库生命周期管理器，并提供启动时的目录恢复逻辑。
        lifecycle = new DatabaseLifecycle(new RecoveryService(requestId -> {
            // 初始化系统目录并读取恢复后的目录快照。
            DbResult<Void> initialized = catalog.initialize(requestId);
            return initialized.isOk()
                    ? DbResult.ok(catalog.snapshot())
                    : DbResult.fail(initialized.error());
        }, storage), pages);
        // 创建SQL编译器模块客户端。
        CompilerModuleClient compiler = new CompilerModuleClient();
        // 组装数据库API；计划执行请求最终由当前对象的executePlan方法处理。
        api = new DatabaseApi(lifecycle, requestId -> DbResult.ok(catalog.snapshot()), compiler,
                this::executePlan, new OptimizedPlanExecutor(dispatcher));
        // 创建使用统一JSON编解码器和指定显示格式的命令行接口。
        cli = new DatabaseCli(api, new JsonCodec(),
                selectedOutputFormat);

        // 启动数据库，完成恢复、目录加载和存储校验。
        DbResult<?> started = lifecycle.startup("startup");
        if (!started.isOk()) {
            // 启动失败时立即释放已经打开的底层存储资源。
            storageManager.close();
            // 将启动错误转换为专用异常交给调用方处理。
            throw new DatabaseStartupException(started.error());
        }
    }

    // 返回可供程序化调用的数据库API。
    public DatabaseApi api() {
        return api;
    }

    // 返回负责读取命令行输入和打印响应的CLI对象。
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

    // 刷新脏页、关闭生命周期并释放底层存储资源。
    public DbResult<?> shutdown() {
        if (closed) {
            return DbResult.ok(null);
        }
        // 请求生命周期管理器刷新页式存储中的所有脏页。
        DbResult<?> result = lifecycle.shutdown("shutdown");
        if (result.isOk()) {
            // 只有刷新成功后才关闭存储管理器并记录关闭状态。
            storageManager.close();
            closed = true;
        }
        return result;
    }

    // 实现AutoCloseable，使try-with-resources能够安全关闭数据库。
    @Override
    public void close() {
        // 调用统一关闭流程并检查关闭结果。
        DbResult<?> result = shutdown();
        if (!result.isOk()) {
            throw new IllegalStateException("数据库关闭失败：" + result.error().getMessage());
        }
    }

    // 向计划分派器登记所有当前支持的计划执行器。
    private void registerExecutors(StorageEngine storage, SystemCatalogManager catalog) {
        // 登记建表执行器。
        dispatcher.register(new CreateTableExecutor(storage, catalog));
        // 登记插入执行器。
        dispatcher.register(new InsertExecutor(storage, catalog));
        // 登记顺序扫描执行器。
        dispatcher.register(new SeqScanExecutor(storage, catalog));
        // 登记过滤执行器。
        dispatcher.register(new FilterExecutor(dispatcher));
        // 登记投影执行器。
        dispatcher.register(new ProjectExecutor(dispatcher));
        // 登记删除执行器。
        dispatcher.register(new DeleteExecutor(storage, catalog));
        // 登记更新执行器。
        dispatcher.register(new UpdateExecutor(storage, catalog));
        // 登记排序执行器。
        dispatcher.register(new SortExecutor(dispatcher));
        // 登记分组聚合执行器。
        dispatcher.register(new GroupByExecutor(dispatcher));
        // 登记连接执行器。
        dispatcher.register(new JoinExecutor(dispatcher));
        // 登记索引扫描执行器。
        dispatcher.register(new IndexScanExecutor(storage, catalog));
    }

    // 将编译器返回的原始计划解析、执行并转换为单条语句结果。
    private DbResult<StatementExecutionResult> executePlan(String requestId, int statementIndex, Object rawPlan) {
        // 将JSON形式的原始计划解析为引擎内部计划节点。
        DbResult<PlanNode> parsed = new PlanParser().parse(rawPlan);
        if (!parsed.isOk()) {
            // 解析失败时补充当前请求编号和语句序号后返回。
            return DbResult.fail(withRequest(parsed.error(), requestId, statementIndex));
        }
        // 由计划分派器执行解析后的计划。
        DbResult<ExecutionValue> executed = dispatcher.execute(requestId, parsed.data());
        if (!executed.isOk()) {
            // 执行失败时补充当前请求编号和语句序号后返回。
            return DbResult.fail(withRequest(executed.error(), requestId, statementIndex));
        }
        if (executed.data() == null || executed.data().commandResult() == null) {
            // 普通SQL语句必须返回最终命令结果，不能只返回内部行集或空值。
            return DbResult.fail(new DbError(requestId, statementIndex, "EXECUTOR", "EXECUTOR_INVALID_RESULT",
                    "计划没有返回用户可见结果", null, null, null));
        }
        // 取得执行器生成的用户可见命令结果。
        CommandResult command = executed.data().commandResult();
        // 将命令结果包装为带有语句序号和结果种类的执行结果。
        return DbResult.ok(new StatementExecutionResult(statementIndex, command.kind(), command));
    }

    // 为下层错误补充当前请求编号和语句序号，保持统一错误格式。
    private static DbError withRequest(DbError source, String requestId, int statementIndex) {
        return new DbError(requestId, statementIndex, source.getStage(), source.getCode(), source.getMessage(),
                source.getLine(), source.getColumn(), source.getPageId());
    }

    // 表示数据库启动阶段失败的专用运行时异常。
    public static final class DatabaseStartupException extends IllegalStateException {
        // 保存启动失败时的结构化数据库错误。
        private final DbError error;

        // 使用具体数据库错误创建启动异常。
        DatabaseStartupException(DbError error) {
            super(error.getMessage());
            this.error = error;
        }

        // 返回导致数据库启动失败的结构化错误。
        public DbError error() {
            return error;
        }
    }
}
