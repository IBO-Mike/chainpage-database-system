package edu.csu.chainpage.engine.api;

// 表示一条SQL语句的执行结果
public final class StatementExecutionResult {

    private final int statementIndex; // 语句在输入SQL中的序号
    private final String kind; // 结果类型，例如SELECT或INSERT
    private final Object result; // 具体结果内容

    // 创建一条语句的执行结果
    public StatementExecutionResult(int statementIndex, String kind, Object result) {
        this.statementIndex = statementIndex;
        this.kind = kind;
        this.result = result;
    }

    // 获取语句序号
    public int getStatementIndex() {
        return statementIndex;
    }

    // 获取结果类型
    public String getKind() {
        return kind;
    }

    // 获取具体结果内容
    public Object getResult() {
        return result;
    }
}
