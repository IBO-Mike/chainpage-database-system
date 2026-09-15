package edu.csu.chainpage.engine.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

// 表示页式存储系统删除表页映射并释放数据页后的结果
public final class DropTablePagesResult {

    private final String requestId; // 请求编号
    private final String table; // 表名
    private final boolean removed; // 是否成功删除页映射
    private final List<Integer> freedPageIds; // 本次释放的页号

    // 创建删除表页映射的结果
    @JsonCreator
    public DropTablePagesResult(
            @JsonProperty("requestId") String requestId,
            @JsonProperty("table") String table,
            @JsonProperty("removed") boolean removed,
            @JsonProperty("freedPageIds") List<Integer> freedPageIds) {
        this.requestId = requestId;
        this.table = table;
        this.removed = removed;
        this.freedPageIds = List.copyOf(Objects.requireNonNull(freedPageIds, "freedPageIds cannot be null"));
    }

    // 获取请求编号
    public String getRequestId() {
        return requestId;
    }

    // 获取表名
    public String getTable() {
        return table;
    }

    // 获取页映射是否已经删除
    public boolean isRemoved() {
        return removed;
    }

    // 获取本次释放的页号
    public List<Integer> getFreedPageIds() {
        return freedPageIds;
    }
}
