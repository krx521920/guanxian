[CmdletBinding()]
param(
  [switch]$NoBuild,
  [ValidateRange(60, 1800)]
  [int]$WaitTimeoutSeconds = 300
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$envFile = Join-Path $root 'tests/e2e/compose.env'
$composeArguments = @(
  'compose',
  '--project-name', 'guanxian-platform-e2e',
  '--project-directory', $root,
  '--env-file', $envFile,
  '-f', (Join-Path $root 'compose.yaml'),
  '-f', (Join-Path $root 'compose.e2e.yaml'),
  '--profile', 'app'
)

function Invoke-Compose {
  param([Parameter(Mandatory)] [string[]] $Arguments)

  & docker @composeArguments @Arguments
  if ($LASTEXITCODE -ne 0) {
    throw "Docker Compose failed: $($Arguments -join ' ')"
  }
}

function Assert-HttpReady {
  param(
    [Parameter(Mandatory)] [string] $Name,
    [Parameter(Mandatory)] [string] $Uri
  )

  $response = Invoke-WebRequest -UseBasicParsing -Uri $Uri -TimeoutSec 10
  if ([int]$response.StatusCode -ne 200) {
    throw "$Name readiness check returned HTTP $($response.StatusCode)."
  }
}

if (-not (Test-Path -LiteralPath $envFile)) {
  throw "E2E environment file not found: $envFile"
}

try {
  Invoke-Compose @('config', '--quiet')

  $upArguments = @('up', '--detach', '--wait', '--wait-timeout', "$WaitTimeoutSeconds")
  if (-not $NoBuild) {
    $upArguments += '--build'
  }
  Invoke-Compose $upArguments

  Assert-HttpReady -Name 'Keycloak discovery' `
    -Uri 'http://127.0.0.1:18081/realms/guanxian-ci/.well-known/openid-configuration'
  Assert-HttpReady -Name 'MinIO' -Uri 'http://127.0.0.1:19000/minio/health/ready'
  Assert-HttpReady -Name 'server' -Uri 'http://127.0.0.1:18080/api/v1/health'
  Assert-HttpReady -Name 'web' -Uri 'http://127.0.0.1:18082/'

  Invoke-Compose @('exec', '-T', 'postgres', 'pg_isready', '-U', 'guanxian', '-d', 'guanxian')
  Invoke-Compose @('exec', '-T', 'redis', 'redis-cli', 'ping')

  $seedContainerId = (& docker @composeArguments 'ps' '-q' 'e2e-seed').Trim()
  if ([string]::IsNullOrWhiteSpace($seedContainerId)) {
    throw 'The E2E seed container was not created.'
  }
  $seedExitCode = (& docker inspect --format '{{.State.ExitCode}}' $seedContainerId).Trim()
  if ($LASTEXITCODE -ne 0 -or $seedExitCode -ne '0') {
    throw "The E2E seed container did not complete successfully (exit=$seedExitCode)."
  }

  [pscustomobject]@{
    Postgres = 'ready'
    Redis = 'ready'
    MinIO = 'ready'
    Keycloak = 'ready'
    Server = 'ready'
    Web = 'ready'
    Seed = 'completed'
    WebUrl = 'http://127.0.0.1:18082'
  }
} catch {
  Write-Warning 'E2E stack startup failed. Current service state follows.'
  & docker @composeArguments 'ps'
  & docker @composeArguments 'logs' '--no-color' '--tail' '120' 'keycloak' 'server' 'e2e-seed' 'web'
  throw
}
