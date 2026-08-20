param(
  [string]$SchemaUrl = 'http://127.0.0.1:18001/openapi.json',
  [int]$MaxExamples = 50
)

$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot '../common/SafeTarget.psm1') -Force
Assert-LocalHttpTarget $SchemaUrl | Out-Null

$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$command = Get-Command schemathesis -ErrorAction SilentlyContinue
$bundled = Join-Path $root '.tools/security-venv/Scripts/schemathesis.exe'
if ($command) {
  $executable = $command.Source
} elseif (Test-Path $bundled) {
  $executable = $bundled
} else {
  throw '未找到 schemathesis。请执行：python -m pip install schemathesis'
}

$reportDir = Join-Path $root 'test-results/schemathesis'
New-Item -ItemType Directory -Force -Path $reportDir | Out-Null
$previousPythonUtf8 = $env:PYTHONUTF8
$previousPythonIoEncoding = $env:PYTHONIOENCODING
try {
  $env:PYTHONUTF8 = '1'
  $env:PYTHONIOENCODING = 'utf-8'
  & $executable run --no-color --max-examples $MaxExamples --workers 2 --report junit --report-junit-path (Join-Path $reportDir 'junit.xml') $SchemaUrl
  if ($LASTEXITCODE -ne 0) { throw "Schemathesis 模糊测试失败，退出码 $LASTEXITCODE" }
} finally {
  $env:PYTHONUTF8 = $previousPythonUtf8
  $env:PYTHONIOENCODING = $previousPythonIoEncoding
}
