package edu.csu.chainpage.engine.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

// 表示页式存储系统为指定表分配页后的结果
public final class AllocatedPage {
    private final String requestId; // 请求编号
    private final String table; // 表名
    private final int pageId; // 本次新分配的页号
    private final List<Integer> pageIds; // 分配完成后该表的全部页号

    // 创建页分配结果
    @JsonCreator
    public AllocatedPage(
            @JsonProperty("requestId") String requestId,
            @JsonProperty("table") String table,
            @JsonProperty("pageId") int pageId,
            @JsonProperty("pageIds") List<Integer> pageIds) {
        this.requestId = requestId;
        this.table = table;
        this.pageId = pageId;
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

    // 获取本次新分配的页号
    public int getPageId() {
        return pageId;
    }

    // 获取该表当前的全部页号
    public List<Integer> getPageIds() {
        return pageIds;
    }
}
