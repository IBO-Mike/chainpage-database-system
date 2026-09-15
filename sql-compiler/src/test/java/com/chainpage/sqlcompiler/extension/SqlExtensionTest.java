package com.chainpage.sqlcompiler.extension;

import com.chainpage.sqlcompiler.CompilerFrontEnd;
import com.chainpage.sqlcompiler.ast.JsonCodec;
import com.chainpage.sqlcompiler.optimizer.*;
import com.chainpage.sqlcompiler.recovery.StatementRecovery;
import java.math.BigDecimal;
import java.util.*;
import static com.chainpage.sqlcompiler.extension.SqlTree.*;

/** 从跨模块文档出发验证协议，而不是要求执行器接受编译器的私有格式。 */
public final class SqlExtensionTest {
    private static int checks;
    public static void main(String[] args) {
        envelopesAndRecovery(); standardPlansAndExecution(); errorsAndCommit(); malformedPlans();
        System.out.println("SQL contract tests passed: " + checks);
    }
    static ExtensionResponse compile(String sql, Map<String, Object> snapshot, boolean optimize) {
        return new CompilerFrontEnd().compile(node("requestId", "test-id", "sql", sql, "catalogSnapshot", snapshot, "optimize", optimize));
    }
    static void envelopesAndRecovery() {
        Map<String, Object> snapshot = node("tables", new ArrayList<>()); String before = JsonCodec.stringify(snapshot);
        String sql = "CREATE TABLE t(id INT); INSERT INTO t(id) VALUES(1); SELECT * FROM t WHERE 1=1;";
        for (boolean optimize : List.of(false, true)) {
            var response = compile(sql, snapshot, optimize); ok(response);
            check(response.data().keySet().equals(Set.of("requestId", "statements")), "compile fields");
            check(response.data().get("requestId").equals("test-id"), "requestId round trip");
            List<Map<String, Object>> statements = nodes(response.data().get("statements")); check(statements.size() == 3, "all artifacts");
            for (int i = 0; i < statements.size(); i++) {
                var s = statements.get(i); check(s.keySet().equals(Set.of("statementIndex", "tokens", "ast", "semantic", "plan", "optimizedPlan")), "exact artifact fields");
                check(s.get("statementIndex").equals(i), "index"); check((s.get("optimizedPlan") != null) == optimize, "optimize flag");
                check(nodes(s.get("tokens")).stream().filter(t -> "EOF".equals(t.get("type"))).count() == 1, "per-statement EOF");
                contract(map(s.get("plan")));
            }
            check(map(statements.get(2).get("ast")).containsKey("columns"), "basic AST shape retained");
        }
        check(before.equals(JsonCodec.stringify(snapshot)), "compile never persists CREATE");
        for (String sqlError : List.of("SELECT * FROM missing;", "SELECT FROM t;", "SELECT @ FROM t;")) {
            var e = compile(sqlError, snapshot, false); check(!e.ok(), "invalid compile");
            check(e.error().keySet().equals(Set.of("requestId", "statementIndex", "stage", "code", "message", "line", "column", "pageId")), "exact boundary error");
            check(e.error().get("pageId") == null && e.error().get("requestId").equals("test-id"), "error context");
        }
        var lexed = new ExtensionLexer().lex(node("sql", "SELECT FROM t; UPDATE t SET id=2; SELECT FROM t; SELECT id FROM t ORDER BY id;")); ok(lexed);
        var recovered = new StatementRecovery().parse(lexed.data()); ok(recovered);
        check(nodes(recovered.data().get("statements")).size() == 2 && nodes(recovered.data().get("errors")).size() == 2, "recovery covers extension");
        for (Map<String, Object> e : nodes(recovered.data().get("errors"))) check(e.keySet().containsAll(Set.of("stage", "line", "column", "unexpected", "expected")), "recovery diagnostics");
        var failed = compile("CREATE TABLE t(id INT); SELECT FROM t; UPDATE t SET id=2;", snapshot, true);
        check(!failed.ok() && failed.error().get("statementIndex").equals(1) && failed.data() == null, "recovered parse errors do not become partial compile success");
        check(!new CompilerFrontEnd().compile(Map.of()).ok(), "invalid request");
        check(!compile("", Map.of(), true).ok(), "empty sql still validates snapshot");
        check(!new StatementRecovery().parse(node("tokens", List.of())).ok(), "malformed tokens");
        check(!new ExtensionPlanner().buildPlan(node("statements", List.of(node("kind", "SelectStmt")))).ok(), "unannotated planner input");
    }
    static void standardPlansAndExecution() {
        InMemoryExtensionExecutor db = new InMemoryExtensionExecutor();
        run(db, "CREATE TABLE t(id INT,v INT); INSERT INTO t(id,v) VALUES(1,10); INSERT INTO t(id,v) VALUES(2,20); INSERT INTO t(id,v) VALUES(3,NULL);");
        rows(db, "SELECT id FROM t WHERE id>=2 ORDER BY id DESC;", List.of(List.of(3), List.of(2)));
        run(db, "UPDATE t SET v=v+1 WHERE id=1;"); rows(db, "SELECT v FROM t WHERE id=1;", List.of(List.of(11)));
        rows(db, "SELECT v,COUNT(*) AS n FROM t GROUP BY v ORDER BY v;", Arrays.asList(List.of(11,1), List.of(20,1), Arrays.asList(null,1)));
        rows(db, "SELECT SUM(v) AS total,COUNT(v) AS n FROM t;", List.of(List.of(31,2)));
        rows(db, "SELECT v,COUNT(*) AS n FROM t GROUP BY v HAVING COUNT(*)>0 ORDER BY v;", Arrays.asList(List.of(11,1), List.of(20,1), Arrays.asList(null,1)));
        rows(db, "SELECT a.id,b.v FROM t a INNER JOIN t b ON b.id=a.id ORDER BY a.id;", Arrays.asList(List.of(1,11), List.of(2,20), Arrays.asList(3,null)));
        for (String sql : List.of("SELECT id FROM t WHERE 1=1 ORDER BY id;", "SELECT v,COUNT(*) FROM t WHERE 1=1 GROUP BY v;", "UPDATE t SET v=v+1 WHERE 1=1;")) {
            var compiled = compile(sql, db.snapshot(), true); ok(compiled); Map<String, Object> artifact = nodes(compiled.data().get("statements")).get(0);
            contract(map(artifact.get("plan"))); contract(map(artifact.get("optimizedPlan")));
            var opt = new Optimizer().optimize(new OptimizeRequest(map(artifact.get("plan")))); check(opt.ok(), "public optimizer accepts extension");
            check(opt.optimizedPlan().equals(artifact.get("optimizedPlan")), "same optimization pipeline");
        }
        run(db, "CREATE TABLE typed(id BIGINT,d DECIMAL,b BOOL,day DATE); INSERT INTO typed(id,d,b,day) VALUES(2147483648,1.25,TRUE,DATE '2024-02-29');");
        rows(db, "SELECT id,d,b,day FROM typed;", List.of(List.of(2147483648L,new BigDecimal("1.25"),true,"2024-02-29")));
        for (int i = 0; i < 40; i++) {
            run(db, "UPDATE t SET v=" + i + " WHERE id=1;"); rows(db, "SELECT v FROM t WHERE id=1;", List.of(List.of(i)));
        }
        run(db, "CREATE TABLE collision(a0 INT); INSERT INTO collision(a0) VALUES(9);");
        rows(db, "SELECT _group.a0,COUNT(*) FROM collision _group GROUP BY _group.a0 HAVING COUNT(*)=1;", List.of(List.of(9,1)));
        run(db, "DELETE FROM t WHERE v IS NULL;"); rows(db, "SELECT id FROM t ORDER BY id;", List.of(List.of(1),List.of(2)));
    }
    static void errorsAndCommit() {
        InMemoryExtensionExecutor db = new InMemoryExtensionExecutor(); run(db, "CREATE TABLE t(id INT,v DECIMAL); INSERT INTO t(id,v) VALUES(1,10.0);");
        for (String sql : List.of("SELECT a.id FROM t a LEFT JOIN t b ON a.id=b.id;", "SELECT id+1 FROM t;", "SELECT AVG(v) FROM t;", "INSERT INTO t(id) VALUES(2),(3);", "SELECT id FROM t ORDER BY id NULLS FIRST;")) {
            var response = compile(sql, db.snapshot(), false); check(!response.ok() && response.error().get("stage").equals("PLANNER"), "unrepresentable SQL explicitly fails: " + sql);
        }
        var failure = new SqlExtension().executeSql("INSERT INTO t(id,v) VALUES(2,20.0); UPDATE t SET v=v/(id-1);", db.snapshot(), db);
        check(!failure.ok() && failure.error().get("statementIndex").equals(1), "failing statement index");
        check(nodes(failure.error().get("results")).size() == 1, "earlier execution results retained");
        rows(db, "SELECT id,v FROM t ORDER BY id;", List.of(List.of(1,10),List.of(2,20)));
        var partial = new SqlExtension().executeSql("UPDATE t SET v=v/(id-2);", db.snapshot(), db);
        check(!partial.ok(), "later row failure rolls back the entire statement");
        rows(db, "SELECT id,v FROM t ORDER BY id;", List.of(List.of(1,10),List.of(2,20)));
        String before = JsonCodec.stringify(db.snapshot()); compile("CREATE TABLE temp(id INT);", db.snapshot(), true);
        check(before.equals(JsonCodec.stringify(db.snapshot())), "compile isolation");
        final boolean[] called = {false};
        ExtensionExecutor unsupported = new ExtensionExecutor() {
            public Set<String> supportedPlanKinds() { return Set.of("Project", "SeqScan"); }
            public Set<String> supportedTypes() { return Set.of("INT", "VARCHAR"); }
            public ExtensionResponse execute(Map<String,Object> request) { called[0] = true; throw new AssertionError(); }
        };
        var denied = new SqlExtension().executeSql("SELECT id FROM t ORDER BY id;", db.snapshot(), unsupported);
        check(!denied.ok() && !called[0], "unsupported downstream fails before dispatch");
    }
    static void malformedPlans() {
        InMemoryExtensionExecutor db = new InMemoryExtensionExecutor(); run(db, "CREATE TABLE t(id INT);");
        Map<String,Object> insert = node("kind","Insert","table","t","columns",List.of("id"),"values",List.of(node("kind","LiteralExpr","literalType","INT","inferredType","INT","value",7)),"children",List.of(),"schema",List.of());
        ok(db.execute(node("plan",insert))); rows(db,"SELECT id FROM t;",List.of(List.of(7)));
        Map<String,Object> scan = node("kind","SeqScan","table","t","schema",List.of(node("name","id","dataType","INT")),"children",List.of());
        Map<String,Object> sort = node("kind","Sort","keys",List.of(node("column","id","direction","ASC")),"children",List.of(scan),"schema",scan.get("schema"));
        var scanned = db.execute(node("plan",scan)); ok(scanned);
        var row = nodes(scanned.data().get("rows")).get(0); check(row.keySet().equals(Set.of("rowId","values")), "InternalRow envelope");
        check(map(row.get("values")).get("id").equals(7), "InternalRow values");
        ok(db.execute(node("plan",sort))); // hand-written document plan, no private fields
        insert.remove("values"); check(!db.execute(node("plan",insert)).ok(), "missing values rejected");
        sort.put("keys",List.of(node("expression",Map.of(),"direction","ASC"))); check(!db.execute(node("plan",sort)).ok(), "old sort rejected");
    }
    static void contract(Map<String,Object> p) {
        String kind = text(p,"kind"); Set<String> fields = new HashSet<>(Set.of("kind","children","schema"));
        fields.addAll(switch(kind) {
            case "CreateTable","Insert" -> kind.equals("Insert") ? Set.of("table","columns","values") : Set.of("table","columns");
            case "Update" -> Set.of("table","assignments","predicate"); case "Delete" -> Set.of("table","predicate");
            case "SeqScan" -> Set.of("table"); case "Filter" -> Set.of("predicate"); case "Project" -> Set.of("columns");
            case "Sort" -> Set.of("keys"); case "GroupBy" -> Set.of("keys","aggregates"); case "Join" -> Set.of("leftKey","rightKey");
            default -> throw new AssertionError("unexpected kind " + kind);
        });
        check(p.keySet().equals(fields), "exact plan fields " + kind + ": " + p.keySet());
        if(kind.equals("Sort")) for(var key:nodes(p.get("keys"))) check(key.keySet().equals(Set.of("column","direction")),"sort key contract");
        if(kind.equals("GroupBy")) for(var a:nodes(p.get("aggregates"))) check(a.keySet().equals(Set.of("function","column","alias")),"aggregate contract");
        for(var child:nodes(p.get("children"))) contract(child);
    }
    static ExtensionResponse run(InMemoryExtensionExecutor db,String sql) { var r=new SqlExtension().executeSql(sql,db.snapshot(),db); ok(r); return r; }
    static void rows(InMemoryExtensionExecutor db,String sql,List<?> expected) {
        var r=run(db,sql); Map<String,Object> entry=nodes(r.data().get("results")).get(0);
        check(entry.keySet().equals(Set.of("statementIndex","kind","result")),"nested result contract");
        Object actual=map(entry.get("result")).get("rows"); check(normalize(actual).equals(normalize(expected)),sql+": "+actual+" != "+expected);
    }
    static Object normalize(Object value) {
        if(value instanceof Number n) return new BigDecimal(n.toString()).stripTrailingZeros();
        if(value instanceof List<?> l) {List<Object> out=new ArrayList<>();for(Object v:l)out.add(normalize(v));return out;} return value;
    }
    static void ok(ExtensionResponse r) {check(r.ok(),"success expected: "+r.error());}
    static void check(boolean v,String message) {checks++;if(!v)throw new AssertionError(message);}
}
