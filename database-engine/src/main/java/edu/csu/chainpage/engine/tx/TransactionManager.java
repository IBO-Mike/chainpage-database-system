package edu.csu.chainpage.engine.tx;

import edu.csu.chainpage.engine.catalog.SystemCatalogManager;
import edu.csu.chainpage.engine.common.DbError;
import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.contract.ColumnSchema;
import edu.csu.chainpage.engine.contract.FlushResult;
import edu.csu.chainpage.engine.contract.TableSchema;
import edu.csu.chainpage.engine.executor.ExecutionValue;
import edu.csu.chainpage.engine.plan.JsonPlanNode;
import edu.csu.chainpage.engine.plan.PlanDispatcher;
import edu.csu.chainpage.engine.plan.PlanNode;
import edu.csu.chainpage.engine.storage.StorageEngine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

// 协调事务状态、访问授权、表锁、计划执行和提交回滚
public final class TransactionManager {

    private final PlanDispatcher dispatcher; // 执行已经验证的计划
    private final TransactionalPageStorageClient pageStorageClient; // 记录页前镜像并执行刷盘
    private final StorageEngine storageEngine; // 回滚新建表的物理存储
    private final SystemCatalogManager catalogManager; // 回滚后重新加载目录内存视图
    private final LockManager lockManager; // 表级锁管理器
    private final AuthorizationService authorizationService; // 用户访问控制服务
    private final PlanActionResolver actionResolver; // 计划动作和目标表解析器
    private final AtomicLong nextTxId = new AtomicLong(1); // 下一个事务编号
    private final Map<Long, TransactionContext> transactions = new LinkedHashMap<>(); // 全部事务上下文

    // 使用默认锁、授权和计划解析服务创建事务管理器
    public TransactionManager(
            PlanDispatcher dispatcher,
            TransactionalPageStorageClient pageStorageClient,
            StorageEngine storageEngine,
            SystemCatalogManager catalogManager) {
        this(
                dispatcher,
                pageStorageClient,
                storageEngine,
                catalogManager,
                new LockManager(),
                new AuthorizationService(),
                new PlanActionResolver()
        );
    }

    // 使用全部可替换依赖创建便于独立测试的事务管理器
    public TransactionManager(
            PlanDispatcher dispatcher,
            TransactionalPageStorageClient pageStorageClient,
            StorageEngine storageEngine,
            SystemCatalogManager catalogManager,
            LockManager lockManager,
            AuthorizationService authorizationService,
            PlanActionResolver actionResolver) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher cannot be null");
        this.pageStorageClient = Objects.requireNonNull(pageStorageClient, "pageStorageClient cannot be null");
        this.storageEngine = Objects.requireNonNull(storageEngine, "storageEngine cannot be null");
        this.catalogManager = Objects.requireNonNull(catalogManager, "catalogManager cannot be null");
        this.lockManager = Objects.requireNonNull(lockManager, "lockManager cannot be null");
        this.authorizationService = Objects.requireNonNull(
                authorizationService,
                "authorizationService cannot be null"
        );
        this.actionResolver = Objects.requireNonNull(actionResolver, "actionResolver cannot be null");
    }

    // 开始一个新的活动事务
    public synchronized DbResult<Transaction> begin(String session) {
        if (session == null || session.isBlank()) {
            return failure(null, "TX_INVALID_SESSION", "事务会话不能为空");
        }
        Transaction transaction = new Transaction(nextTxId.getAndIncrement(), session);
        transactions.put(transaction.txId(), new TransactionContext(transaction));
        return DbResult.ok(transaction);
    }

    // 按权限检查、获取锁、执行计划的固定顺序处理事务语句
    public synchronized DbResult<ExecutionValue> execute(
            String requestId,
            long txId,
            PlanNode plan,
            String user) {
        DbResult<Transaction> active = requireActive(txId);
        if (!active.isOk()) {
            return DbResult.fail(withRequest(active.error(), requestId));
        }
        DbResult<String> actionResult = actionResolver.resolveAction(plan);
        if (!actionResult.isOk()) {
            return DbResult.fail(withRequest(actionResult.error(), requestId));
        }
        DbResult<String> tableResult = actionResolver.resolveTable(plan);
        if (!tableResult.isOk()) {
            return DbResult.fail(withRequest(tableResult.error(), requestId));
        }

        String action = actionResult.data();
        List<String> tables = List.of(tableResult.data().split(","));
        for (String table : tables) {
            DbResult<Void> allowed = authorizationService.requireAllowed(user, action, table);
            if (!allowed.isOk()) {
                return DbResult.fail(withRequest(allowed.error(), requestId));
            }
        }
        for (String table : tables) {
            DbResult<Void> locked = "SELECT".equals(action)
                    ? lockManager.acquireRead(requestId, txId, table)
                    : lockManager.acquireWrite(requestId, txId, table);
            if (!locked.isOk()) {
                return DbResult.fail(locked.error());
            }
        }

        TransactionContext context = context(txId);
        pageStorageClient.activate(context);
        final DbResult<ExecutionValue> executed;
        try {
            executed = dispatcher.execute(requestId, plan);
        } finally {
            pageStorageClient.deactivate();
        }
        if (!executed.isOk()) {
            return DbResult.fail(executed.error());
        }
        if ("CREATE".equals(action)) {
            DbResult<TableSchema> schema = schemaFromCreatePlan(requestId, plan);
            if (!schema.isOk()) {
                return DbResult.fail(schema.error());
            }
            context.recordCatalogChange(new CatalogChange("CREATE", schema.data()));
        }
        return executed;
    }

    // 刷新事务修改并在成功后标记提交、释放全部锁
    public synchronized DbResult<Void> commit(String requestId, long txId) {
        DbResult<Transaction> active = requireActive(txId);
        if (!active.isOk()) {
            return DbResult.fail(withRequest(active.error(), requestId));
        }
        DbResult<FlushResult> flushed = pageStorageClient.flushAll(requestId);
        if (!flushed.isOk()) {
            return DbResult.fail(flushed.error());
        }
        active.data().markCommitted();
        lockManager.releaseAll(txId);
        return DbResult.ok(null);
    }

    // 写回页前镜像、撤销新建表并重新加载目录视图
    public synchronized DbResult<Void> rollback(String requestId, long txId) {
        DbResult<Transaction> active = requireActive(txId);
        if (!active.isOk()) {
            return DbResult.fail(withRequest(active.error(), requestId));
        }
        TransactionContext context = context(txId);
        DbResult<Void> restored = pageStorageClient.restoreBeforeImages(
                requestId,
                context.pageBeforeImages()
        );
        if (!restored.isOk()) {
            return restored;
        }

        List<CatalogChange> changes = new ArrayList<>(context.catalogChanges());
        for (int index = changes.size() - 1; index >= 0; index--) {
            CatalogChange change = changes.get(index);
            if ("CREATE".equals(change.operation())) {
                DbResult<Void> dropped = storageEngine.dropTableStorage(
                        requestId,
                        change.schema().getName()
                );
                if (!dropped.isOk()) {
                    return dropped;
                }
            }
        }
        DbResult<List<TableSchema>> reloaded = catalogManager.load(requestId);
        if (!reloaded.isOk()) {
            return DbResult.fail(reloaded.error());
        }
        active.data().markRolledBack();
        lockManager.releaseAll(txId);
        return DbResult.ok(null);
    }

    // 返回指定编号对应的活动事务
    public synchronized DbResult<Transaction> requireActive(long txId) {
        TransactionContext context = transactions.get(txId);
        if (context == null) {
            return failure(null, "TX_NOT_FOUND", "事务不存在：" + txId);
        }
        if (context.transaction().state() != TransactionState.ACTIVE) {
            return failure(null, "TX_NOT_ACTIVE", "事务已经结束：" + txId);
        }
        return DbResult.ok(context.transaction());
    }

    // 返回指定事务上下文
    private synchronized TransactionContext context(long txId) {
        return transactions.get(txId);
    }

    // 从CreateTable计划还原需要记录的目录表结构
    private DbResult<TableSchema> schemaFromCreatePlan(String requestId, PlanNode plan) {
        if (!(plan instanceof JsonPlanNode jsonPlan)
                || !(jsonPlan.field("table") instanceof String table)
                || !(jsonPlan.field("columns") instanceof List<?> columns)) {
            return failure(requestId, "TX_INVALID_PLAN", "CreateTable计划缺少表结构");
        }
        List<ColumnSchema> schemaColumns = new ArrayList<>();
        for (Object value : columns) {
            if (!(value instanceof Map<?, ?> column)
                    || !(column.get("name") instanceof String name)
                    || !(column.get("dataType") instanceof String dataType)) {
                return failure(requestId, "TX_INVALID_PLAN", "CreateTable计划列定义无效");
            }
            schemaColumns.add(new ColumnSchema(name, dataType));
        }
        return DbResult.ok(new TableSchema(table, schemaColumns));
    }

    // 创建事务管理阶段错误
    private <T> DbResult<T> failure(String requestId, String code, String message) {
        return DbResult.fail(new DbError(
                requestId,
                null,
                "TRANSACTION",
                code,
                message,
                null,
                null,
                null
        ));
    }

    // 为内部校验错误补充当前请求编号
    private DbError withRequest(DbError error, String requestId) {
        return new DbError(
                requestId,
                error.getStatementIndex(),
                error.getStage(),
                error.getCode(),
                error.getMessage(),
                error.getLine(),
                error.getColumn(),
                error.getPageId()
        );
    }
}
