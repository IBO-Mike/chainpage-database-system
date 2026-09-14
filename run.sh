#!/usr/bin/env sh

# ChainPage DB 的 macOS/Linux 一键启动脚本。
# JAR 不存在或源码比 JAR 更新时，先构建一个跳过测试的可执行 JAR。
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
JAR_PATH="$SCRIPT_DIR/database-engine/target/chainpage-db.jar"
NEED_BUILD=0

if [ ! -f "$JAR_PATH" ]; then
    NEED_BUILD=1
else
    for INPUT_PATH in \
        "$SCRIPT_DIR/pom.xml" \
        "$SCRIPT_DIR/database-engine/pom.xml" \
        "$SCRIPT_DIR/sql-compiler/pom.xml" \
        "$SCRIPT_DIR/page-storage-system/pom.xml"; do
        if [ -f "$INPUT_PATH" ] && [ "$INPUT_PATH" -nt "$JAR_PATH" ]; then
            NEED_BUILD=1
        fi
    done
    if find \
        "$SCRIPT_DIR/sql-compiler/src" \
        "$SCRIPT_DIR/page-storage-system/src" \
        "$SCRIPT_DIR/database-engine/src" \
        -type f -newer "$JAR_PATH" -print -quit | grep -q .; then
        NEED_BUILD=1
    fi
fi

if [ "$NEED_BUILD" -eq 1 ]; then
    if ! command -v mvn >/dev/null 2>&1; then
        echo "未找到 Maven，请先安装 Maven 或手动构建 database-engine/target/chainpage-db.jar。" >&2
        exit 1
    fi
    echo "正在构建 ChainPage DB（跳过测试）；后续启动可直接使用本脚本。" >&2
    mvn -f "$SCRIPT_DIR/pom.xml" -DskipTests -pl database-engine -am package
fi

exec java --enable-native-access=ALL-UNNAMED -jar "$JAR_PATH" "$@"
