param(
    [Parameter(Mandatory=$true)][string]$BaselineJar,
    [Parameter(Mandatory=$true)][string]$ImprovedJar,
    [Parameter(Mandatory=$true)][string]$EvidenceDirectory,
    [string]$BaselineVersion = '89ed0c458d911c8001115174af1dd8d02c25cbc7',
    [string]$ImprovedVersion = 'working-tree'
)
$ErrorActionPreference = 'Stop'
$baseline = (Resolve-Path -LiteralPath $BaselineJar).Path
$improved = (Resolve-Path -LiteralPath $ImprovedJar).Path
if (Test-Path -LiteralPath $EvidenceDirectory) { throw 'Use a new evidence directory' }
$output = [IO.Path]::GetFullPath($EvidenceDirectory)
New-Item -ItemType Directory -Path $output | Out-Null
$classes = Join-Path $output 'classes'
New-Item -ItemType Directory -Path $classes | Out-Null
javac -cp $improved -d $classes (Join-Path $PSScriptRoot 'StoragePerformanceExperiment.java')
if ($LASTEXITCODE -ne 0) { throw 'Experiment compilation failed' }
$separator = [IO.Path]::PathSeparator
# Run sequentially so benchmark JVMs do not compete with one another.
$runs = @(
    @('mutation', 'baseline-mutation', $baseline, $BaselineVersion),
    @('mutation', 'improved-mutation', $improved, $ImprovedVersion),
    @('cache', 'improved-cache', $improved, $ImprovedVersion),
    @('lookup', 'improved-lookup', $improved, $ImprovedVersion)
)
foreach ($run in $runs) {
    java -cp "$classes$separator$($run[2])" StoragePerformanceExperiment $run[0] (Join-Path $output $run[1]) $run[3]
    if ($LASTEXITCODE -ne 0) { throw "Experiment failed: $($run[1])" }
}
$manifest = [ordered]@{
    baselineVersion = $BaselineVersion
    baselineJarSha256 = (Get-FileHash -LiteralPath $baseline -Algorithm SHA256).Hash
    improvedVersion = $ImprovedVersion
    improvedJarSha256 = (Get-FileHash -LiteralPath $improved -Algorithm SHA256).Hash
    java = @(java -version 2>&1 | ForEach-Object { "$_" })
    timestamp = (Get-Date).ToString('o')
}
$manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $output 'manifest.json') -Encoding utf8
Write-Host "PASS: results and JAR fingerprints saved in $output"
