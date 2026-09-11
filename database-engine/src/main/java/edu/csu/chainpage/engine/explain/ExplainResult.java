package edu.csu.chainpage.engine.explain;

import edu.csu.chainpage.engine.plan.PlanNode;

import java.util.Objects;

// 保存EXPLAIN请求产生的全部编译结果和计划树文本
public final class ExplainResult {

    private final Object tokens; // 词法分析结果
    private final Object ast; // 抽象语法树
    private final Object semantic; // 语义分析结果
    private final PlanNode plan; // 原始逻辑执行计划
    private final PlanNode optimizedPlan; // 优化后的逻辑执行计划
    private final String tree; // 优化计划对应的树形文本

    // 创建一份完整的EXPLAIN结果
    public ExplainResult(
            Object tokens,
            Object ast,
            Object semantic,
            PlanNode plan,
            PlanNode optimizedPlan,
            String tree) {
        this.tokens = tokens;
        this.ast = ast;
        this.semantic = semantic;
        this.plan = Objects.requireNonNull(plan, "plan cannot be null");
        this.optimizedPlan = Objects.requireNonNull(optimizedPlan, "optimizedPlan cannot be null");
        this.tree = Objects.requireNonNull(tree, "tree cannot be null");
    }

    // 返回词法分析结果
    public Object tokens() {
        return tokens;
    }

    // 返回抽象语法树
    public Object ast() {
        return ast;
    }

    // 返回语义分析结果
    public Object semantic() {
        return semantic;
    }

    // 返回原始逻辑执行计划
    public PlanNode plan() {
        return plan;
    }

    // 返回优化后的逻辑执行计划
    public PlanNode optimizedPlan() {
        return optimizedPlan;
    }

    // 返回优化计划的树形文本
    public String tree() {
        return tree;
    }

    // 为JSON序列化返回词法分析结果
    public Object getTokens() {
        return tokens;
    }

    // 为JSON序列化返回抽象语法树
    public Object getAst() {
        return ast;
    }

    // 为JSON序列化返回语义分析结果
    public Object getSemantic() {
        return semantic;
    }

    // 为JSON序列化返回原始逻辑执行计划
    public PlanNode getPlan() {
        return plan;
    }

    // 为JSON序列化返回优化后的逻辑执行计划
    public PlanNode getOptimizedPlan() {
        return optimizedPlan;
    }

    // 为JSON序列化返回树形文本
    public String getTree() {
        return tree;
    }
}
