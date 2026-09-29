package com.chainpage.sqlcompiler.parser;

import com.chainpage.sqlcompiler.lexer.Token;
import com.chainpage.sqlcompiler.lexer.TokenType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 语法分析器—— SQL 编译器的第二阶段，使用预测分析表和显式栈实现 LL(1) 自顶向下分析。
 *
 * <p>工作流程分两步：</p>
 * <ol>
 *   <li><b>analyze</b>：表驱动的 LL(1) 分析。维护一个节点栈，栈顶为非终结符时查预测分析表
 *       TABLE 展开产生式，栈顶为终结符时与当前 lookahead Token 精确匹配，从而同时完成
 *       语法检查并构建出具体的“分析树”（Node 树）；</li>
 *   <li><b>buildXxx</b>：对分析树做后处理，把 Node 树压缩、改写为更精简的语义化 AST
 *       （以 Map/JSON 形式表示，如 SelectStmt / BinaryExpr 等），供语义分析与计划生成使用。</li>
 * </ol>
 *
 * <p>错误处理：Token 不符合文法时抛出 ParseFailure（携带 PARSER_* 错误与期望集合），
 * 入口方法捕获后转换为 ParseResponse.failure；请求参数不合法（Token 流为空、EOF 不在末尾等）
 * 则返回 PARSER_INVALID_REQUEST。</p>
 */
public final class Parser {
    /** 文法中终结符的规范名：非关键字终结符统一用 Token 类型名表示。 */
    private static final String ID = "IDENTIFIER";
    private static final String INTEGER = "INT_LITERAL";
    private static final String STRING = "STRING_LITERAL";
    private static final String EOF = "EOF";
    /** LL(1) 预测分析表：非终结符 → lookahead 终结符 → 产生式右部。类加载时构建一次。 */
    private static final Map<String, Map<String, List<String>>> TABLE = buildTable();

    /**
     * 语法分析入口：校验请求 → LL(1) 分析出分析树 → 压缩为 AST 语句列表。
     *
     * @param request 含 tokens 数组的请求，Token 流必须以且仅以一个 EOF 结尾
     * @return 成功时携带 statements（语义化 AST 列表）；失败时携带 PARSER_* 错误
     */
    public ParseResponse parse(ParseRequest request) {
        ParserError invalid = validate(request);
        if (invalid != null) return ParseResponse.failure(invalid);
        try {
            return ParseResponse.success(buildProgram(analyze(request.tokens())));
        } catch (ParseFailure failure) {
            // analyze/build 阶段的语法错误统一在此转为失败响应
            return ParseResponse.failure(failure.error);
        }
    }

    /**
     * 表驱动 LL(1) 分析主循环。
     *
     * <p>状态：栈 stack 中存放待处理的文法符号节点，input 指向当前 lookahead Token。
     * 每轮弹出栈顶：</p>
     * <ul>
     *   <li>若为终结符：必须与 lookahead 的规范名一致，匹配成功则把 Token 挂到节点上并前进；</li>
     *   <li>若为非终结符：查 TABLE[栈顶符号][lookahead]，查不到即语法错误；
     *       查到则按产生式右部自左向右创建子节点，再自右向左压栈，保证左孩子先被分析。</li>
     * </ul>
     * 栈空后若输入还有剩余 Token，同样视为语法错误。返回 PROGRAM 根节点。
     */
    private Node analyze(List<Token> tokens) {
        Node root = new Node("PROGRAM");
        Deque<Node> stack = new ArrayDeque<>();
        stack.push(root);
        int input = 0;
        while (!stack.isEmpty()) {
            Node top = stack.pop();
            Token lookahead = tokens.get(input);
            String terminal = terminalOf(lookahead);
            if (isTerminal(top.symbol)) {
                // 终结符：与 lookahead 精确匹配，然后消费该 Token
                if (!top.symbol.equals(terminal)) throw unexpected(lookahead, List.of(top.symbol));
                top.token = lookahead;
                input++;
                continue;
            }
            // 非终结符：查预测分析表决定使用哪条产生式
            List<String> production = TABLE.get(top.symbol).get(terminal);
            if (production == null) {
                // 表项为空 → 该 lookahead 在此位置非法，报错并给出所有可开始的 Token
                throw unexpected(lookahead, new ArrayList<>(TABLE.get(top.symbol).keySet()));
            }
            for (String symbol : production) top.children.add(new Node(symbol));
            // 逆序压栈：栈是 LIFO，逆序压入使右部最左侧符号最先弹出处理
            for (int i = top.children.size() - 1; i >= 0; i--) stack.push(top.children.get(i));
        }
        // 栈已空但输入未耗尽：多余 Token 无处安放，报错
        if (input != tokens.size()) throw unexpected(tokens.get(input), List.of(EOF));
        return root;
    }

    /**
     * 沿 PROGRAM → STMT PROGRAM 的右递归链展开，逐个取出 STMT 子树构建语句 AST。
     * PROGRAM 节点固定有两个孩子（STMT 与剩余 PROGRAM），链尾孩子为空。
     */
    private List<Map<String, Object>> buildProgram(Node program) {
        List<Map<String, Object>> result = new ArrayList<>();
        Node cursor = program;
        while (cursor.children.size() == 2) {
            result.add(buildStatement(cursor.children.get(0)));
            cursor = cursor.children.get(1);
        }
        return result;
    }

    /** 按 STMT 唯一孩子（CREATE_STMT/INSERT_STMT/SELECT_STMT/DELETE_STMT）分发到对应的 AST 构建器。 */
    private Map<String, Object> buildStatement(Node statement) {
        Node value = statement.children.get(0);
        return switch (value.symbol) {
            case "CREATE_STMT" -> buildCreate(value);
            case "INSERT_STMT" -> buildInsert(value);
            case "SELECT_STMT" -> buildSelect(value);
            case "DELETE_STMT" -> buildDelete(value);
            default -> throw new IllegalStateException("未知语句：" + value.symbol);
        };
    }

    /**
     * 构建 CreateTableStmt AST。
     * 产生式：CREATE TABLE ID ( COLUMN_DEF COLUMN_TAIL ) ;
     * 列定义沿 COLUMN_TAIL 右递归链逐个收集为 columns 列表。
     */
    private Map<String, Object> buildCreate(Node node) {
        Map<String, Object> result = ast("CreateTableStmt", token(node, 0));
        result.put("table", token(node, 2).lexeme());       // 孩子 2 为表名 ID
        List<Map<String, Object>> columns = new ArrayList<>();
        addColumn(node.children.get(4), columns);           // 孩子 4 为第一列定义
        Node tail = node.children.get(5);                   // 孩子 5 为 COLUMN_TAIL
        while (!tail.children.isEmpty()) {                  // 链尾 COLUMN_TAIL 为空产生式
            addColumn(tail.children.get(1), columns);       // ", COLUMN_DEF" 中的列定义
            tail = tail.children.get(2);                    // 递归进入下一级 COLUMN_TAIL
        }
        result.put("columns", columns);
        return result;
    }

    /** 从 COLUMN_DEF 子树提取单个列定义：{name, dataType, loc}。 */
    private void addColumn(Node node, List<Map<String, Object>> columns) {
        Token name = token(node, 0);                        // 孩子 0 为列名 ID
        Map<String, Object> column = new LinkedHashMap<>();
        column.put("name", name.lexeme());
        column.put("dataType", token(node.children.get(1), 0).lexeme()); // DATA_TYPE 下的 INT/VARCHAR
        column.put("loc", loc(name));
        columns.add(column);
    }

    /**
     * 构建 InsertStmt AST。
     * 产生式：INSERT INTO ID ( ID_LIST ) VALUES ( VALUE_LIST ) ;
     * 孩子 2 为表名、孩子 4 为列名列表、孩子 8 为值列表。
     */
    private Map<String, Object> buildInsert(Node node) {
        Map<String, Object> result = ast("InsertStmt", token(node, 0));
        result.put("table", token(node, 2).lexeme());
        result.put("columns", buildIds(node.children.get(4)));
        result.put("values", buildValues(node.children.get(8)));
        return result;
    }

    /**
     * 构建 SelectStmt AST。
     * 产生式：SELECT SELECT_LIST FROM ID WHERE_OPT ;
     * SELECT_LIST 为 "*" 或列名列表；WHERE_OPT 为空时 where 置 null。
     */
    private Map<String, Object> buildSelect(Node node) {
        Map<String, Object> result = ast("SelectStmt", token(node, 0));
        Node selection = node.children.get(1).children.get(0); // SELECT_LIST 的具体分支
        result.put("columns", selection.symbol.equals("*") ? List.of("*") : buildIds(selection));
        result.put("table", token(node, 3).lexeme());          // 孩子 3 为 FROM 后的表名
        Node where = node.children.get(4);                     // 孩子 4 为 WHERE_OPT
        result.put("where", where.children.isEmpty() ? null : buildExpression(where.children.get(1)));
        return result;
    }

    /**
     * 构建 DeleteStmt AST。
     * 产生式：DELETE FROM ID WHERE_OPT ;
     */
    private Map<String, Object> buildDelete(Node node) {
        Map<String, Object> result = ast("DeleteStmt", token(node, 0));
        result.put("table", token(node, 2).lexeme());
        Node where = node.children.get(3);
        result.put("where", where.children.isEmpty() ? null : buildExpression(where.children.get(1)));
        return result;
    }

    /** 沿 ID_TAIL 右递归链收集 ID_LIST 子树中的全部列名。 */
    private List<String> buildIds(Node node) {
        List<String> result = new ArrayList<>();
        result.add(token(node, 0).lexeme());               // 列表首元素
        Node tail = node.children.get(1);                  // ID_TAIL
        while (!tail.children.isEmpty()) {
            result.add(token(tail, 1).lexeme());           // ", ID" 中的 ID
            tail = tail.children.get(2);
        }
        return result;
    }

    /** 沿 VALUE_TAIL 右递归链收集 VALUE_LIST 子树中的全部字面量。 */
    private List<Map<String, Object>> buildValues(Node node) {
        List<Map<String, Object>> result = new ArrayList<>();
        result.add(buildLiteral(node.children.get(0)));    // 列表首元素
        Node tail = node.children.get(1);                  // VALUE_TAIL
        while (!tail.children.isEmpty()) {
            result.add(buildLiteral(tail.children.get(1)));
            tail = tail.children.get(2);
        }
        return result;
    }

    /**
     * 构建 WHERE 条件表达式 AST：OR_EXPR 层（最低优先级）。
     * 左结合的 OR 链：先构建第一个 AND_EXPR，再沿 OR_TAIL 逐个用 OR 合并。
     */
    private Map<String, Object> buildExpression(Node expression) {
        Node or = expression.children.get(0);
        Map<String, Object> result = buildAnd(or.children.get(0));
        Node tail = or.children.get(1);
        while (!tail.children.isEmpty()) {
            result = binary(token(tail, 0), result, buildAnd(tail.children.get(1)));
            tail = tail.children.get(2);
        }
        return result;
    }

    /** AND_EXPR 层：左结合的 AND 链，优先级高于 OR。 */
    private Map<String, Object> buildAnd(Node node) {
        Map<String, Object> result = buildNot(node.children.get(0));
        Node tail = node.children.get(1);
        while (!tail.children.isEmpty()) {
            result = binary(token(tail, 0), result, buildNot(tail.children.get(1)));
            tail = tail.children.get(2);
        }
        return result;
    }

    /**
     * NOT_EXPR 层：NOT 可递归前缀（如 NOT NOT x），否则下探到 COMPARISON。
     */
    private Map<String, Object> buildNot(Node node) {
        if (node.children.get(0).symbol.equals("NOT")) {
            Token operator = token(node, 0);
            Map<String, Object> result = ast("UnaryExpr", operator);
            result.put("operator", "NOT");
            result.put("operand", buildNot(node.children.get(1)));  // 递归支持连续 NOT
            return result;
        }
        return buildComparison(node.children.get(0));
    }

    /** COMPARISON 层：PRIMARY（可选）后跟比较运算符与第二个 PRIMARY，如 a = 1。 */
    private Map<String, Object> buildComparison(Node node) {
        Map<String, Object> left = buildPrimary(node.children.get(0));
        Node tail = node.children.get(1);
        if (tail.children.isEmpty()) return left;              // 无比较运算符，表达式即 PRIMARY 本身
        Token operator = token(tail.children.get(0), 0);       // COMP_OP 下的运算符
        return binary(operator, left, buildPrimary(tail.children.get(1)));
    }

    /** PRIMARY 层：标识符 → IdentifierExpr，整型/字符串字面量 → LiteralExpr，括号表达式则下探。 */
    private Map<String, Object> buildPrimary(Node node) {
        Node first = node.children.get(0);
        if (first.symbol.equals(ID)) {
            Map<String, Object> result = ast("IdentifierExpr", first.token);
            result.put("name", first.token.lexeme());
            return result;
        }
        if (first.symbol.equals(INTEGER) || first.symbol.equals(STRING)) return buildLiteral(first);
        return buildExpression(node.children.get(1));          // ( EXPR ) 括号内表达式
    }

    /**
     * 构建 LiteralExpr AST：整型转为 Java int（超出范围报 PARSER_INT_OUT_OF_RANGE），
     * 字符串去掉两侧引号并把 '' 还原为单个 '。
     */
    private Map<String, Object> buildLiteral(Node node) {
        Node terminal = node.symbol.equals("LITERAL") ? node.children.get(0) : node;
        Token token = terminal.token;
        Map<String, Object> result = ast("LiteralExpr", token);
        if (token.type() == TokenType.INT_LITERAL) {
            result.put("literalType", "INT");
            try {
                result.put("value", Integer.parseInt(token.lexeme()));
            } catch (NumberFormatException exception) {
                throw new ParseFailure(new ParserError("PARSER", "PARSER_INT_OUT_OF_RANGE",
                        "整数超出 Java int 范围", token.line(), token.column(), List.of(INTEGER)));
            }
        } else {
            result.put("literalType", "VARCHAR");
            result.put("value", token.lexeme().substring(1, token.lexeme().length() - 1).replace("''", "'"));
        }
        return result;
    }

    /** 构建 BinaryExpr：记录运算符词素与左右操作数子树。 */
    private Map<String, Object> binary(Token operator, Map<String, Object> left, Map<String, Object> right) {
        Map<String, Object> result = ast("BinaryExpr", operator);
        result.put("operator", operator.lexeme());
        result.put("left", left);
        result.put("right", right);
        return result;
    }

    /** AST 节点公共骨架：kind + loc（取自该节点对应的首个 Token 位置）。 */
    private static Map<String, Object> ast(String kind, Token token) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", kind);
        result.put("loc", loc(token));
        return result;
    }

    /** 位置信息 {line, column}。 */
    private static Map<String, Object> loc(Token token) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("line", token.line());
        result.put("column", token.column());
        return result;
    }

    private static Token token(Node node, int index) { return node.children.get(index).token; }
    /** 判断符号是否为终结符：不在分析表键集合中的即为终结符。 */
    private static boolean isTerminal(String symbol) { return !TABLE.containsKey(symbol); }

    /**
     * 把 Token 映射为文法中的终结符名：标识符/字面量/EOF 用类型名，
     * 其余（关键字、运算符、分隔符）直接用词素本身。
     */
    private static String terminalOf(Token token) {
        return switch (token.type()) {
            case IDENTIFIER -> ID;
            case INT_LITERAL -> INTEGER;
            case STRING_LITERAL -> STRING;
            case EOF -> EOF;
            default -> token.lexeme();
        };
    }

    /** 构造“意外 Token”语法错误，expected 为该位置允许出现的终结符集合。 */
    private static ParseFailure unexpected(Token token, List<String> expected) {
        return new ParseFailure(new ParserError("PARSER", "PARSER_UNEXPECTED_TOKEN",
                "Token 不符合 LL(1) 文法：" + terminalOf(token), token.line(), token.column(), expected));
    }

    /** 请求校验：tokens 非空、每个 Token 字段完整且位置为正整数、以且仅以一个 EOF 结尾。 */
    private static ParserError validate(ParseRequest request) {
        if (request == null || request.tokens() == null)
            return invalid("请求必须包含 tokens 数组", null, null);
        List<Token> tokens = request.tokens();
        if (tokens.isEmpty()) return invalid("Token 数组必须以 EOF 结束", null, null);
        int eofCount = 0;
        for (Token token : tokens) {
            if (token == null || token.type() == null || token.lexeme() == null
                    || token.line() < 1 || token.column() < 1)
                return invalid("Token 字段不完整或位置不是正整数", null, null);
            if (token.type() == TokenType.EOF) eofCount++;
        }
        Token last = tokens.get(tokens.size() - 1);
        if (eofCount != 1 || last.type() != TokenType.EOF)
            return invalid("Token 数组必须以且只能以一个 EOF 结束", last.line(), last.column());
        return null;
    }

    /** 组装 PARSER_INVALID_REQUEST 错误。 */
    private static ParserError invalid(String message, Integer line, Integer column) {
        return new ParserError("PARSER", "PARSER_INVALID_REQUEST", message, line, column, List.of(EOF));
    }

    /**
     * 构建基础 SQL 方言的 LL(1) 预测分析表（手工完成 FIRST/FOLLOW 计算）。
     *
     * <p>文法要点：</p>
     * <ul>
     *   <li>PROGRAM → STMT PROGRAM | EOF：支持多语句，用右递归实现列表；</li>
     *   <li>列表类非终结符（COLUMN_TAIL/ID_TAIL/VALUE_TAIL 等）同样用右递归 + ε 产生式；</li>
     *   <li>表达式按优先级分层：EXPR → OR_EXPR → AND_EXPR → NOT_EXPR → COMPARISON → PRIMARY，
     *       层级越深优先级越高，实现 OR &lt; AND &lt; NOT &lt; 比较运算；</li>
     *   <li>同一非终结符的 ε 产生式用其 FOLLOW 集作为 lookahead（如 ID_TAIL 在 ) 和 FROM 处可空）。</li>
     * </ul>
     */
    private static Map<String, Map<String, List<String>>> buildTable() {
        Grammar g = new Grammar();
        g.add("PROGRAM", List.of("CREATE", "INSERT", "SELECT", "DELETE"), "STMT", "PROGRAM");
        g.add("PROGRAM", List.of(EOF), EOF);
        g.add("STMT", List.of("CREATE"), "CREATE_STMT");
        g.add("STMT", List.of("INSERT"), "INSERT_STMT");
        g.add("STMT", List.of("SELECT"), "SELECT_STMT");
        g.add("STMT", List.of("DELETE"), "DELETE_STMT");
        g.add("CREATE_STMT", List.of("CREATE"), "CREATE", "TABLE", ID, "(", "COLUMN_DEF", "COLUMN_TAIL", ")", ";");
        g.add("COLUMN_DEF", List.of(ID), ID, "DATA_TYPE");
        g.add("DATA_TYPE", List.of("INT"), "INT");
        g.add("DATA_TYPE", List.of("VARCHAR"), "VARCHAR");
        g.add("COLUMN_TAIL", List.of(","), ",", "COLUMN_DEF", "COLUMN_TAIL");
        g.add("COLUMN_TAIL", List.of(")"));
        g.add("INSERT_STMT", List.of("INSERT"), "INSERT", "INTO", ID, "(", "ID_LIST", ")", "VALUES", "(", "VALUE_LIST", ")", ";");
        g.add("SELECT_STMT", List.of("SELECT"), "SELECT", "SELECT_LIST", "FROM", ID, "WHERE_OPT", ";");
        g.add("DELETE_STMT", List.of("DELETE"), "DELETE", "FROM", ID, "WHERE_OPT", ";");
        g.add("SELECT_LIST", List.of("*"), "*");
        g.add("SELECT_LIST", List.of(ID), "ID_LIST");
        g.add("ID_LIST", List.of(ID), ID, "ID_TAIL");
        g.add("ID_TAIL", List.of(","), ",", ID, "ID_TAIL");
        g.add("ID_TAIL", List.of(")", "FROM"));
        g.add("VALUE_LIST", List.of(INTEGER, STRING), "LITERAL", "VALUE_TAIL");
        g.add("VALUE_TAIL", List.of(","), ",", "LITERAL", "VALUE_TAIL");
        g.add("VALUE_TAIL", List.of(")"));
        g.add("LITERAL", List.of(INTEGER), INTEGER);
        g.add("LITERAL", List.of(STRING), STRING);
        g.add("WHERE_OPT", List.of("WHERE"), "WHERE", "EXPR");
        g.add("WHERE_OPT", List.of(";"));
        g.add("EXPR", exprStarts(), "OR_EXPR");
        g.add("OR_EXPR", exprStarts(), "AND_EXPR", "OR_TAIL");
        g.add("OR_TAIL", List.of("OR"), "OR", "AND_EXPR", "OR_TAIL");
        g.add("OR_TAIL", List.of(")", ";"));
        g.add("AND_EXPR", exprStarts(), "NOT_EXPR", "AND_TAIL");
        g.add("AND_TAIL", List.of("AND"), "AND", "NOT_EXPR", "AND_TAIL");
        g.add("AND_TAIL", List.of("OR", ")", ";"));
        g.add("NOT_EXPR", List.of("NOT"), "NOT", "NOT_EXPR");
        g.add("NOT_EXPR", primaryStarts(), "COMPARISON");
        g.add("COMPARISON", primaryStarts(), "PRIMARY", "COMPARISON_TAIL");
        g.add("COMPARISON_TAIL", List.of("=", "!=", ">", ">=", "<", "<="), "COMP_OP", "PRIMARY");
        g.add("COMPARISON_TAIL", List.of("AND", "OR", ")", ";"));
        for (String op : List.of("=", "!=", ">", ">=", "<", "<=")) g.add("COMP_OP", List.of(op), op);
        g.add("PRIMARY", List.of(ID), ID);
        g.add("PRIMARY", List.of(INTEGER), INTEGER);
        g.add("PRIMARY", List.of(STRING), STRING);
        g.add("PRIMARY", List.of("("), "(", "EXPR", ")");
        return g.table;
    }

    /** EXPR 系非终结符的 FIRST 集（表达式可能的开头 Token）。 */
    private static List<String> exprStarts() { return List.of("NOT", ID, INTEGER, STRING, "("); }
    /** PRIMARY 的 FIRST 集（不含 NOT）。 */
    private static List<String> primaryStarts() { return List.of(ID, INTEGER, STRING, "("); }

    /** 分析表构建辅助类：登记产生式并即时检测 LL(1) 冲突（同一表项被写入两次即冲突）。 */
    private static final class Grammar {
        private final Map<String, Map<String, List<String>>> table = new LinkedHashMap<>();
        private void add(String nonterminal, List<String> lookaheads, String... production) {
            Map<String, List<String>> row = table.computeIfAbsent(nonterminal, key -> new LinkedHashMap<>());
            for (String lookahead : lookaheads) {
                if (row.put(lookahead, List.of(production)) != null)
                    throw new IllegalStateException("LL(1) 表冲突：" + nonterminal + ", " + lookahead);
            }
        }
    }

    /** 分析树节点：symbol 为文法符号名；终结符节点在匹配时挂上对应 Token。 */
    private static final class Node {
        private final String symbol;
        private final List<Node> children = new ArrayList<>();
        private Token token;
        private Node(String symbol) { this.symbol = symbol; }
    }

    /** 语法错误载体：以运行时异常在递归/循环中传播，由 parse 入口统一捕获转换。 */
    private static final class ParseFailure extends RuntimeException {
        private final ParserError error;
        private ParseFailure(ParserError error) { super(error.message()); this.error = error; }
    }
}
