package edu.csu.chainpage.engine.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

// 表示页式存储系统刷新脏页后的结果
public final class FlushResult {

    private final String requestId; // 请求编号
    private final List<Integer> flushedPageIds; // 已经刷回持久化存储的页号

    // 创建刷新结果
    @JsonCreator
    public FlushResult(
            @JsonProperty("requestId") String requestId,
            @JsonProperty("flushedPageIds") List<Integer> flushedPageIds) {
        this.requestId = requestId;
        this.flushedPageIds = List.copyOf(Objects.requireNonNull(flushedPageIds, "flushedPageIds cannot be null"));
    }

    // 获取请求编号
    public String getRequestId() {
        return requestId;
    }

    // 获取已经刷回的页号
    public List<Integer> getFlushedPageIds() {
        return flushedPageIds;
    }
}
