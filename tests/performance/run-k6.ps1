param(
  [ValidateSet('smoke', 'load', 'stress')]
  [string]$Profile = 'smoke',
  [string]$BaseUrl = 'http://127.0.0.1:18080',
  [string]$JwtToken = $env:JWT_TOKEN,
  [string]$SummaryExport
)

$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot '../common/SafeTarget.psm1') -Force
$target = Assert-LocalHttpTarget $BaseUrl
if ([string]::IsNullOrWhiteSpace($JwtToken)) {
  throw 'JWT_TOKEN is required. Obtain a short-lived token from the isolated or approved test IdP.'
}

$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$reportDir = Join-Path $root 'test-results/k6'
New-Item -ItemType Directory -Force -Path $reportDir | Out-Null
if (-not $SummaryExport) {
  $SummaryExport = Join-Path $reportDir "$Profile-summary.json"
}
$k6Command = Get-Command k6 -ErrorAction SilentlyContinue
$bundledK6 = Join-Path $root '.tools/k6/k6-v2.1.0-windows-amd64/k6.exe'
if (-not $k6Command -and (Test-Path $bundledK6)) {
  $k6Executable = $bundledK6
} elseif ($k6Command) {
  $k6Executable = $k6Command.Source
}

if ($k6Executable) {
  $previousBaseUrl = $env:BASE_URL
  $previousProfile = $env:PROFILE
  $previousJwtToken = $env:JWT_TOKEN
  try {
    $env:BASE_URL = $target.AbsoluteUri.TrimEnd('/')
    $env:PROFILE = $Profile
    $env:JWT_TOKEN = $JwtToken
    & $k6Executable run --summary-export $SummaryExport (Join-Path $PSScriptRoot 'api-load.js')
    if ($LASTEXITCODE -ne 0) { throw "k6 $Profile 测试失败，退出码 $LASTEXITCODE" }
  } finally {
    $env:BASE_URL = $previousBaseUrl
    $env:PROFILE = $previousProfile
    $env:JWT_TOKEN = $previousJwtToken
  }
  return
}

$dockerUrl = $target.AbsoluteUri.TrimEnd('/')
if ($target.Host -in @('localhost', '127.0.0.1', '::1')) {
  $dockerUrl = $dockerUrl.Replace($target.Host, 'host.docker.internal')
}

$scriptDir = (Resolve-Path $PSScriptRoot).Path
$args = @(
  'run', '--rm',
  '--add-host', 'host.docker.internal:host-gateway',
  '-e', "BASE_URL=$dockerUrl",
  '-e', "PROFILE=$Profile",
  '-e', 'JWT_TOKEN',
  '-v', "${scriptDir}:/scripts:ro",
  '-v', "${reportDir}:/results:rw",
  'grafana/k6:2.1.0',
  'run', '--summary-export', "/results/$Profile-summary.json", '/scripts/api-load.js'
)
& docker @args
if ($LASTEXITCODE -ne 0) { throw "k6 $Profile 测试失败，退出码 $LASTEXITCODE" }
