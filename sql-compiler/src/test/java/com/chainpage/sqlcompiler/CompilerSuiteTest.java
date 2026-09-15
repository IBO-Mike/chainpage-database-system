package com.chainpage.sqlcompiler;

import com.chainpage.sqlcompiler.ast.AstServiceTest;
import com.chainpage.sqlcompiler.catalog.InMemoryCatalogTest;
import com.chainpage.sqlcompiler.explain.ExplainServiceTest;
import com.chainpage.sqlcompiler.extension.SqlExtensionTest;
import com.chainpage.sqlcompiler.lexer.LexerTest;
import com.chainpage.sqlcompiler.optimizer.OptimizerTest;
import com.chainpage.sqlcompiler.parser.ParserTest;
import com.chainpage.sqlcompiler.planner.PlanGeneratorTest;
import com.chainpage.sqlcompiler.randomtesting.RandomSqlTesterTest;
import com.chainpage.sqlcompiler.recovery.RecoveringParserTest;
import com.chainpage.sqlcompiler.semantic.SemanticAnalyzerTest;
import org.junit.jupiter.api.Test;

/** 把组内现有 main 方法验收程序纳入 Maven test 生命周期。 */
class CompilerSuiteTest {
    @Test void lexer() { LexerTest.main(new String[0]); }
    @Test void parser() { ParserTest.main(new String[0]); }
    @Test void ast() { AstServiceTest.main(new String[0]); }
    @Test void catalog() { InMemoryCatalogTest.main(new String[0]); }
    @Test void semantic() { SemanticAnalyzerTest.main(new String[0]); }
    @Test void planner() { PlanGeneratorTest.main(new String[0]); }
    @Test void optimizer() { OptimizerTest.main(new String[0]); }
    @Test void recovery() { RecoveringParserTest.main(new String[0]); }
    @Test void extension() { SqlExtensionTest.main(new String[0]); }
    @Test void explain() { ExplainServiceTest.main(new String[0]); }
    @Test void randomTesting() { RandomSqlTesterTest.main(new String[0]); }
}
