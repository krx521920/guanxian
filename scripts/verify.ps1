$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

function Assert-NativeCommandSucceeded {
  param([Parameter(Mandatory)] [string] $Step)
  if ($LASTEXITCODE -ne 0) {
    throw "$Step failed with exit code $LASTEXITCODE."
  }
}

Write-Host '[1/4] Validate Docker Compose'
$composeFiles = @(
  '-f', (Join-Path $root 'compose.yaml'),
  '-f', (Join-Path $root 'compose.e2e.yaml')
)
docker compose --project-directory $root `
  --project-name guanxian-platform-e2e `
  --env-file (Join-Path $root 'tests/e2e/compose.env') `
  @composeFiles --profile app config --quiet
if ($LASTEXITCODE -ne 0) {
  throw 'Docker Compose E2E configuration is invalid.'
}

Write-Host '[2/4] Verify Web'
Push-Location (Join-Path $root 'apps/web')
try {
  npm run typecheck
  Assert-NativeCommandSucceeded 'Web typecheck'
  npm run test
  Assert-NativeCommandSucceeded 'Web tests'
  npm run build
  Assert-NativeCommandSucceeded 'Web build'
} finally {
  Pop-Location
}

Write-Host '[3/4] Verify AI service'
Push-Location (Join-Path $root 'services/ai')
try {
  python -m ruff check app tests
  Assert-NativeCommandSucceeded 'AI lint'
  python -m pytest
  Assert-NativeCommandSucceeded 'AI tests'
} finally {
  Pop-Location
}

Write-Host '[4/4] Verify Java server'
$maven = Get-Command mvn -ErrorAction SilentlyContinue
if ($maven) {
  $mavenCommand = $maven.Source
} else {
  $localMaven = Join-Path $root '.tools/apache-maven-3.9.9/bin/mvn.cmd'
  if (-not (Test-Path $localMaven)) {
    throw 'Maven 3.9+ is required. Install Maven or place it under .tools/apache-maven-3.9.9.'
  }
  $mavenCommand = $localMaven
}

Push-Location (Join-Path $root 'apps/server')
try {
  & $mavenCommand --batch-mode --no-transfer-progress verify
  Assert-NativeCommandSucceeded 'Java verification'
} finally {
  Pop-Location
}

Write-Host 'All verification steps passed.'
