[CmdletBinding()]
param(
    [string] $DbContainer = "postgresdb",
    [string] $DbUser = "postgres",
    [string] $DbName = "postgres",
    [string] $MigrationPath = (Join-Path (Get-Location) "src\main\resources\db\migration\102-add-home-cloud-hot-path-indexes.sql"),
    [double] $MaxPublicReadMilliseconds = 1000,
    [double] $MaxOutboxClaimMilliseconds = 1000
)

$ErrorActionPreference = "Stop"

function Invoke-Postgres {
    param([string] $Sql)

    $output = @(& docker.exe exec $DbContainer psql -U $DbUser -d $DbName -v ON_ERROR_STOP=1 -Atqc $Sql 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "PostgreSQL 检查失败：$($output -join [Environment]::NewLine)"
    }
    return $output
}

function Get-ExecutionMilliseconds {
    param([string[]] $ExplainOutput)

    $text = $ExplainOutput -join [Environment]::NewLine
    $match = [Regex]::Match($text, 'Execution Time:\s+([0-9.]+)\s+ms')
    if (-not $match.Success) {
        throw "EXPLAIN 输出缺少 Execution Time。"
    }
    return [double]$match.Groups[1].Value
}

function Assert-Threshold {
    param([string] $Name, [double] $Actual, [double] $Maximum)

    if ($Actual -gt $Maximum) {
        throw "$Name 超过阈值：实际 $Actual ms，阈值 $Maximum ms。"
    }
}

if (-not (Get-Command docker.exe -ErrorAction SilentlyContinue)) {
    throw "找不到 docker.exe。"
}
if (-not (Test-Path -LiteralPath $MigrationPath -PathType Leaf)) {
    throw "找不到热点索引迁移：$MigrationPath"
}
if ($MaxPublicReadMilliseconds -le 0 -or $MaxOutboxClaimMilliseconds -le 0) {
    throw "耗时阈值必须为正数。"
}

Invoke-Postgres "ANALYZE posts, post_revisions, post_media, comments, media, media_download_event, outbox_events;" | Out-Null

$publicReadPlan = Invoke-Postgres @"
EXPLAIN (ANALYZE, BUFFERS)
SELECT p.id, p.published_at
FROM posts p
WHERE p.status = 1
  AND p.deleted_at IS NULL
  AND p.visibility = 0
  AND p.published_revision_id IS NOT NULL
ORDER BY p.published_at DESC NULLS LAST, p.id DESC
LIMIT 20;
"@
$publicReadMilliseconds = Get-ExecutionMilliseconds $publicReadPlan
Assert-Threshold "公开文章分页" $publicReadMilliseconds $MaxPublicReadMilliseconds

$outboxPlan = Invoke-Postgres @"
BEGIN;
EXPLAIN (ANALYZE, BUFFERS)
UPDATE outbox_events
SET status = 'IN_FLIGHT', locked_until = now(), lock_owner = 'hot-path-smoke',
    attempt_count = attempt_count + 1
WHERE id = (
    SELECT id
    FROM outbox_events
    WHERE status IN ('PENDING', 'IN_FLIGHT')
      AND available_at <= now()
      AND (locked_until IS NULL OR locked_until < now())
    ORDER BY id
    LIMIT 1
    FOR UPDATE SKIP LOCKED
)
RETURNING id;
ROLLBACK;
"@
$outboxMilliseconds = Get-ExecutionMilliseconds $outboxPlan
Assert-Threshold "Outbox 领取" $outboxMilliseconds $MaxOutboxClaimMilliseconds

$migrationText = Get-Content -LiteralPath $MigrationPath -Raw
$migrationStopwatch = [System.Diagnostics.Stopwatch]::StartNew()
$migrationOutput = @($migrationText | & docker.exe exec -i $DbContainer psql -U $DbUser -d $DbName -v ON_ERROR_STOP=1 2>&1)
$migrationStopwatch.Stop()
if ($LASTEXITCODE -ne 0) {
    throw "热点索引迁移幂等重放失败：$($migrationOutput -join [Environment]::NewLine)"
}

$expectedIndexes = @(
    "idx_posts_public_published_at",
    "idx_posts_visibility_status_deleted",
    "idx_post_revisions_post_revision",
    "idx_post_media_post_usage",
    "idx_post_media_media_usage",
    "idx_comments_post_status_created",
    "idx_media_storage_key_active",
    "idx_content_access_ticket_scope_expiry",
    "idx_media_download_event_post_time",
    "idx_media_download_event_subject_time",
    "idx_outbox_claim_ready"
)
$indexCount = [int](Invoke-Postgres ("SELECT count(*) FROM pg_indexes WHERE schemaname = 'public' AND indexname IN (" +
        (($expectedIndexes | ForEach-Object { "'$_'" }) -join ',') + ");")).Trim()
if ($indexCount -ne $expectedIndexes.Count) {
    throw "热点索引数量不完整：$indexCount/$($expectedIndexes.Count)。"
}

$postCount = [int](Invoke-Postgres "SELECT count(*) FROM posts;").Trim()
$publicPostCount = [int](Invoke-Postgres "SELECT count(*) FROM posts WHERE status = 1 AND deleted_at IS NULL AND visibility = 0 AND published_revision_id IS NOT NULL;").Trim()
$outboxPendingCount = [int](Invoke-Postgres "SELECT count(*) FROM outbox_events WHERE status IN ('PENDING', 'IN_FLIGHT');").Trim()

[pscustomobject]@{
    database = $DbName
    postCount = $postCount
    publicPostCount = $publicPostCount
    outboxPendingCount = $outboxPendingCount
    publicReadMilliseconds = $publicReadMilliseconds
    outboxClaimMilliseconds = $outboxMilliseconds
    migrationReplayMilliseconds = [math]::Round($migrationStopwatch.Elapsed.TotalMilliseconds, 2)
    hotPathIndexCount = $indexCount
    hotPathIndexExpected = $expectedIndexes.Count
} | ConvertTo-Json -Compress

Write-Output "WindBlog hot-path verification passed."
