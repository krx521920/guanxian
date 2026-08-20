param(
  [ValidateSet('baseline', 'api')]
  [string]$Mode = 'baseline',
  [string]$Target = 'http://127.0.0.1:8081'
)

$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot '../common/SafeTarget.psm1') -Force
$targetUri = Assert-LocalHttpTarget $Target

$dockerTarget = $targetUri.AbsoluteUri.TrimEnd('/')
if ($targetUri.Host -in @('localhost', '127.0.0.1', '::1')) {
  $dockerTarget = $dockerTarget.Replace($targetUri.Host, 'host.docker.internal')
}

$reportDir = Join-Path (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path 'test-results/zap'
New-Item -ItemType Directory -Force -Path $reportDir | Out-Null
Copy-Item (Join-Path $PSScriptRoot 'zap-rules.conf') (Join-Path $reportDir 'zap-rules.conf') -Force

$scanScript = if ($Mode -eq 'api') { 'zap-api-scan.py' } else { 'zap-baseline.py' }
$args = @(
  'run', '--rm',
  '--add-host', 'host.docker.internal:host-gateway',
  '-v', "${reportDir}:/zap/wrk/:rw",
  'ghcr.io/zaproxy/zaproxy:stable',
  $scanScript,
  '-t', $dockerTarget,
  '-c', 'zap-rules.conf',
  '-r', "zap-$Mode.html",
  '-J', "zap-$Mode.json",
  '-I'
)
if ($Mode -eq 'api') { $args += @('-f', 'openapi') }

& docker @args
if ($LASTEXITCODE -ne 0) { throw "ZAP $Mode 扫描失败，退出码 $LASTEXITCODE" }
