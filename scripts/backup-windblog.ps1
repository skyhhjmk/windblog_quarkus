[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $BackupRoot,

    [string] $MediaRoot = (Join-Path (Get-Location) "uploads"),
    [int] $RetentionDays = 30,
    [string] $GpgRecipient = $env:WINDBLOG_BACKUP_GPG_RECIPIENT,
    [string] $PgDumpContainer = ""
)

$ErrorActionPreference = "Stop"

function Get-FullPath {
    param([string] $Path)
    return [System.IO.Path]::GetFullPath($Path)
}

function Assert-NotDriveRoot {
    param([string] $Path, [string] $Name)
    $fullPath = Get-FullPath $Path
    $root = [System.IO.Path]::GetPathRoot($fullPath)
    if ($fullPath.TrimEnd('\') -eq $root.TrimEnd('\')) {
        throw "$Name 不能是磁盘根目录。"
    }
    return $fullPath.TrimEnd('\')
}

function Invoke-GpgEncrypt {
    param(
        [string] $Source,
        [string] $Destination,
        [string] $Recipient
    )
    $destinationDirectory = Split-Path -Parent $Destination
    New-Item -ItemType Directory -Force -Path $destinationDirectory | Out-Null
    & gpg.exe --batch --yes --trust-model always --recipient $Recipient --output $Destination --encrypt $Source
    if ($LASTEXITCODE -ne 0) {
        throw "GPG 加密失败：$Source"
    }
}

$backupPath = Assert-NotDriveRoot $BackupRoot "BackupRoot"
$mediaPath = Get-FullPath $MediaRoot
if (-not (Test-Path -LiteralPath $mediaPath -PathType Container)) {
    throw "媒体目录不存在：$mediaPath"
}
if ($RetentionDays -lt 1 -or $RetentionDays -gt 3650) {
    throw "RetentionDays 必须在 1 到 3650 之间。"
}
if ([string]::IsNullOrWhiteSpace($GpgRecipient)) {
    throw "请通过 WINDBLOG_BACKUP_GPG_RECIPIENT 注入 GPG 公钥指纹或邮箱；禁止生成未加密备份。"
}
if (-not (Get-Command gpg.exe -ErrorAction SilentlyContinue)) {
    throw "找不到 gpg.exe；请先安装 GnuPG 或配置备份执行节点。"
}
if (-not [string]::IsNullOrWhiteSpace($PgDumpContainer) -and
        -not (Get-Command docker.exe -ErrorAction SilentlyContinue)) {
    throw "使用 PgDumpContainer 时找不到 docker.exe。"
}
if ([string]::IsNullOrWhiteSpace($env:PGPASSWORD)) {
    throw "请通过环境变量 PGPASSWORD 注入数据库密码；不要把密码写入命令行。"
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$runPath = Join-Path $backupPath $timestamp
$databasePath = Join-Path $runPath "database"
$mediaBackupPath = Join-Path $runPath "media"
$stagingPath = Join-Path ([System.IO.Path]::GetTempPath()) ("windblog-backup-" + $timestamp)
$stagingMediaPath = Join-Path $stagingPath "media"
$stagingDump = Join-Path $stagingPath "windblog.dump"
$encryptedDatabaseDump = Join-Path $databasePath "windblog.dump.gpg"
$containerDumpPath = "/tmp/windblog-backup-$timestamp.dump"

try {
    New-Item -ItemType Directory -Force -Path $databasePath, $mediaBackupPath, $stagingMediaPath | Out-Null

    $pgHost = if ($env:PGHOST) { $env:PGHOST } else { "127.0.0.1" }
    $pgPort = if ($env:PGPORT) { $env:PGPORT } else { "5432" }
    $pgUser = if ($env:PGUSER) { $env:PGUSER } else { "windblog" }
    $pgDatabase = if ($env:PGDATABASE) { $env:PGDATABASE } else { "windblog" }

    if ([string]::IsNullOrWhiteSpace($PgDumpContainer)) {
        & pg_dump --format=custom --no-owner --file $stagingDump --host $pgHost --port $pgPort --username $pgUser --dbname $pgDatabase
        if ($LASTEXITCODE -ne 0) {
            throw "pg_dump 失败，退出码 $LASTEXITCODE。"
        }
    } else {
        & docker exec $PgDumpContainer pg_dump --format=custom --no-owner --file $containerDumpPath --username $pgUser --dbname $pgDatabase
        if ($LASTEXITCODE -ne 0) {
            throw "容器内 pg_dump 失败，退出码 $LASTEXITCODE。"
        }
        $containerSource = $PgDumpContainer + ":" + $containerDumpPath
        & docker cp $containerSource $stagingDump
        if ($LASTEXITCODE -ne 0) {
            throw "无法从 PostgreSQL 容器复制数据库归档。"
        }
    }
    Invoke-GpgEncrypt $stagingDump $encryptedDatabaseDump $GpgRecipient

    & robocopy $mediaPath $stagingMediaPath /E /Z /R:2 /W:5 /COPY:DAT /DCOPY:DAT /NFL /NDL /NP | Out-Null
    if ($LASTEXITCODE -ge 8) {
        throw "媒体增量复制失败，退出码 $LASTEXITCODE。"
    }

    $stagedMediaFiles = @(Get-ChildItem -LiteralPath $stagingMediaPath -Recurse -File)
    foreach ($stagedFile in $stagedMediaFiles) {
        $relativePath = $stagedFile.FullName.Substring($stagingMediaPath.Length).TrimStart('\')
        $encryptedPath = Join-Path $mediaBackupPath ($relativePath + ".gpg")
        Invoke-GpgEncrypt $stagedFile.FullName $encryptedPath $GpgRecipient
    }

    $databaseHash = (Get-FileHash -LiteralPath $encryptedDatabaseDump -Algorithm SHA256).Hash
    $encryptedMediaFiles = @(Get-ChildItem -LiteralPath $mediaBackupPath -Recurse -File)
    $mediaBytes = ($encryptedMediaFiles | Measure-Object -Property Length -Sum).Sum
    if ($null -eq $mediaBytes) {
        $mediaBytes = 0
    }
    $manifest = [ordered]@{
        createdAt = [DateTimeOffset]::Now.ToString("o")
        encryption = [ordered]@{
            format = "GPG public-key encryption"
            recipient = $GpgRecipient
            plaintextRetained = $false
        }
        database = [ordered]@{
            host = $pgHost
            port = $pgPort
            database = $pgDatabase
            dump = "database/windblog.dump.gpg"
            sha256 = $databaseHash
        }
        media = [ordered]@{
            source = $mediaPath
            encryptedFileCount = $encryptedMediaFiles.Count
            encryptedBytes = $mediaBytes
            suffix = ".gpg"
        }
        restoreRequired = $true
        walArchiving = "由 PostgreSQL 部署配置单独提供；本脚本不伪造 WAL 已归档。"
    }
    $manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $runPath "manifest.json") -Encoding UTF8

    $cutoff = (Get-Date).AddDays(-$RetentionDays)
    $backupRootPrefix = $backupPath + [System.IO.Path]::DirectorySeparatorChar
    Get-ChildItem -LiteralPath $backupPath -Directory |
        Where-Object { $_.LastWriteTime -lt $cutoff } |
        ForEach-Object {
            $candidate = Get-FullPath $_.FullName
            if (-not $candidate.StartsWith($backupRootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
                throw "拒绝删除备份目录外的路径：$candidate"
            }
            Remove-Item -LiteralPath $candidate -Recurse -Force
        }

    Write-Output "WindBlog encrypted backup completed: $runPath"
    Write-Output "Database encrypted SHA256: $databaseHash"
    Write-Output "Encrypted media files: $($encryptedMediaFiles.Count)"
}
finally {
    if (-not [string]::IsNullOrWhiteSpace($PgDumpContainer)) {
        & docker exec $PgDumpContainer rm -f $containerDumpPath 2>$null | Out-Null
    }
    if (Test-Path -LiteralPath $stagingPath) {
        Remove-Item -LiteralPath $stagingPath -Recurse -Force
    }
}
