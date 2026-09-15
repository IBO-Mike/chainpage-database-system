package edu.csu.chainpage.engine.tx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// 保存事务回滚所需的页前镜像和目录变更
public final class TransactionContext {

    private final Transaction transaction; // 当前上下文所属事务
    private final Map<Integer, String> pageBeforeImages = new LinkedHashMap<>(); // 页首次写入前的内容
    private final List<CatalogChange> catalogChanges = new ArrayList<>(); // 目录变更顺序记录

    // 创建指定事务的修改上下文
    public TransactionContext(Transaction transaction) {
        this.transaction = Objects.requireNonNull(transaction, "transaction cannot be null");
    }

    // 返回当前上下文所属事务
    public Transaction transaction() {
        return transaction;
    }

    // 只记录指定页第一次被修改前的完整内容
    public synchronized void recordPageBeforeWrite(int pageId, String pageData) {
        if (pageId < 0) {
            throw new IllegalArgumentException("pageId must be non-negative");
        }
        pageBeforeImages.putIfAbsent(
                pageId,
                Objects.requireNonNull(pageData, "pageData cannot be null")
        );
    }

    // 按发生顺序记录一次目录变更
    public synchronized void recordCatalogChange(CatalogChange change) {
        catalogChanges.add(Objects.requireNonNull(change, "change cannot be null"));
    }

    // 返回不可修改的页前镜像
    public synchronized Map<Integer, String> pageBeforeImages() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(pageBeforeImages));
    }

    // 返回不可修改的目录变更记录
    public synchronized List<CatalogChange> catalogChanges() {
        return List.copyOf(catalogChanges);
    }
}
