package edu.csu.chainpage.engine.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

// 表示页式存储系统写入一页后的结果
public final class WritePageResult {
    private final String requestId; // 请求编号
    private final int pageId; // 页号
    private final boolean dirty; // 页面是否已经标记为脏页

    // 创建写页结果
    @JsonCreator
    public WritePageResult(
            @JsonProperty("requestId") String requestId,
            @JsonProperty("pageId") int pageId,
            @JsonProperty("dirty") boolean dirty) {
        this.requestId = requestId;
        this.pageId = pageId;
        this.dirty = dirty;
    }

    // 获取请求编号
    public String getRequestId() {
        return requestId;
    }

    // 获取页号
    public int getPageId() {
        return pageId;
    }

    // 获取页面是否为脏页
    public boolean isDirty() {
        return dirty;
    }
}
