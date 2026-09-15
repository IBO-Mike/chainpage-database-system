package edu.csu.chainpage.engine.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

// 表示页式存储系统读取到的一页数据
public final class PageData {
    private final String requestId; // 请求编号
    private final int pageId; // 页号
    private final String data; // Base64编码的页数据
    private final boolean hit; // 是否命中缓存
    private final boolean dirty; // 页面是否为脏页

    // 创建页数据结果
    @JsonCreator
    public PageData(
            @JsonProperty("requestId") String requestId,
            @JsonProperty("pageId") int pageId,
            @JsonProperty("data") String data,
            @JsonProperty("hit") boolean hit,
            @JsonProperty("dirty") boolean dirty) {
        this.requestId = requestId;
        this.pageId = pageId;
        this.data = data;
        this.hit = hit;
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

    // 获取Base64编码的页数据
    public String getData() {
        return data;
    }

    // 获取是否命中缓存
    public boolean isHit() {
        return hit;
    }

    // 获取页面是否为脏页
    public boolean isDirty() {
        return dirty;
    }
}
