package com.chainpage.sqlcompiler.lexer;

/**
 * Token 类别枚举，覆盖基础 SQL 方言所需的全部词法单元：
 * <ul>
 *   <li>KEYWORD：关键字，如 SELECT、FROM、WHERE（词素统一为大写）；</li>
 *   <li>IDENTIFIER：标识符（表名/列名）；</li>
 *   <li>INT_LITERAL / STRING_LITERAL：整型与字符串字面量；</li>
 *   <li>OPERATOR / DELIMITER：运算符与分隔符（括号、逗号、分号）；</li>
 *   <li>EOF：输入结束哨兵，Parser 依赖它判断 Token 流是否消费完毕。</li>
 * </ul>
 */
public enum TokenType {
    KEYWORD,
    IDENTIFIER,
    INT_LITERAL,
    STRING_LITERAL,
    OPERATOR,
    DELIMITER,
    EOF
}
