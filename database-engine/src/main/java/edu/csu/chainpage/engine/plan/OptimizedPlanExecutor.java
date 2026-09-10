package edu.csu.chainpage.engine.plan;

import edu.csu.chainpage.engine.common.DbError;
import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.executor.ExecutionValue;

import java.util.Objects;

// 只执行优化计划并把优化信息与执行结果一起返回
public final class OptimizedPlanExecutor {

    private final PlanDispatcher dispatcher; // 基本计划树分派器
    private final PlanParser parser; // 优化计划结构校验器

    // 使用默认分派器和计划解析器创建优化执行器
    public OptimizedPlanExecutor() {
        this(new PlanDispatcher(), new PlanParser());
    }

    // 使用指定计划分派器创建优化执行器
    public OptimizedPlanExecutor(PlanDispatcher dispatcher) {
        this(dispatcher, new PlanParser());
    }

    // 创建可替换计划校验器的优化执行器
    public OptimizedPlanExecutor(
            PlanDispatcher dispatcher,
            PlanParser parser) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher cannot be null");
        this.parser = Objects.requireNonNull(parser, "parser cannot be null");
    }

    // 只分派optimizedPlan，绝不把原计划作为缺省替代
    public DbResult<OptimizedExecutionResult> execute(
            String requestId,
            OptimizedExecutionRequest request) {
        DbResult<Void> validation = validateOptimizedPlan(requestId, request);
        if (!validation.isOk()) {
            return DbResult.fail(validation.error());
        }

        DbResult<ExecutionValue> executed = dispatcher.execute(
                requestId,
                request.optimizedPlan()
        );
        if (!executed.isOk()) {
            return DbResult.fail(executed.error());
        }
        if (executed.data() == null) {
            return DbResult.fail(DbError.executor(
                    requestId,
                    null,
                    "EXECUTOR_INVALID_RESULT",
                    "优化计划执行器返回了null结果"
            ));
        }
        return DbResult.ok(new OptimizedExecutionResult(
                executed.data(),
                request.optimizedPlan(),
                request.appliedRules()
        ));
    }

    // 验证请求和优化计划格式，缺失时直接失败
    public DbResult<Void> validateOptimizedPlan(
            String requestId,
            OptimizedExecutionRequest request) {
        if (request == null) {
            return DbResult.fail(DbError.executor(
                    requestId,
                    null,
                    "EXECUTOR_INVALID_REQUEST",
                    "优化执行请求不能为null"
            ));
        }
        DbResult<Void> requestValidation = request.validate(requestId);
        if (!requestValidation.isOk()) {
            return requestValidation;
        }
        DbResult<Void> planValidation = parser.validate(request.optimizedPlan());
        if (planValidation.isOk()) {
            return planValidation;
        }
        return DbResult.fail(withRequest(planValidation.error(), requestId));
    }

    // 为计划校验错误补充当前请求编号
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
