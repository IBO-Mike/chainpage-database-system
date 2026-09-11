package edu.csu.chainpage.engine.storage.index;

import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.storage.RowId;
import edu.csu.chainpage.engine.storage.StorageEngine;

import java.util.List;
import java.util.Objects;

// 通过StorageEngine访问索引，使执行器不会越过存储引擎操作物理索引
public final class StorageIndexLookup implements IndexLookup {

    private final StorageEngine storageEngine; // 提供统一索引访问入口的存储引擎

    // 创建使用指定存储引擎的索引查询器
    public StorageIndexLookup(StorageEngine storageEngine) {
        this.storageEngine = Objects.requireNonNull(storageEngine, "storageEngine cannot be null");
    }

    // 将索引查找请求完整委托给StorageEngine
    @Override
    public DbResult<List<RowId>> find(
            String requestId,
            String table,
            String index,
            Object condition) {
        return storageEngine.lookupIndex(requestId, table, index, condition);
    }

    // 通过StorageEngine验证表和索引是否可以访问
    @Override
    public DbResult<Void> ensureAvailable(String requestId, String table, String index) {
        return storageEngine.ensureIndexAvailable(requestId, table, index);
    }
}
