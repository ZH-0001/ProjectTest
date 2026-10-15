param([int]$Port = 8081)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskConfig = Join-Path $taskRoot 'backend\.env.local'
$taskUrl = $env:DB_TEST_URL
if (-not $taskUrl -and (Test-Path -LiteralPath $taskConfig)) {
    foreach ($taskLine in [System.IO.File]::ReadAllLines($taskConfig)) {
        if ($taskLine -match '^DB_TEST_URL\s*=(.*)$') { $taskUrl = $Matches[1].Trim() }
    }
}
if ($taskUrl -notmatch '^jdbc:mysql://[^/]+/[A-Za-z0-9_]+_test(?:\?.*)?$') {
    throw 'Configure a dedicated DB_TEST_URL ending in _test before browser tests.'
}
$taskOldUrl = $env:DB_URL
$taskOldPort = $env:PORT
try {
    $env:DB_URL = $taskUrl
    $env:PORT = [string]$Port
    Push-Location $taskRoot
    try { & mvn -B -f backend/pom.xml spring-boot:run; if ($LASTEXITCODE -ne 0) { throw 'Test backend did not start successfully.' } }
    finally { Pop-Location }
} finally { $env:DB_URL = $taskOldUrl; $env:PORT = $taskOldPort }
