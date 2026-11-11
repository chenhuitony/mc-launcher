#Requires -Version 5.1
<#
.SYNOPSIS
    通过 GitHub Contents API 把本地 HEAD 同步到远程仓库。

.DESCRIPTION
    为什么不用 git push：
      本机网络直连 github.com:443 会被重置，
      而配置的 gh-proxy.com 镜像只允许读取、拒绝写入。
      GitHub Contents API 走 HTTPS 可正常写入，
      因此本脚本把本地 HEAD 的文件树镜像到远程分支。

    只推送内容有变化的文件：先用 git blob 哈希与远程 sha 比较，
    相同则跳过（GitHub 对无变化的内容返回 422）。

.PARAMETER Message
    提交信息，默认 "chore: sync"。

.PARAMETER Repo
    仓库全名，默认 chenhuitony/mc-launcher。

.PARAMETER Branch
    目标分支，默认 main。

.PARAMETER Force
    忽略差异检测，强制推送全部文件。

.EXAMPLE
    .\push.ps1
    .\push.ps1 -Message "feat: 新增功能"
    .\push.ps1 -Force
#>
[CmdletBinding()]
param(
    [string]$Message = "chore: sync",
    [string]$Repo = "chenhuitony/mc-launcher",
    [string]$Branch = "main",
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

function Get-GitHubToken {
    $input = "protocol=https`nhost=github.com`n`n"
    $cred = $input | git credential fill 2>$null
    if (-not $cred) { return $null }
    $line = $cred | Select-String '^password=' | Select-Object -First 1
    if (-not $line) { return $null }
    return ($line.Line -replace '^password=', '')
}

$token = Get-GitHubToken
if (-not $token) {
    Write-Host "[ERROR] 凭据管理器中找不到 GitHub token" -ForegroundColor Red
    exit 1
}

$headers = @{
    Authorization = "token $token"
    'User-Agent'  = 'mc-launcher-push'
    Accept        = 'application/vnd.github+json'
}

# 取本地 HEAD 的文件清单及其 blob sha
$treeLines = @(git ls-tree -r HEAD)
if ($treeLines.Count -eq 0) {
    Write-Host "[ERROR] 本地仓库没有任何文件" -ForegroundColor Red
    exit 1
}

$local = @{}
foreach ($line in $treeLines) {
    # 格式: <mode> blob <sha>	<path>
    if ($line -match '^\d+\s+blob\s+([0-9a-f]+)\s+(.+)$') {
        $sha = $Matches[1]
        $path = $Matches[2]
        $local[$path] = $sha
    }
}

Write-Host "[INFO] HEAD 包含 $($local.Count) 个文件"
if ($Force) { Write-Host "[INFO] 强制模式：忽略差异检测" -ForegroundColor Yellow }

$updated = 0
$skipped = 0
$failed = 0

foreach ($path in @($local.Keys)) {
    $blobSha = $local[$path]

    # 用 UriBuilder 拼 URL，避免 PowerShell 5.1 对 String.Replace 链式调用的怪异行为
    $encoded = [Uri]::EscapeDataString($path).Replace('%2F', '/')
    $uri = "https://api.github.com/repos/$Repo/contents/$encoded"

    # 取远程文件 sha，用于差异判断
    $remoteSha = $null
    try {
        $g = Invoke-WebRequest -Uri $uri -Headers $headers -TimeoutSec 30 -UseBasicParsing -ErrorAction Stop
        $remoteSha = ($g.Content | ConvertFrom-Json).sha
    } catch {
        $remoteSha = $null
    }

    # 内容无变化则跳过（GitHub 对相同内容 PUT 会返回 422）
    if (-not $Force -and $remoteSha -and $remoteSha -eq $blobSha) {
        Write-Host "  SKIP $path" -ForegroundColor DarkGray
        $skipped++
        continue
    }

    # 关键：从 git 读取 blob 内容，而不是读工作区文件。
    # 本机 core.autocrlf 会把工作区转成 CRLF，而 git blob 存的是 LF；
    # 直接用工作区内容上传会与 blob 哈希不一致，导致每次都判定为有变化。
    # 注意必须用 cmd 的 > 重定向：PowerShell 的 > 会写成 UTF-16，破坏字节。
    $tmp = [IO.Path]::GetTempFileName()
    try {
        $null = cmd /c "git cat-file blob $blobSha > `"$tmp`" 2>nul"
        $bytes = [IO.File]::ReadAllBytes($tmp)
    } finally {
        Remove-Item -LiteralPath $tmp -ErrorAction SilentlyContinue
    }

    if ($bytes -eq $null -or $bytes.Length -eq 0) {
        Write-Host "  SKIP $path（blob 为空）" -ForegroundColor Yellow
        $skipped++
        continue
    }

    $b64 = [Convert]::ToBase64String($bytes)

    if ($remoteSha) {
        $payload = @{ message = $Message; content = $b64; sha = $remoteSha; branch = $Branch }
    } else {
        $payload = @{ message = $Message; content = $b64; branch = $Branch }
    }
    $body = $payload | ConvertTo-Json -Compress

    try {
        Invoke-WebRequest -Uri $uri -Method Put -Headers $headers -Body $body -ContentType 'application/json' -TimeoutSec 90 -UseBasicParsing -ErrorAction Stop | Out-Null
        Write-Host "  OK   $path" -ForegroundColor Green
        $updated++
    } catch {
        $code = $null
        if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
        Write-Host "  FAIL $path ($code)" -ForegroundColor Red
        $failed++
    }
}

Write-Host ""
if ($failed -eq 0) {
    Write-Host "[DONE] 更新 $updated 个，跳过 $skipped 个" -ForegroundColor Green
    Write-Host "       https://github.com/$Repo"
    exit 0
} else {
    Write-Host "[DONE] 更新 $updated 个，跳过 $skipped 个，失败 $failed 个" -ForegroundColor Yellow
    exit 1
}
