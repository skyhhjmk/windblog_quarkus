[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $BackupPath,

    [Parameter(Mandatory = $true)]
    [string] $RestoreRoot,

    [string] $PgRestoreContainer = ""
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

$backupPath = Assert-NotDriveRoot $BackupPath "BackupPath"
$restorePath = Assert-NotDriveRoot $RestoreRoot "RestoreRoot"
$backupPrefix = $backupPath + [System.IO.Path]::DirectorySeparatorChar
$restorePrefix = $restorePath + [System.IO.Path]::DirectorySeparatorChar
if (($restorePath.StartsWith($backupPrefix, [System.StringComparison]::OrdinalIgnoreCase)) -or
        ($backupPath.StartsWith($restorePrefix, [System.StringComparison]::OrdinalIgnoreCase)) -or
        ($restorePath.Equals($backupPath, [System.StringComparison]::OrdinalIgnoreCase))) {
    throw "RestoreRoot 必须与备份目录分离，避免恢复操作覆盖备份。"
}
if (-not (Test-Path -LiteralPath $backupPath -PathType Container)) {
    throw "备份目录不存在：$backupPath"
}
if (Test-Path -LiteralPath $restorePath) {
    throw "RestoreRoot 必须是尚不存在的隔离目录，避免清理失败时误删已有数据。"
}
if (-not (Get-Command gpg.exe -ErrorAction SilentlyContinue)) {
    throw "找不到 gpg.exe；请先安装 GnuPG。"
}
if ([string]::IsNullOrWhiteSpace($PgRestoreContainer) -and
        -not (Get-Command pg_restore.exe -ErrorAction SilentlyContinue)) {
    throw "找不到 pg_restore.exe；请安装 PostgreSQL 客户端工具。"
}
if (-not [string]::IsNullOrWhiteSpace($PgRestoreContainer) -and
        -not (Get-Command docker.exe -ErrorAction SilentlyContinue)) {
    throw "使用 PgRestoreContainer 时找不到 docker.exe。"
}

$manifestPath = Join-Path $backupPath "manifest.json"
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
    throw "备份缺少 manifest.json。"
}
$manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
$encryptedDatabasePath = Join-Path $backupPath ([string]$manifest.database.dump)
if (-not (Test-Path -LiteralPath $encryptedDatabasePath -PathType Leaf)) {
    throw "数据库加密归档不存在：$encryptedDatabasePath"
}
$actualDatabaseHash = (Get-FileHash -LiteralPath $encryptedDatabasePath -Algorithm SHA256).Hash
if (-not $actualDatabaseHash.Equals([string]$manifest.database.sha256, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "数据库加密归档 SHA-256 校验失败。"
}

New-Item -ItemType Directory -Force -Path $restorePath | Out-Null
$databaseRestorePath = Join-Path $restorePath "database\windblog.dump"
$mediaRestorePath = Join-Path $restorePath "media"
$containerRestorePath = "/tmp/windblog-restore-$([Guid]::NewGuid().ToString('N')).dump"
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $databaseRestorePath), $mediaRestorePath | Out-Null

try {
    & gpg.exe --batch --yes --output $databaseRestorePath --decrypt $encryptedDatabasePath
    if ($LASTEXITCODE -ne 0) {
        throw "数据库归档解密失败。"
    }
    if ([string]::IsNullOrWhiteSpace($PgRestoreContainer)) {
        & pg_restore.exe --list $databaseRestorePath | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "pg_restore 无法读取数据库归档。"
        }
    } else {
        & docker cp $databaseRestorePath ($PgRestoreContainer + ":" + $containerRestorePath)
        if ($LASTEXITCODE -ne 0) {
            throw "无法将数据库归档复制到 PostgreSQL 容器。"
        }
        & docker exec $PgRestoreContainer pg_restore --list $containerRestorePath | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "容器内 pg_restore 无法读取数据库归档。"
        }
    }

    $encryptedMediaFiles = @(Get-ChildItem -LiteralPath (Join-Path $backupPath "media") -Recurse -File -Filter "*.gpg")
    foreach ($encryptedMediaFile in $encryptedMediaFiles) {
        $relativePath = $encryptedMediaFile.FullName.Substring((Join-Path $backupPath "media").Length).TrimStart('\')
        if (-not $relativePath.EndsWith(".gpg", [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "媒体备份文件扩展名异常：$relativePath"
        }
        $relativePlainPath = $relativePath.Substring(0, $relativePath.Length - 4)
        $targetPath = Join-Path $mediaRestorePath $relativePlainPath
        $targetFullPath = Get-FullPath $targetPath
        if (-not $targetFullPath.StartsWith($restorePrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "拒绝将媒体恢复到 RestoreRoot 外：$relativePath"
        }
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $targetFullPath) | Out-Null
        & gpg.exe --batch --yes --output $targetFullPath --decrypt $encryptedMediaFile.FullName
        if ($LASTEXITCODE -ne 0) {
            throw "媒体归档解密失败：$relativePath"
        }
    }

    Write-Output "WindBlog backup validation completed: $restorePath"
    Write-Output "Database archive: valid"
    Write-Output "Media files restored: $($encryptedMediaFiles.Count)"
    Write-Output "This script does not alter any PostgreSQL database."
}
catch {
    if (Test-Path -LiteralPath $restorePath) {
        Remove-Item -LiteralPath $restorePath -Recurse -Force
    }
    throw
}
finally {
    if (-not [string]::IsNullOrWhiteSpace($PgRestoreContainer)) {
        & docker exec $PgRestoreContainer rm -f $containerRestorePath 2>$null | Out-Null
    }
}
