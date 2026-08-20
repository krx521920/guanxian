param(
    [int]$Workers = 1,
    [string[]]$Targets = @()
)

$ErrorActionPreference = "Stop"
$invalidTargets = $Targets | Where-Object { $_ -notmatch '^[A-Za-z0-9_.?*\-]+$' }
if ($invalidTargets) {
    throw "Targets 只能包含变异名称中使用的字母、数字、点、下划线、问号、星号和连字符。"
}
$targetArgs = if ($Targets.Count -gt 0) { " " + ($Targets -join " ") } else { "" }
$serviceRoot = Split-Path -Parent $PSScriptRoot
if ($IsWindows) {
    $wslRoot = (& wsl.exe wslpath -a $serviceRoot).Trim()
    if ($LASTEXITCODE -ne 0 -or -not $wslRoot) {
        throw "mutmut 3 不支持原生 Windows，且未能连接 WSL。请安装/启用 WSL 后重试。"
    }
    if ($wslRoot.Contains("'")) {
        throw "AI 服务路径不能包含单引号。"
    }
    $command = (
        "cd '$wslRoot' && python3 -m mutmut run --max-children " +
        $Workers + $targetArgs + " && python3 -m mutmut results"
    )
    & wsl.exe bash -lc $command
    if ($LASTEXITCODE -ne 0) {
        throw "WSL 中的 mutmut 执行失败，请确认已在 WSL Python 中安装 requirements-dev.txt。"
    }
}
else {
    Push-Location $serviceRoot
    try {
        python -m mutmut run --max-children $Workers @Targets
        python -m mutmut results
    }
    finally {
        Pop-Location
    }
}
