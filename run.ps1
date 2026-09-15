param()

# ChainPage DB 的 Windows PowerShell 一键启动脚本。
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$JarPath = Join-Path $Root "database-engine\target\chainpage-db.jar"
$NeedsBuild = -not (Test-Path -LiteralPath $JarPath -PathType Leaf)

if (-not $NeedsBuild) {
    $SourceRoots = @(
        (Join-Path $Root "sql-compiler\src"),
        (Join-Path $Root "page-storage-system\src"),
        (Join-Path $Root "database-engine\src")
    )
    $Inputs = @(
        (Get-Item -LiteralPath (Join-Path $Root "pom.xml"))
        (Get-Item -LiteralPath (Join-Path $Root "database-engine\pom.xml"))
        (Get-Item -LiteralPath (Join-Path $Root "sql-compiler\pom.xml"))
        (Get-Item -LiteralPath (Join-Path $Root "page-storage-system\pom.xml"))
        (Get-ChildItem -LiteralPath $SourceRoots -File -Recurse)
    )
    $JarTime = (Get-Item -LiteralPath $JarPath).LastWriteTimeUtc
    $NeedsBuild = $null -ne ($Inputs | Where-Object {
        $_.LastWriteTimeUtc -gt $JarTime
    } | Select-Object -First 1)
}

if ($NeedsBuild) {
    if ($null -eq (Get-Command mvn -ErrorAction SilentlyContinue)) {
        Write-Error "未找到 Maven，请先安装 Maven 或手动构建 database-engine\target\chainpage-db.jar。"
        exit 1
    }
    Write-Host "正在构建 ChainPage DB（跳过测试）；后续启动可直接使用本脚本。"
    & mvn -f (Join-Path $Root "pom.xml") -DskipTests -pl database-engine -am package
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}

& java -jar $JarPath @args
exit $LASTEXITCODE
