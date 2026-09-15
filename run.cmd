@echo off
setlocal EnableExtensions

rem ChainPage DB 的 Windows 命令行一键启动脚本。
set "ROOT=%~dp0"
set "JAR=%ROOT%database-engine\target\chainpage-db.jar"

where powershell >nul 2>nul
if not errorlevel 1 (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%run.ps1" %*
    if errorlevel 1 exit /b 1
    exit /b 0
)

if not exist "%JAR%" (
    where mvn >nul 2>nul
    if errorlevel 1 (
        echo 未找到 Maven，请先安装 Maven 或手动构建 database-engine\target\chainpage-db.jar。
        exit /b 1
    )
    echo 正在构建 ChainPage DB（跳过测试）；后续启动可直接使用本脚本。
    call mvn -f "%ROOT%pom.xml" -DskipTests -pl database-engine -am package
    if errorlevel 1 exit /b 1
)

java -jar "%JAR%" %*
exit /b %errorlevel%
