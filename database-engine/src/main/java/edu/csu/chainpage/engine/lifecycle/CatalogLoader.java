package edu.csu.chainpage.engine.lifecycle;

import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.contract.CatalogSnapshot;

// 为数据库生命周期提供目录加载能力的接口
@FunctionalInterface
public interface CatalogLoader {

    // 加载持久化目录并返回当前目录快照
    DbResult<CatalogSnapshot> load(String requestId);
}
