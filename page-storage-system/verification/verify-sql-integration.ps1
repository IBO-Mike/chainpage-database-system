param(
    [Parameter(Mandatory = $true)][string]$DatabaseJar,
    [Parameter(Mandatory = $true)][string]$EvidenceDirectory
)

# Run the actual three-module JAR twice against a fresh isolated database.
# Build the JAR from the documented main + os-storage-core combination first.
$ErrorActionPreference = 'Stop'
$jar = (Resolve-Path -LiteralPath $DatabaseJar).Path
$evidence = [System.IO.Path]::GetFullPath($EvidenceDirectory)
if (Test-Path -LiteralPath $evidence) {
    throw 'EvidenceDirectory must be a new directory to avoid touching existing database data.'
}
New-Item -ItemType Directory -Path $evidence | Out-Null
$database = Join-Path $evidence 'database'
$checks = [System.Collections.Generic.List[string]]::new()

function Assert-Value($Actual, $Expected, [string]$Label) {
    $actualJson = ConvertTo-Json -InputObject $Actual -Depth 30 -Compress
    $expectedJson = ConvertTo-Json -InputObject $Expected -Depth 30 -Compress
    if ($actualJson -cne $expectedJson) {
        throw "${Label}: expected $expectedJson, got $actualJson"
    }
    $checks.Add($Label)
}

function Run-Cli([string]$Name, [string[]]$Requests) {
    $inputPath = Join-Path $evidence "$Name-input.jsonl"
    $outputPath = Join-Path $evidence "$Name-output.jsonl"
    $errorPath = Join-Path $evidence "$Name-stderr.txt"
    $Requests | Set-Content -LiteralPath $inputPath -Encoding utf8
    $raw = @(Get-Content -LiteralPath $inputPath -Encoding utf8 |
        & java -jar $jar --data $database 2> $errorPath)
    $exitCode = $LASTEXITCODE
    $raw | Set-Content -LiteralPath $outputPath -Encoding utf8
    Assert-Value $exitCode 0 "$Name process exit"
    Assert-Value $raw.Count $Requests.Count "$Name response count"
    return @($raw | ForEach-Object { ConvertFrom-Json -InputObject $_ -Depth 30 })
}

function Command($Response) {
    Assert-Value $Response.ok $true 'SQL success envelope'
    Assert-Value @($Response.data.results).Count 1 'one statement result'
    return $Response.data.results[0].result
}

function Assert-Rows($Actual, [string]$ExpectedJson, [string]$Label) {
    $expected = ConvertFrom-Json -InputObject $ExpectedJson -NoEnumerate
    Assert-Value $Actual $expected $Label
}

$first = Run-Cli 'first' @(
    'CREATE TABLE student(id INT, name VARCHAR, age INT);',
    "INSERT INTO student(id,name,age) VALUES (1,'Alice',20);",
    "INSERT INTO student(id,name,age) VALUES (2,'Bob',17);",
    'SELECT id,name FROM student WHERE age>18;',
    'DELETE FROM student WHERE id=1;',
    'SELECT * FROM student;',
    '{"sql":"SELECT id FROM student WHERE age>10;","mode":"compile"}',
    'SELECT * FROM missing_table;'
)
Assert-Value (Command $first[0]).kind 'CREATE' 'create table'
Assert-Value (Command $first[1]).affectedRows 1 'insert Alice'
Assert-Value (Command $first[2]).affectedRows 1 'insert Bob'
Assert-Rows (Command $first[3]).rows '[[1,"Alice"]]' 'filter and project'
Assert-Value (Command $first[4]).affectedRows 1 'delete one row'
Assert-Rows (Command $first[5]).rows '[[2,"Bob",17]]' 'only Bob remains'
Assert-Value $first[6].ok $true 'real compiler success'
if ($null -eq $first[6].data.results[0].plan) {
    throw 'Compile mode must return a real logical plan.'
}
Assert-Value $first[6].data.results[0].plan.kind 'Project' 'plan Project'
Assert-Value $first[6].data.results[0].plan.children[0].kind 'Filter' 'plan Filter'
Assert-Value $first[6].data.results[0].plan.children[0].children[0].kind 'SeqScan' 'plan SeqScan'
$checks.Add('real logical plan returned')
Assert-Value $first[7].ok $false 'missing table failure'
if ([string]::IsNullOrWhiteSpace($first[7].error.code) -or
    [string]::IsNullOrWhiteSpace($first[7].error.stage)) {
    throw 'Missing-table error must retain its code and stage.'
}
$checks.Add('error code and stage preserved')

$second = Run-Cli 'restart' @(
    'SELECT * FROM student;',
    "INSERT INTO student(id,name,age) VALUES (3,'Carol',22);",
    'SELECT id,name FROM student WHERE age>18;'
)
Assert-Rows (Command $second[0]).rows '[[2,"Bob",17]]' 'catalog and deletion survive restart'
Assert-Value (Command $second[1]).affectedRows 1 'insert after restart'
Assert-Rows (Command $second[2]).rows '[[3,"Carol"]]' 'query after restart'

$summary = [ordered]@{
    status = 'PASS'
    requests = 11
    processes = 2
    checks = $checks.ToArray()
    jarSha256 = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash
    missingTableError = $first[7].error
}
$summary | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath (Join-Path $evidence 'summary.json') -Encoding utf8
Write-Output "PASS: 11 SQL requests across 2 actual JVM processes. Evidence: $evidence"
