package edu.csu.chainpage.engine.common;

// 用以统一表示数据库引擎中的失败信息
public final class DbError {

    private final String requestId; // 请求编号
    private final Integer statementIndex; // 多语句SQL中的序号
    private final String stage; // 错误阶段
    private final String code; // 错误码
    private final String message; // 文字错误说明
    private final Integer line; // SQL错误所在行号
    private final Integer column; // SQL错误所在列号
    private final Integer pageId; // 存储错误所涉及的页号

    public DbError(String requestId, Integer statementIndex, String stage, String code, String message, Integer line, Integer column, Integer pageId) {
        this.requestId = requestId;
        this.statementIndex = statementIndex;
        this.stage = stage;
        this.code = code;
        this.message = message;
        this.line = line;
        this.column = column;
        this.pageId = pageId;
    }

    // 创建EXECUTOR阶段错误
    public static DbError executor(String requestId, Integer statementIndex, String code, String message) {
        return new DbError(requestId, statementIndex, "EXECUTOR", code, message, null, null, null);
    }

    // 创建入口请求错误
    public static DbError invalidRequest(String requestId, String message) {
        return new DbError(requestId, null, "API", "INVALID_REQUEST", message, null, null, null);
    }

    // 补充或更新语句序号
    public DbError withStatementIndex(Integer statementIndex) {
        return new DbError(this.requestId, statementIndex, this.stage, this.code, this.message, this.line, this.column, this.pageId);
    }

    public String getRequestId() {
        return requestId;
    }

    public Integer getStatementIndex() {
        return statementIndex;
    }

    public String getStage() {
        return stage;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public Integer getLine() {
        return line;
    }

    public Integer getColumn() {
        return column;
    }

    public Integer getPageId() {
        return pageId;
    }
}
