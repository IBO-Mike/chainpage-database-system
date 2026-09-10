package edu.csu.chainpage.engine.catalog;

import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.contract.TableSchema;

import java.util.List;

// 定义系统目录的初始化、加载、保存和删除能力
public interface CatalogRepository {

    // 初始化保留名称的目录存储
    DbResult<Void> initialize(String requestId);

    // 读取全部已经持久化的用户表结构
    DbResult<List<TableSchema>> load(String requestId);

    // 持久化一张新的用户表结构
    DbResult<Void> save(String requestId, TableSchema schema);

    // 删除一张已经持久化的用户表结构
    DbResult<Void> remove(String requestId, String table);
}
