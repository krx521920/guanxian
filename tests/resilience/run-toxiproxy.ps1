param(
  [int]$LatencyMs = 1500
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path

docker compose --project-directory $root --profile chaos up -d ai toxiproxy
if ($LASTEXITCODE -ne 0) { throw '无法启动 AI 与 Toxiproxy 容器。' }

$payload = Get-Content -Raw (Join-Path $PSScriptRoot 'toxiproxy-populate.json')
$headers = @{ 'Content-Type' = 'application/json' }

for ($attempt = 0; $attempt -lt 20; $attempt++) {
  try {
    Invoke-RestMethod 'http://127.0.0.1:8474/populate' -Method Post -Headers $headers -Body $payload | Out-Null
    break
  } catch {
    if ($attempt -eq 19) { throw }
    Start-Sleep -Seconds 1
  }
}

$normalWatch = [Diagnostics.Stopwatch]::StartNew()
$normal = Invoke-RestMethod 'http://127.0.0.1:18002/health'
$normalWatch.Stop()

$toxic = @{
  name = 'latency_downstream'
  type = 'latency'
  stream = 'downstream'
  toxicity = 1.0
  attributes = @{ latency = $LatencyMs; jitter = 50 }
} | ConvertTo-Json -Depth 4

try {
  Invoke-RestMethod 'http://127.0.0.1:8474/proxies/ai_service/toxics' -Method Post -Headers $headers -Body $toxic | Out-Null
  $slowWatch = [Diagnostics.Stopwatch]::StartNew()
  $slow = Invoke-RestMethod 'http://127.0.0.1:18002/health'
  $slowWatch.Stop()
  if ($slowWatch.ElapsedMilliseconds -lt ($LatencyMs - 150)) {
    throw "延迟注入未达到预期：$($slowWatch.ElapsedMilliseconds)ms"
  }

  [ordered]@{
    normal_status = $normal.status
    normal_ms = $normalWatch.ElapsedMilliseconds
    injected_status = $slow.status
    injected_ms = $slowWatch.ElapsedMilliseconds
    expected_latency_ms = $LatencyMs
  } | ConvertTo-Json
} finally {
  Invoke-RestMethod 'http://127.0.0.1:8474/proxies/ai_service/toxics/latency_downstream' -Method Delete -ErrorAction SilentlyContinue | Out-Null
}
