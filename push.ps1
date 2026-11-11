#Requires -Version 5.1
<#
.SYNOPSIS
    通过 GitHub Contents API 推送本地 HEAD 到远程仓库。

.DESCRIPTION
    为什么不用 git push：
      本机网络直连 github.com:443 会被重置，
      而配置的 gh-proxy.com 镜像只允许读取、拒绝写入。
      GitHub Contents API 走 HTTPS 可以正常写入，
      因此本脚本把本地 HEAD 的文件树逐文件镜像到远程分支。

.PARAMETER Message
    提交信息，默认 "chore: sync"。

.PARAMETER Repo
    仓库全名，默认 chenhuitony/mc-launcher。

.PARAMETER Branch
    目标分支，默认 main。

.EXAMPLE
    .\push.ps1
    .\push.ps1 -Message "feat: 新增功能"
#>
[CmdletBinding()]
param(
    [string]$Message = "chore: sync",
    [string]$Repo = "chenhuitony/mc-launcher",
    [string]$Branch = "main"
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

$files = @(git ls-tree -r HEAD --name-only)
if ($files.Count -eq 0) {
    Write-Host "[ERROR] 本地仓库没有任何文件" -ForegroundColor Red
    exit 1
}
Write-Host "[INFO] HEAD 包含 $($files.Count) 个文件"

$updated = 0
$failed = 0

foreach ($f in $files) {
    $path = $f -replace '\\', '/'
    $encoded = ([Uri]::EscapeDataString($path)).Replace('%2F', '/')
    $uri = "https://api.github.com/repos/$Repo/contents/$encoded"

    # 取远程现有文件的 sha（若不存在则为新文件）
    $remoteSha = $null
    try {
        $g = Invoke-WebRequest -Uri $uri -Headers $headers -TimeoutSec 30 -ErrorAction Stop
        $remoteSha = ($g.Content | ConvertFrom-Json).sha
    } catch {
        $remoteSha = $null
    }

    $localPath = Join-Path (Get-Location) $f
    if (-not (Test-Path -LiteralPath $localPath)) {
        Write-Host "  SKIP $path（本地不存在）" -ForegroundColor Yellow
        continue
    }
    $b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($localPath))

    if ($remoteSha) {
        $body = @{ message = $Message; content = $b64; sha = $remoteSha; branch = $Branch } |
                ConvertTo-Json -Compress
    } else {
        $body = @{ message = $Message; content = $b64; branch = $Branch } |
                ConvertTo-Json -Compress
    }

    try {
        Invoke-WebRequest -Uri $uri -Method Put -Headers $headers -Body $body `
            -ContentType 'application/json' -TimeoutSec 90 -ErrorAction Stop | Out-Null
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
    Write-Host "[DONE] 全部成功，共 $updated 个文件" -ForegroundColor Green
    Write-Host "       https://github.com/$Repo"
    exit 0
} else {
    Write-Host "[DONE] 成功 $updated 个，失败 $failed 个" -ForegroundColor Yellow
    exit 1
}
