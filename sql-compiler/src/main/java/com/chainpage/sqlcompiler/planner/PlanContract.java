package com.chainpage.sqlcompiler.planner;

import java.util.*;

/** 共享计划边界的结构校验；不访问 Catalog 或执行数据操作。 */
public final class PlanContract {
    private static final Set<String> TYPES = Set.of("INT","VARCHAR","BOOL","BIGINT","DECIMAL","DATE");
    private PlanContract() {}
    public static void validate(Map<String,Object> p) {
        String kind = text(p,"kind"); List<Map<String,Object>> children = nodes(p.get("children"));
        int count = switch(kind) {case "Join" -> 2; case "Project","Filter","Sort","GroupBy" -> 1;
            case "CreateTable","Insert","Update","Delete","SeqScan" -> 0; default -> throw new IllegalArgumentException("未知计划："+kind);};
        if(children.size()!=count || p.containsKey("version")) invalid("计划子节点数量或协议字段无效");
        schema(p.get("schema")); for(var child:children)validate(child);
        switch(kind) {
            case "CreateTable" -> {text(p,"table");schema(p.get("columns"));if(nodes(p.get("columns")).isEmpty())invalid("空列定义");}
            case "Insert" -> {text(p,"table");List<String> columns=strings(p.get("columns"));List<Map<String,Object>> values=nodes(p.get("values"));
                if(columns.isEmpty() || columns.size()!=values.size() || p.containsKey("rows"))invalid("插入字段不匹配");for(var v:values)expression(v);}
            case "SeqScan" -> text(p,"table");
            case "Update" -> {text(p,"table");var assignments=nodes(p.get("assignments"));if(assignments.isEmpty())invalid("空赋值");
                for(var a:assignments){text(a,"column");expression(map(a.get("value")));}predicate(p,false);}
            case "Delete" -> {text(p,"table");predicate(p,false);}
            case "Filter" -> {predicate(p,true);if(!p.get("schema").equals(children.get(0).get("schema")))invalid("Filter schema 不一致");}
            case "Project" -> {var columns=strings(p.get("columns"));if(columns.isEmpty() || columns.size()!=nodes(p.get("schema")).size() || p.containsKey("items"))invalid("投影字段不匹配");}
            case "Sort" -> {var keys=nodes(p.get("keys"));if(keys.isEmpty())invalid("空排序键");for(var k:keys){text(k,"column");if(!Set.of("ASC","DESC").contains(text(k,"direction")))invalid("排序方向无效");}}
            case "Join" -> {text(p,"leftKey");text(p,"rightKey");}
            case "GroupBy" -> {strings(p.get("keys"));for(var a:nodes(p.get("aggregates"))){String fn=text(a,"function"),col=text(a,"column");text(a,"alias");
                if(!Set.of("COUNT","SUM").contains(fn) || (fn.equals("SUM") && col.equals("*")))invalid("聚合定义无效");}}
        }
    }
    private static void schema(Object value) {for(var c:nodes(value)){text(c,"name");if(!TYPES.contains(text(c,"dataType")))invalid("schema 类型无效");}}
    private static void predicate(Map<String,Object> p,boolean required) {
        if(!p.containsKey("predicate") || required && p.get("predicate")==null)invalid("缺少 predicate");
        if(p.get("predicate")!=null){var e=map(p.get("predicate"));expression(e);if(!Set.of("BOOL","NULL").contains(text(e,"inferredType")))invalid("predicate 类型无效");}
    }
    private static void expression(Map<String,Object> e) {
        String type=text(e,"inferredType"),kind=text(e,"kind");if(!TYPES.contains(type) && !type.equals("NULL"))invalid("表达式类型无效");
        switch(kind) {
            case "LiteralExpr" -> {if(!type.equals(text(e,"literalType")) || !e.containsKey("value") || e.get("value")==null)invalid("字面量无效");}
            case "NullLiteralExpr" -> {if(!type.equals("NULL") || !e.containsKey("value") || e.get("value")!=null)invalid("NULL 节点无效");}
            case "IdentifierExpr" -> {text(e,"name");var b=map(e.get("binding"));text(b,"table");text(b,"column");if(!type.equals(text(b,"dataType")))invalid("binding 类型不一致");}
            case "IsNullExpr" -> {expression(map(e.get("operand")));if(!type.equals("BOOL") || !(e.get("negated") instanceof Boolean))invalid("IS NULL 无效");}
            case "UnaryExpr" -> {var operand=map(e.get("operand"));expression(operand);String op=text(e,"operator");
                if(op.equals("NOT")){if(!type.equals("BOOL") || !Set.of("BOOL","NULL").contains(text(operand,"inferredType")))invalid("NOT 类型无效");}
                else if(!Set.of("+","-").contains(op) || !Set.of("INT","BIGINT","DECIMAL").contains(type))invalid("一元表达式无效");}
            case "BinaryExpr" -> {var l=map(e.get("left"));var r=map(e.get("right"));expression(l);expression(r);String op=text(e,"operator");
                if(Set.of("AND","OR").contains(op)){if(!type.equals("BOOL") || !Set.of("BOOL","NULL").contains(text(l,"inferredType")) || !Set.of("BOOL","NULL").contains(text(r,"inferredType")))invalid("布尔表达式类型无效");}
                else if(Set.of("=","!=","<","<=",">",">=").contains(op)){if(!type.equals("BOOL"))invalid("比较类型无效");}
                else if(!Set.of("+","-","*","/").contains(op) || !Set.of("INT","BIGINT","DECIMAL").contains(type))invalid("算术表达式无效");}
            default -> invalid("未知表达式："+kind);
        }
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object v){if(!(v instanceof Map<?,?>))throw new IllegalArgumentException("需要对象");return (Map<String,Object>)v;}
    private static List<Map<String,Object>> nodes(Object v){if(!(v instanceof List<?> l))throw new IllegalArgumentException("需要数组");List<Map<String,Object>> out=new ArrayList<>();for(Object x:l)out.add(map(x));return out;}
    private static List<String> strings(Object v){if(!(v instanceof List<?> l))throw new IllegalArgumentException("需要字符串数组");List<String> out=new ArrayList<>();for(Object x:l){if(!(x instanceof String s)||s.isEmpty())throw new IllegalArgumentException("需要列名");out.add(s);}return out;}
    private static String text(Map<String,Object> m,String key){if(!(m.get(key) instanceof String s)||s.isEmpty())throw new IllegalArgumentException("缺少字段："+key);return s;}
    private static void invalid(String message){throw new IllegalArgumentException(message);}
}
