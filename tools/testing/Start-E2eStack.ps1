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

  # Successful oneshot services have already exited. Plain `ps` hides them.
  $seedQueryOutput = @(& docker @composeArguments 'ps' '--all' '--quiet' 'e2e-seed')
  if ($LASTEXITCODE -ne 0) {
    throw 'Failed to query the E2E seed container with Docker Compose.'
  }
  $seedContainerIds = @($seedQueryOutput | ForEach-Object { ([string]$_).Trim() } |
    Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
  if ($seedContainerIds.Count -eq 0) {
    throw 'The E2E seed container was not created.'
  }
  if ($seedContainerIds.Count -ne 1) {
    throw 'Expected exactly one E2E seed container; inspect the isolated project before retrying.'
  }
  $seedContainerId = $seedContainerIds[0]
  if ($seedContainerId -notmatch '^[a-f0-9]{12,64}$') {
    throw 'Docker Compose returned an invalid container ID for the E2E seed service.'
  }

  $seedStateOutput = @(& docker inspect --type container --format '{{json .State}}' $seedContainerId)
  if ($LASTEXITCODE -ne 0) {
    throw 'Failed to inspect the E2E seed container state.'
  }
  try {
    $seedStateJson = ($seedStateOutput -join "`n").Trim()
    if (-not $seedStateJson.StartsWith('{')) {
      throw 'Expected a container state object.'
    }
    $seedState = ConvertFrom-Json -InputObject $seedStateJson -ErrorAction Stop
  } catch {
    throw 'Docker returned invalid or incomplete state for the E2E seed container.'
  }
  if ($seedState -isnot [pscustomobject] -or $seedState.Status -isnot [string] -or
      $seedState.Running -isnot [bool] -or $seedState.OOMKilled -isnot [bool] -or
      ($seedState.ExitCode -isnot [int] -and $seedState.ExitCode -isnot [long])) {
    throw 'Docker returned invalid or incomplete state for the E2E seed container.'
  }
  # Running/created containers can also expose ExitCode=0; that is not completion.
  if ($seedState.Status -ne 'exited' -or $seedState.Running -or $seedState.OOMKilled -or
      $seedState.ExitCode -ne 0) {
    throw "The E2E seed container did not complete successfully (status=$($seedState.Status), exit=$($seedState.ExitCode), oom=$($seedState.OOMKilled))."
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
  & docker @composeArguments 'ps' '--all'
  & docker @composeArguments 'logs' '--no-color' '--tail' '120' 'keycloak' 'server' 'e2e-seed' 'web'
  throw
}
