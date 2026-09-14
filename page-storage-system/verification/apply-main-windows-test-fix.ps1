param([Parameter(Mandatory=$true)][string]$IntegrationCheckout)
$ErrorActionPreference = 'Stop'
# ddb0dd7's println assertion assumes LF. PrintStream uses the platform newline.
# Change only the expected newline, in an isolated integration checkout.
$file = Join-Path $IntegrationCheckout 'database-engine/src/test/java/edu/csu/chainpage/engine/api/DatabaseCliTest.java'
$path = (Resolve-Path -LiteralPath $file).Path
$original = 'assertEquals("Query OK, 1 row affected\n", output.toString(StandardCharsets.UTF_8));'
$portable = 'assertEquals("Query OK, 1 row affected" + System.lineSeparator(), output.toString(StandardCharsets.UTF_8));'
$text = [IO.File]::ReadAllText($path)
if ($text.Contains($portable)) { return }
if (-not $text.Contains($original)) { throw 'Unknown upstream test version; refusing to rewrite it' }
if ($text.IndexOf($original) -ne $text.LastIndexOf($original)) { throw 'Ambiguous assertion' }
[IO.File]::WriteAllText($path, $text.Replace($original, $portable), [Text.UTF8Encoding]::new($false))
Write-Host 'Applied the platform-newline assertion fix in the isolated integration checkout'
