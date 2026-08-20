function Assert-LocalHttpTarget {
  param(
    [Parameter(Mandatory = $true)]
    [string]$Target
  )

  try {
    $uri = [System.Uri]$Target
  } catch {
    throw "目标地址格式无效：$Target"
  }

  $allowedHosts = @(
    'localhost',
    '127.0.0.1',
    '::1',
    'host.docker.internal',
    'server',
    'web',
    'ai',
    'toxiproxy'
  )

  if ($uri.Scheme -notin @('http', 'https') -or $uri.Host -notin $allowedHosts) {
    throw "安全限制：测试只允许访问本机或 Compose 内部目标，拒绝访问 $Target"
  }

  return $uri
}

Export-ModuleMember -Function Assert-LocalHttpTarget
