param(
  [int]$ServerPort = 18080,
  [int]$AiPort = 18001
)

$ErrorActionPreference = 'Stop'
$isWindowsHost = $env:OS -eq 'Windows_NT'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$runDir = Join-Path $root 'test-results/smoke'
New-Item -ItemType Directory -Force -Path $runDir | Out-Null

$serverJar = Join-Path $root 'apps/server/bootstrap/target/bootstrap-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path $serverJar)) {
  throw "未找到后端可执行包：$serverJar。请先运行 Maven verify。"
}

$requiredSmokeServerOverrides = @(
  '--guanxian.business.repository=memory'
  '--guanxian.member.repository=memory'
  '--guanxian.member.seed-demo-data=true'
  '--guanxian.security.mode=demo'
  '--spring.flyway.enabled=false'
)
$smokeServerOverrides = @(
  '--guanxian.business.repository=memory'
  '--guanxian.member.repository=memory'
  '--guanxian.member.seed-demo-data=true'
  '--guanxian.security.mode=demo'
  '--spring.flyway.enabled=false'
)

$serverStart = @{
  FilePath = 'java'
  ArgumentList = @('-jar', $serverJar, "--server.port=$ServerPort") + $smokeServerOverrides
  RedirectStandardOutput = (Join-Path $runDir 'server.out.log')
  RedirectStandardError = (Join-Path $runDir 'server.err.log')
  PassThru = $true
}
$missingSmokeOverrides = @(
  $requiredSmokeServerOverrides | Where-Object { $serverStart.ArgumentList -notcontains $_ }
)
if ($missingSmokeOverrides.Count -gt 0 -or
    $smokeServerOverrides.Count -ne $requiredSmokeServerOverrides.Count) {
  throw "Smoke后端运行模式契约不完整：$($missingSmokeOverrides -join ', ')"
}
if ($isWindowsHost) { $serverStart.WindowStyle = 'Hidden' }

$serverProc = $null
$aiProc = $null
try {
  $serverProc = Start-Process @serverStart

  $aiStart = @{
  FilePath = 'python'
  ArgumentList = @('-m', 'uvicorn', 'app.main:app', '--host', '127.0.0.1', '--port', $AiPort)
  WorkingDirectory = (Join-Path $root 'services/ai')
  RedirectStandardOutput = (Join-Path $runDir 'ai.out.log')
  RedirectStandardError = (Join-Path $runDir 'ai.err.log')
  PassThru = $true
  }
  if ($isWindowsHost) { $aiStart.WindowStyle = 'Hidden' }
  $aiProc = Start-Process @aiStart

  $serverUrl = "http://127.0.0.1:$ServerPort"
  $aiUrl = "http://127.0.0.1:$AiPort"
  $serverReady = $false
  $aiReady = $false

  for ($attempt = 0; $attempt -lt 30; $attempt++) {
    if (-not $serverReady) {
      try { $serverHealth = Invoke-RestMethod "$serverUrl/api/v1/health"; $serverReady = $true } catch {}
    }
    if (-not $aiReady) {
      try {
        $aiHealthResponse = Invoke-WebRequest -UseBasicParsing "$aiUrl/health"
        $aiHealth = $aiHealthResponse.Content | ConvertFrom-Json
        $aiReady = $true
      } catch {}
    }
    if ($serverReady -and $aiReady) { break }
    Start-Sleep -Seconds 1
  }

  if (-not $serverReady) { throw '业务后端未在30秒内启动。' }
  if (-not $aiReady) { throw 'AI服务未在30秒内启动。' }

  $basic = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes('association-admin:admin123'))
  $serverRequestId = "smoke-server-$([Guid]::NewGuid().ToString('N'))"
  $headers = @{
    Authorization = "Basic $basic"
    'X-Request-Id' = $serverRequestId
  }
  $currentUserResponse = Invoke-WebRequest -UseBasicParsing "$serverUrl/api/v1/users/me" -Headers $headers
  if ($currentUserResponse.Headers['X-Request-Id'] -ne $serverRequestId) {
    throw '业务后端未按约定回显X-Request-Id。'
  }
  $currentUser = $currentUserResponse.Content | ConvertFrom-Json
  $policies = Invoke-RestMethod "$serverUrl/api/v1/policies" -Headers $headers

  $unauthorizedRequestId = "smoke-unauthorized-$([Guid]::NewGuid().ToString('N'))"
  $unauthorizedResponse = Invoke-WebRequest "$serverUrl/api/v1/policies" -Headers @{
    'X-Request-Id' = $unauthorizedRequestId
  } -SkipHttpErrorCheck
  $unauthorizedStatus = [int]$unauthorizedResponse.StatusCode
  if ($unauthorizedStatus -ne 401) { throw "反向鉴权测试失败，预期401，实际$unauthorizedStatus" }
  if ($unauthorizedResponse.Headers['X-Request-Id'] -ne $unauthorizedRequestId) {
    throw '业务后端鉴权失败响应未保留X-Request-Id。'
  }
  $unauthorizedBody = $unauthorizedResponse.Content | ConvertFrom-Json
  if ($unauthorizedBody.code -ne 'AUTHENTICATION_REQUIRED') {
    throw "鉴权错误契约不一致，预期AUTHENTICATION_REQUIRED，实际$($unauthorizedBody.code)"
  }

  if ($aiHealthResponse.Headers['X-Content-Type-Options'] -ne 'nosniff') {
    throw 'AI服务缺少X-Content-Type-Options安全响应头。'
  }

  $matchBody = @{
    demand = @{
      demand_id = 'D-SMOKE'
      title = '燃气管线阀门供应'
      scenarios = @('燃气')
      required_capabilities = @('阀门')
      required_qualifications = @('ISO9001')
      region = '北京'
    }
    candidates = @(@{
      enterprise_id = 'E-SMOKE'
      enterprise_name = '燃气阀门企业'
      scenarios = @('燃气')
      capabilities = @('阀门')
      qualifications = @('ISO9001')
      service_regions = @('北京')
      case_count = 4
      data_completeness = 1
      updated_days_ago = 3
    })
  } | ConvertTo-Json -Depth 8

  $aiRequestId = "smoke-ai-$([Guid]::NewGuid().ToString('N'))"
  $aiMatchResponse = Invoke-WebRequest -UseBasicParsing "$aiUrl/api/v1/match/enterprises" `
    -Method Post `
    -ContentType 'application/json' `
    -Headers @{ 'X-Request-Id' = $aiRequestId } `
    -Body $matchBody
  if ($aiMatchResponse.Headers['X-Request-Id'] -ne $aiRequestId) {
    throw 'AI服务未按约定回显X-Request-Id。'
  }
  $aiMatch = $aiMatchResponse.Content | ConvertFrom-Json
  if ($aiMatch.algorithm_version -ne 'rules-v1' -or $aiMatch.processing_mode -ne 'deterministic_rules') {
    throw 'AI匹配算法版本或处理模式契约不一致。'
  }
  if ($aiMatch.total_candidates -ne 1 -or $aiMatch.matches.Count -ne 1) {
    throw 'AI匹配候选数量契约不一致。'
  }

  $memberSuffix = [Guid]::NewGuid().ToString('N').Substring(0, 12)
  $memberBody = @{
    name = "冒烟企业-$memberSuffix"
    unifiedSocialCreditCode = "SMOKE$memberSuffix"
    category = '测试企业'
    capabilities = @('管线检测')
    products = @('测试产品')
    cooperationNeeds = @('测试需求')
    status = 'ACTIVE'
  } | ConvertTo-Json -Depth 5
  $memberCreate = Invoke-WebRequest -UseBasicParsing "$serverUrl/api/v1/members" `
    -Method Post `
    -ContentType 'application/json' `
    -Headers $headers `
    -Body $memberBody
  $createdMember = $memberCreate.Content | ConvertFrom-Json
  $initialEtag = [string]$memberCreate.Headers['ETag']
  if ($initialEtag -ne '"0"' -or $createdMember.data.version -ne 0) {
    throw "会员创建版本契约不一致，ETag=$initialEtag version=$($createdMember.data.version)"
  }

  $updatedMemberBody = @{
    name = "冒烟企业-$memberSuffix-更新"
    unifiedSocialCreditCode = "SMOKE$memberSuffix"
    category = '测试企业'
    capabilities = @('管线检测', '泄漏预警')
    products = @('测试产品')
    cooperationNeeds = @('测试需求')
    status = 'ACTIVE'
  } | ConvertTo-Json -Depth 5
  $memberUpdateHeaders = @{
    Authorization = "Basic $basic"
    'X-Request-Id' = "smoke-member-update-$memberSuffix"
    'If-Match' = $initialEtag
  }
  $memberUrl = "$serverUrl/api/v1/members/$($createdMember.data.id)"
  $memberUpdate = Invoke-WebRequest -UseBasicParsing $memberUrl `
    -Method Put `
    -ContentType 'application/json' `
    -Headers $memberUpdateHeaders `
    -Body $updatedMemberBody
  $updatedMember = $memberUpdate.Content | ConvertFrom-Json
  $updatedEtag = [string]$memberUpdate.Headers['ETag']
  if ($updatedEtag -ne '"1"' -or $updatedMember.data.version -ne 1) {
    throw "会员更新版本契约不一致，ETag=$updatedEtag version=$($updatedMember.data.version)"
  }

  $staleUpdate = Invoke-WebRequest -UseBasicParsing $memberUrl `
    -Method Put `
    -ContentType 'application/json' `
    -Headers @{
      Authorization = "Basic $basic"
      'X-Request-Id' = "smoke-member-stale-$memberSuffix"
      'If-Match' = $initialEtag
    } `
    -Body $updatedMemberBody `
    -SkipHttpErrorCheck
  $staleBody = $staleUpdate.Content | ConvertFrom-Json
  if ([int]$staleUpdate.StatusCode -ne 412 -or $staleBody.code -ne 'PRECONDITION_FAILED') {
    throw "过期会员版本未被拒绝，status=$($staleUpdate.StatusCode) code=$($staleBody.code)"
  }

  $memberDelete = Invoke-WebRequest -UseBasicParsing $memberUrl `
    -Method Delete `
    -Headers @{
      Authorization = "Basic $basic"
      'X-Request-Id' = "smoke-member-delete-$memberSuffix"
      'If-Match' = $updatedEtag
    }
  if ([int]$memberDelete.StatusCode -ne 200) {
    throw "会员清理失败，status=$($memberDelete.StatusCode)"
  }

  $result = [ordered]@{
    server_status = $serverHealth.data.status
    authenticated_user = $currentUser.data.username
    unauthenticated_status = $unauthorizedStatus
    unauthenticated_code = $unauthorizedBody.code
    request_trace = 'ok'
    policy_count = $policies.data.Count
    ai_status = $aiHealth.status
    ai_security_headers = 'ok'
    ai_algorithm_version = $aiMatch.algorithm_version
    optimistic_concurrency = 'ok'
    top_match = $aiMatch.matches[0].enterprise_name
    top_score = $aiMatch.matches[0].score
    checked_at = (Get-Date).ToString('o')
  }
  $result | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $runDir 'result.json')
  $result | ConvertTo-Json
} finally {
  if ($null -ne $serverProc -and -not $serverProc.HasExited) {
    Stop-Process -Id $serverProc.Id -Force -ErrorAction SilentlyContinue
  }
  if ($null -ne $aiProc -and -not $aiProc.HasExited) {
    Stop-Process -Id $aiProc.Id -Force -ErrorAction SilentlyContinue
  }
}
