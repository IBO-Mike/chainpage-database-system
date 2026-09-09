package edu.csu.chainpage.engine.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class CompiledStatement {

    private final int statementIndex; // 语句在SQL中的顺序
    private final Object tokens; // 词法分析结果
    private final Object ast; // 抽象语法树
    private final Object semantic; // 语义分析结果
    private final Object plan; // 原始逻辑执行计划
    private final Object optimizedPlan; // 优化后的计划

    // 创建一条SQL语句的完整编译结果
    @JsonCreator
    public CompiledStatement(
            @JsonProperty("statementIndex") int statementIndex,
            @JsonProperty("tokens") Object tokens,
            @JsonProperty("ast") Object ast,
            @JsonProperty("semantic") Object semantic,
            @JsonProperty("plan") Object plan,
            @JsonProperty("optimizedPlan") Object optimizedPlan) {
        this.statementIndex = statementIndex;
        this.tokens = tokens;
        this.ast = ast;
        this.semantic = semantic;
        this.plan = plan;
        this.optimizedPlan = optimizedPlan;
    }

    // 获取语句在输入SQL中的序号
    public int getStatementIndex() {
        return statementIndex;
    }

    // 获取词法分析结果
    public Object getTokens() {
        return tokens;
    }

    // 获取抽象语法树
    public Object getAst() {
        return ast;
    }

    // 获取语义分析结果
    public Object getSemantic() {
        return semantic;
    }

    // 获取原始逻辑执行计划
    public Object getPlan() {
        return plan;
    }

    // 获取优化后的逻辑执行计划
    public Object getOptimizedPlan() {
        return optimizedPlan;
    }
}
