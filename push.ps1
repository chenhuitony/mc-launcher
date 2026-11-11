#Requires -Version 5.1
<#
.SYNOPSIS
    通过 GitHub Git Data API 把本地 HEAD 快照推送到远程。

.DESCRIPTION
    本机网络直连 github.com:443 会被重置，gh-proxy.com 镜像拒绝写入，
    因此只能走 GitHub API。

    做法：把本地 HEAD 的完整文件树一次性构造成一个 tree，
    生成一个 commit 并更新分支引用。全程只有 N+3 次请求
    （N = 不同文件数），比逐文件 Contents API 快得多，
    也避免了每个提交重复上传全部文件。

    只推送与远程内容不同的文件：先比对 blob SHA，
    已存在于远程的直接复用，不再上传。

.PARAMETER Message
    提交信息。

.PARAMETER Repo
    仓库全名，默认 chenhuitony/mc-launcher。

.PARAMETER Branch
    目标分支，默认 main。

.EXAMPLE
    .\push.ps1
    .\push.ps1 -Message "feat: 新功能"
#>
[CmdletBinding()]
param(
    [string]$Message = "",
    [string]$Repo = "chenhuitony/mc-launcher",
    [string]$Branch = "main"
)

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

function Get-Token {
    $cred = "protocol=https`nhost=github.com`n`n" | git credential fill 2>$null
    if (-not $cred) { return $null }
    $line = $cred | Select-String '^password=' | Select-Object -First 1
    if (-not $line) { return $null }
    return ($line.Line -replace '^password=', '')
}

function Invoke-Api {
    param([string]$Method = 'GET', [string]$Uri, $Body = $null)
    $p = @{
        Uri             = $Uri
        Method          = $Method
        Headers         = $script:headers
        TimeoutSec      = 90
        UseBasicParsing = $true
        ErrorAction     = 'Stop'
    }
    if ($null -ne $Body) {
        $p.Body        = ($Body | ConvertTo-Json -Compress -Depth 20)
        $p.ContentType = 'application/json'
    }
    $r = Invoke-WebRequest @p
    if ($r.Content) { return ($r.Content | ConvertFrom-Json) }
    return $null
}

$token = Get-Token
if (-not $token) {
    Write-Host "[ERROR] 找不到 GitHub token（检查凭据管理器）" -ForegroundColor Red
    exit 1
}
$script:headers = @{
    Authorization = "token $token"
    'User-Agent'  = 'mc-launcher-push'
    Accept        = 'application/vnd.github+json'
}

if (-not $Message) {
    $Message = (git log -1 --format=%s)
    if (-not $Message) { $Message = "chore: sync" }
}

# --- 1. 远程当前 HEAD（作为父提交）---
$remoteHead = $null
$remoteBlobs = @{}
try {
    $ref = Invoke-Api -Uri "https://api.github.com/repos/$Repo/git/ref/heads/$Branch"
    $remoteHead = $ref.object.sha
    Write-Host "[INFO] 远程 HEAD: $($remoteHead.Substring(0,8))"

    # 取远程已有 blob，用于跳过重复上传
    $rt = Invoke-Api -Uri "https://api.github.com/repos/$Repo/git/trees/$remoteHead`?recursive=1"
    foreach ($e in $rt.tree) {
        if ($e.type -eq 'blob') { $remoteBlobs[$e.sha] = $true }
    }
    Write-Host "[INFO] 远程已有 $($remoteBlobs.Count) 个对象"
} catch {
    Write-Host "[INFO] 远程分支尚不存在，将新建"
}

# --- 2. 本地 HEAD 文件清单 ---
$lsRaw = @(git ls-tree -r HEAD)
if ($lsRaw.Count -eq 0) {
    Write-Host "[ERROR] 本地没有文件" -ForegroundColor Red
    exit 1
}
Write-Host "[INFO] 本地 HEAD 有 $($lsRaw.Count) 个文件"

# --- 3. 上传 blob，构造 tree 条目 ---
$treeEntries = New-Object System.Collections.ArrayList
$uploaded = 0
$reused = 0
$seen = @{}

foreach ($line in $lsRaw) {
    if ($line -notmatch '^(\d+)\s+blob\s+([0-9a-f]+)\s+(.+)$') { continue }
    $mode = $Matches[1]
    $blob = $Matches[2]
    $path = $Matches[3]

    $githubSha = $null
    if ($seen.ContainsKey($blob)) {
        $githubSha = $seen[$blob]
    } elseif ($remoteBlobs.ContainsKey($blob)) {
        $githubSha = $blob          # 远程已有相同内容
        $seen[$blob] = $blob
        $reused++
    } else {
        # 从 git 取原始字节（必须用 cmd 重定向，PS 的 > 会写 UTF-16）
        $tmp = [IO.Path]::GetTempFileName()
        try {
            $null = cmd /c "git cat-file blob $blob > `"$tmp`" 2>nul"
            $bytes = [IO.File]::ReadAllBytes($tmp)
        } finally {
            Remove-Item -LiteralPath $tmp -ErrorAction SilentlyContinue
        }
        $b64 = [Convert]::ToBase64String($bytes)
        $created = Invoke-Api -Method POST -Uri "https://api.github.com/repos/$Repo/git/blobs" `
            -Body @{ content = $b64; encoding = 'base64' }
        $githubSha = $created.sha
        $seen[$blob] = $githubSha
        $uploaded++
        Write-Host "  上传 $path"
    }

    $null = $treeEntries.Add(@{
        path = $path
        mode = '100644'
        type = 'blob'
        sha  = $githubSha
    })
}

Write-Host "[INFO] 新上传 $uploaded 个，复用 $reused 个"

# --- 4. 建 tree ---
$tree = Invoke-Api -Method POST -Uri "https://api.github.com/repos/$Repo/git/trees" `
    -Body @{ tree = $treeEntries.ToArray() }
Write-Host "[INFO] tree: $($tree.sha.Substring(0,8))"

# --- 5. 建 commit ---
$commitBody = @{
    message = $Message
    tree    = $tree.sha
}
if ($remoteHead) { $commitBody.parents = @($remoteHead) }
$commit = Invoke-Api -Method POST -Uri "https://api.github.com/repos/$Repo/git/commits" -Body $commitBody
Write-Host "[INFO] commit: $($commit.sha.Substring(0,8))"

# --- 6. 更新分支引用 ---
if ($remoteHead) {
    $null = Invoke-Api -Method PATCH -Uri "https://api.github.com/repos/$Repo/git/refs/heads/$Branch" `
        -Body @{ sha = $commit.sha; force = $true }
    Write-Host "[INFO] 分支 $Branch 已更新"
} else {
    $null = Invoke-Api -Method POST -Uri "https://api.github.com/repos/$Repo/git/refs" `
        -Body @{ ref = "refs/heads/$Branch"; sha = $commit.sha }
    Write-Host "[INFO] 分支 $Branch 已创建"
}

Write-Host ""
Write-Host "[OK] 推送完成: $Message" -ForegroundColor Green
Write-Host "     https://github.com/$Repo"
exit 0
