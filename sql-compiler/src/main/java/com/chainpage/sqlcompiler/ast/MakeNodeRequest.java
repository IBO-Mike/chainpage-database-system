package com.chainpage.sqlcompiler.ast;

import java.util.Map;

/**
 * makeNode 请求体：以 Map 形式给出的 AST 节点。
 *
 * @param node 待校验与序列化的 AST 节点（kind/loc/各节点特有字段），不允许为 null
 */
public record MakeNodeRequest(Map<String, Object> node) {
}
