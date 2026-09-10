package edu.csu.chainpage.engine.plan;

import edu.csu.chainpage.engine.common.DbError;
import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.executor.ExecutionValue;
import edu.csu.chainpage.engine.executor.PlanExecutor;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

// 按计划节点种类分派执行器，并保证子计划先于父计划执行
public final class PlanDispatcher {

    private final PlanParser parser; // 计划结构校验器
    private final List<PlanExecutor> executors = new ArrayList<>(); // 已登记执行器
    private final ThreadLocal<ExecutionContext> executionContext = new ThreadLocal<>();

    // 使用默认计划解析器创建分派器
    public PlanDispatcher() {
        this(new PlanParser());
    }

    // 创建可替换计划解析器的分派器
    public PlanDispatcher(PlanParser parser) {
        this.parser = Objects.requireNonNull(parser, "parser cannot be null");
    }

    // 登记一个计划执行器
    public void register(PlanExecutor executor) {
        executors.add(Objects.requireNonNull(executor, "executor cannot be null"));
    }

    // 按kind找到执行器并执行计划树
    public DbResult<ExecutionValue> execute(String requestId, PlanNode plan) {
        if (plan == null) {
            return failure(requestId, "EXECUTOR_INVALID_PLAN", "计划不能为null");
        }
        if (!isSupportedKind(plan.kind())) {
            return unsupported(requestId, plan);
        }

        DbResult<Void> validation = parser.validate(plan);
        if (!validation.isOk()) {
            return failureFrom(validation.error(), requestId);
        }

        PlanExecutor executor = findExecutor(plan.kind());
        if (executor == null) {
            return unsupported(requestId, plan);
        }

        ExecutionContext context = executionContext.get();
        boolean rootInvocation = context == null;
        if (rootInvocation) {
            context = new ExecutionContext();
            executionContext.set(context);
        }

        try {
            List<ExecutionValue> childValues = new ArrayList<>();
            for (PlanNode child : plan.children()) {
                DbResult<ExecutionValue> childResult = execute(requestId, child);
                if (!childResult.isOk()) {
                    return childResult;
                }
                if (childResult.data() == null) {
                    return failure(requestId, "EXECUTOR_INVALID_RESULT", "子执行器返回了空结果");
                }
                childValues.add(childResult.data());
            }
            context.childResults.put(plan, List.copyOf(childValues));

            DbResult<ExecutionValue> result = executor.execute(requestId, plan);
            if (result == null) {
                return failure(requestId, "EXECUTOR_INVALID_RESULT", "执行器返回了null结果");
            }
            if (result.isOk() && result.data() == null) {
                return failure(requestId, "EXECUTOR_INVALID_RESULT", "执行器成功结果不能为null");
            }
            return result;
        } finally {
            context.childResults.remove(plan);
            if (rootInvocation) {
                executionContext.remove();
            }
        }
    }

    // 执行父计划指定位置的子计划；树执行期间优先返回已经执行的结果
    public DbResult<ExecutionValue> executeChild(
            String requestId,
            PlanNode parent,
            int childIndex) {
        if (parent == null) {
            return failure(requestId, "EXECUTOR_INVALID_PLAN", "父计划不能为null");
        }
        DbResult<Void> count = validateChildCount(parent, parent.children() == null
                ? -1
                : parent.children().size());
        if (!count.isOk()) {
            return countFailure(count, requestId);
        }
        if (childIndex < 0 || childIndex >= parent.children().size()) {
            return failure(requestId, "EXECUTOR_INVALID_CHILD", "子计划索引超出范围");
        }

        ExecutionContext context = executionContext.get();
        if (context != null) {
            List<ExecutionValue> cached = context.childResults.get(parent);
            if (cached != null) {
                return DbResult.ok(cached.get(childIndex));
            }
        }
        return execute(requestId, parent.children().get(childIndex));
    }

    // 验证计划拥有指定数量的子计划
    public DbResult<Void> validateChildCount(PlanNode plan, int expected) {
        if (plan == null || expected < 0) {
            return failure(null, "EXECUTOR_INVALID_PLAN", "计划或期望子计划数量无效");
        }
        if (plan.children() == null || plan.children().size() != expected) {
            return failure(
                    null,
                    "EXECUTOR_INVALID_PLAN",
                    plan.kind() + "节点需要" + expected + "个子计划"
            );
        }
        return DbResult.ok(null);
    }

    // 返回未知计划节点错误
    public DbResult<ExecutionValue> unsupported(String requestId, PlanNode plan) {
        String kind = plan == null ? "null" : plan.kind();
        return failure(requestId, "EXECUTOR_UNSUPPORTED_PLAN", "不支持的计划节点：" + kind);
    }

    // 判断计划种类是否属于当前执行框架
    private boolean isSupportedKind(String kind) {
        return SetOfKinds.contains(kind);
    }

    // 查找第一个声明支持指定kind的执行器
    private PlanExecutor findExecutor(String kind) {
        for (PlanExecutor executor : executors) {
            if (executor.supports(kind)) {
                return executor;
            }
        }
        return null;
    }

    // 将无请求编号的计划校验错误补充当前请求编号
    private <T> DbResult<T> failureFrom(DbError error, String requestId) {
        return DbResult.fail(new DbError(
                requestId,
                error.getStatementIndex(),
                error.getStage(),
                error.getCode(),
                error.getMessage(),
                error.getLine(),
                error.getColumn(),
                error.getPageId()
        ));
    }

    // 把任意结果类型的子计划校验错误转换成执行结果错误
    private DbResult<ExecutionValue> countFailure(DbResult<Void> result, String requestId) {
        return failureFrom(result.error(), requestId);
    }

    // 创建统一格式的执行框架错误
    private <T> DbResult<T> failure(String requestId, String code, String message) {
        return DbResult.fail(new DbError(
                requestId,
                null,
                "EXECUTOR",
                code,
                message,
                null,
                null,
                null
        ));
    }

    // 保存一次计划执行过程中已经完成的子结果
    private static final class ExecutionContext {
        private final IdentityHashMap<PlanNode, List<ExecutionValue>> childResults =
                new IdentityHashMap<>();
    }

    // 当前执行阶段支持的核心和扩展计划节点
    private static final class SetOfKinds {
        private static boolean contains(String kind) {
            return "CreateTable".equals(kind)
                    || "Insert".equals(kind)
                    || "SeqScan".equals(kind)
                    || "Filter".equals(kind)
                    || "Project".equals(kind)
                    || "Delete".equals(kind)
                    || "Update".equals(kind)
                    || "Sort".equals(kind)
                    || "GroupBy".equals(kind)
                    || "Join".equals(kind);
        }
    }
}
