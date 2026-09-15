package edu.csu.chainpage.engine.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

// 表示数据库引擎发给SQL编译器的请求
public final class CompileRequest {
    private final String requestId; // 标识这次请求
    private final String sql; // 用户提交的SQL
    private final CatalogSnapshot catalogSnapshot; // 编译器进行表名，列名和类型检查所需的目录快照
    private final boolean optimize; // 是否需要优化

    // 创建SQL编译请求
    @JsonCreator
    public CompileRequest(
            @JsonProperty("requestId") String requestId,
            @JsonProperty("sql") String sql,
            @JsonProperty("catalogSnapshot") CatalogSnapshot catalogSnapshot,
            @JsonProperty("optimize") boolean optimize) {
        this.requestId = requestId;
        this.sql = sql;
        this.catalogSnapshot = catalogSnapshot;
        this.optimize = optimize;
    }

    // 获取本次编译请求的编号
    public String getRequestId() {
        return requestId;
    }

    // 获取待编译的SQL文本
    public String getSql() {
        return sql;
    }

    // 获取供语义分析使用的目录快照
    public CatalogSnapshot getCatalogSnapshot() {
        return catalogSnapshot;
    }

    // 获取是否需要生成优化计划
    public boolean isOptimize() {
        return optimize;
    }
}
