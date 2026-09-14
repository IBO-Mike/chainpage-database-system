@echo off
setlocal
chcp 65001 >nul

if not exist "target\classes" mkdir "target\classes"
dir /s /b "src\main\java\*.java" > "target\sources-utf8.txt"

javac --release 17 -encoding UTF-8 -d "target\classes" @"target\sources-utf8.txt"
if errorlevel 1 (
    echo Java compilation failed.
    exit /b 1
)

java -Dfile.encoding=UTF-8 -Dstdin.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp "target\classes" com.chainpage.sqlcompiler.ConsoleApp
endlocal
