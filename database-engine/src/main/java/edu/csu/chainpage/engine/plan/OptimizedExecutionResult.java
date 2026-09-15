package edu.csu.chainpage.engine.plan;

import edu.csu.chainpage.engine.executor.ExecutionValue;

import java.util.List;
import java.util.Objects;

// 组合优化计划的实际执行结果、最终计划和已应用规则
public final class OptimizedExecutionResult {

    private final ExecutionValue result; // 优化计划产生的结果
    private final PlanNode plan; // 实际执行的优化计划
    private final List<String> appliedRules; // 优化器报告的规则列表

    // 创建优化执行结果
    public OptimizedExecutionResult(
            ExecutionValue result,
            PlanNode plan,
            List<String> appliedRules) {
        this.result = Objects.requireNonNull(result, "result cannot be null");
        this.plan = Objects.requireNonNull(plan, "plan cannot be null");
        this.appliedRules = List.copyOf(Objects.requireNonNull(
                appliedRules,
                "appliedRules cannot be null"
        ));
    }

    // 返回优化计划执行结果
    public ExecutionValue result() {
        return result;
    }

    // 返回实际执行的优化计划
    public PlanNode plan() {
        return plan;
    }

    // 返回不可修改的优化规则列表
    public List<String> appliedRules() {
        return appliedRules;
    }
}
