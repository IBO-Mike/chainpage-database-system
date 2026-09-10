package edu.csu.chainpage.engine.plan;

import edu.csu.chainpage.engine.common.DbResult;

import java.util.List;
import java.util.Objects;

// 保存原始计划、优化计划以及优化器报告的规则名称
public final class OptimizedExecutionRequest {

    private final PlanNode originalPlan; // SQL编译器生成的原始逻辑计划
    private final PlanNode optimizedPlan; // 优化器生成且实际需要执行的计划
    private final List<String> appliedRules; // 已应用的优化规则

    // 创建优化执行请求；优化计划允许暂时为null并由validate报告错误
    public OptimizedExecutionRequest(
            PlanNode plan,
            PlanNode optimizedPlan,
            List<String> appliedRules) {
        this.originalPlan = plan;
        this.optimizedPlan = optimizedPlan;
        this.appliedRules = List.copyOf(Objects.requireNonNull(
                appliedRules,
                "appliedRules cannot be null"
        ));
    }

    // 返回原始逻辑计划
    public PlanNode originalPlan() {
        return originalPlan;
    }

    // 返回优化后且必须执行的计划
    public PlanNode optimizedPlan() {
        return optimizedPlan;
    }

    // 返回不可修改的优化规则列表
    public List<String> appliedRules() {
        return appliedRules;
    }

    // 检查优化执行请求是否包含两份必要计划
    public DbResult<Void> validate(String requestId) {
        if (originalPlan == null) {
            return DbResult.fail(edu.csu.chainpage.engine.common.DbError.executor(
                    requestId,
                    null,
                    "EXECUTOR_ORIGINAL_PLAN_MISSING",
                    "原始执行计划不能为null"
            ));
        }
        if (optimizedPlan == null) {
            return DbResult.fail(edu.csu.chainpage.engine.common.DbError.executor(
                    requestId,
                    null,
                    "EXECUTOR_OPTIMIZED_PLAN_MISSING",
                    "优化执行计划不能为null"
            ));
        }
        return DbResult.ok(null);
    }
}
