package edu.csu.chainpage.engine.plan;

import edu.csu.chainpage.engine.contract.ColumnSchema;

import java.util.List;

// 表示数据库执行计划中的一个节点
public interface PlanNode {

    // 返回计划节点种类，例如CreateTable、Insert或SeqScan
    String kind();

    // 返回该节点的子计划
    List<PlanNode> children();

    // 返回该节点输出的列模式
    List<ColumnSchema> schema();
}
