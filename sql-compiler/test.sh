#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
contract_build=$(mktemp -d "${TMPDIR:-/tmp}/chainpage-contract.XXXXXX")
trap 'rm -rf "$contract_build"' EXIT HUP INT TERM
rg --files sql-compiler/src -g '*.java' > "$contract_build/sources"
javac --release 17 -encoding UTF-8 -d "$contract_build/classes" @"$contract_build/sources"
for suite in lexer.LexerTest parser.ParserTest ast.AstServiceTest catalog.InMemoryCatalogTest semantic.SemanticAnalyzerTest planner.PlanGeneratorTest optimizer.OptimizerTest recovery.RecoveringParserTest extension.SqlExtensionTest explain.ExplainServiceTest randomtesting.RandomSqlTesterTest; do
    java -cp "$contract_build/classes" "com.chainpage.sqlcompiler.$suite"
done
