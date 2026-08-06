[CmdletBinding(SupportsShouldProcess)]
param(
    [string]$DbContainer = "postgresdb",
    [string]$DbUser = "postgres",
    [string]$DbName = "postgres",
    [switch]$Apply
)

$ErrorActionPreference = "Stop"
$fixtureUserPattern = "edge-sync-user-%"
$fixturePostPattern = "edge-post-%"

function Invoke-DatabaseSql {
    param([Parameter(Mandatory)][string]$Sql)

    $result = & docker exec $DbContainer psql -U $DbUser -d $DbName `
        -v ON_ERROR_STOP=1 -At -F "|" -c $Sql
    if ($LASTEXITCODE -ne 0) {
        throw "数据库查询失败，容器=$DbContainer，数据库=$DbName"
    }
    return $result
}

$previewSql = @"
select u.id, u.username, p.id, p.slug
from users u
left join posts p on p.user_id = u.id and p.slug like '$fixturePostPattern'
where u.username like '$fixtureUserPattern'
order by u.id, p.id;
"@

$preview = @(Invoke-DatabaseSql -Sql $previewSql)
if ($preview.Count -eq 0 -or ($preview.Count -eq 1 -and [string]::IsNullOrWhiteSpace($preview[0]))) {
    Write-Output "未找到边缘同步测试夹具。"
    exit 0
}

Write-Output "发现以下边缘同步测试夹具（默认只读）："
$preview | ForEach-Object { Write-Output $_ }

if (-not $Apply) {
    Write-Output "如确认清理，请重新运行并显式添加 -Apply；当前未删除任何数据。"
    exit 0
}

if (-not $PSCmdlet.ShouldProcess(
        "$DbContainer/$DbName",
        "删除 edge-sync-user-* 用户及其 edge-post-* 测试文章")) {
    exit 0
}

$cleanupSql = @"
begin;
create temporary table edge_test_users on commit drop as
select id
from users
where username like '$fixtureUserPattern';

-- posts 删除会按外键级联删除 revisions、tags/media 关联和 comments。
delete from posts
where user_id in (select id from edge_test_users)
  and slug like '$fixturePostPattern';

-- 只删除已经没有文章/版本引用的测试用户，避免误伤其他关联数据。
delete from users u
where u.id in (select id from edge_test_users)
  and not exists (select 1 from posts p where p.user_id = u.id)
  and not exists (select 1 from post_revisions r where r.created_by = u.id);

commit;
"@

Invoke-DatabaseSql -Sql $cleanupSql | ForEach-Object { Write-Output $_ }

$remainingSql = @"
select count(*)
from users
where username like '$fixtureUserPattern';
"@
$remaining = (Invoke-DatabaseSql -Sql $remainingSql | Select-Object -Last 1).Trim()
if ($remaining -ne "0") {
    throw "清理后仍有 $remaining 个测试用户，脚本已停止，不再扩大删除范围。"
}

Write-Output "边缘同步测试夹具清理完成。"