package com.chainpage.sqlcompiler.ast;

/**
 * parseNode 请求体：AST 节点的 JSON 文本。
 *
 * @param json 待反序列化并校验的 JSON 字符串，不允许为 null
 */
public record ParseNodeRequest(String json) {
}
