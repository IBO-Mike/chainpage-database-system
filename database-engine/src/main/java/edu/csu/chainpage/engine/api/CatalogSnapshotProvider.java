package edu.csu.chainpage.engine.api;

import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.contract.CatalogSnapshot;

// 为数据库入口提供编译前目录快照的接口
@FunctionalInterface
public interface CatalogSnapshotProvider {

    // 获取指定请求使用的只读目录快照
    DbResult<CatalogSnapshot> snapshot(String requestId);
}
