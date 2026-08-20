$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

Write-Host '[1/4] Validate Docker Compose'
docker compose --project-directory $root --env-file (Join-Path $root '.env.example') config --quiet

Write-Host '[2/4] Verify Web'
Push-Location (Join-Path $root 'apps/web')
try {
  npm run typecheck
  npm run test
  npm run build
} finally {
  Pop-Location
}

Write-Host '[3/4] Verify AI service'
Push-Location (Join-Path $root 'services/ai')
try {
  python -m ruff check app tests
  python -m pytest
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
} finally {
  Pop-Location
}

Write-Host 'All verification steps passed.'
