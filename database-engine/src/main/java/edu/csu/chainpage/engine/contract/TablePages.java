package edu.csu.chainpage.engine.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

// 表示一张表与其数据页之间的映射结果
public final class TablePages {
    private final String requestId; // 请求编号
    private final String table; // 表名
    private final List<Integer> pageIds; // 该表对应的全部页号

    // 创建表页映射结果
    @JsonCreator
    public TablePages(
            @JsonProperty("requestId") String requestId,
            @JsonProperty("table") String table,
            @JsonProperty("pageIds") List<Integer> pageIds) {
        this.requestId = requestId;
        this.table = table;
        this.pageIds = List.copyOf(Objects.requireNonNull(pageIds, "pageIds cannot be null"));
    }

    // 获取请求编号
    public String getRequestId() {
        return requestId;
    }

    // 获取表名
    public String getTable() {
        return table;
    }

    // 获取该表对应的全部页号
    public List<Integer> getPageIds() {
        return pageIds;
    }
}
