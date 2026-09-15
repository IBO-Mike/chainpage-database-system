package edu.csu.chainpage.engine.storage.index;

import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.storage.RowId;

import java.util.List;

// 隔离执行引擎与物理索引实现，只向上层提供索引可用性检查和记录定位能力
public interface IndexLookup {

    // 根据索引条件查找匹配记录的物理位置
    DbResult<List<RowId>> find(
            String requestId,
            String table,
            String index,
            Object condition
    );

    // 检查指定表的索引是否已经建立并且可以访问
    DbResult<Void> ensureAvailable(String requestId, String table, String index);
}
