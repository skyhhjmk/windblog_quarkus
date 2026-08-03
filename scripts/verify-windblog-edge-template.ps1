[CmdletBinding()]
param(
    [string] $TemplatePath = (Join-Path (Get-Location) "deploy\nginx\windblog-edge.conf.template"),
    [string] $NginxImage = "nginx:1.27-alpine"
)

$ErrorActionPreference = "Stop"

if (-not (Get-Command docker.exe -ErrorAction SilentlyContinue)) {
    throw "找不到 docker.exe。"
}
if (-not (Test-Path -LiteralPath $TemplatePath -PathType Leaf)) {
    throw "找不到 Nginx 模板：$TemplatePath"
}

$rootPath = (Get-Location).Path
$certificatePath = Join-Path $rootPath "certs\ca\server.crt"
$keyPath = Join-Path $rootPath "certs\ca\server.key"
if (-not (Test-Path -LiteralPath $certificatePath -PathType Leaf) -or
        -not (Test-Path -LiteralPath $keyPath -PathType Leaf)) {
    throw "缺少 Nginx 配置校验所需的测试证书。"
}

$values = @{
    '${WINDBLOG_APP_UPSTREAM}' = '127.0.0.1:8080'
    '${WINDBLOG_PUBLIC_HOST}' = 'blog.example.test'
    '${WINDBLOG_TLS_CERTIFICATE}' = '/etc/nginx/certs/server.crt'
    '${WINDBLOG_TLS_CERTIFICATE_KEY}' = '/etc/nginx/certs/server.key'
    '${WINDBLOG_TRUSTED_PROXY_CIDR}' = '10.0.0.0/8'
    '${WINDBLOG_MANAGEMENT_CIDR}' = '10.0.0.0/8'
}

$template = Get-Content -LiteralPath $TemplatePath -Raw
foreach ($entry in $values.GetEnumerator()) {
    $template = $template.Replace($entry.Key, $entry.Value)
}
if ($template -match '\$\{[A-Z0-9_]+\}') {
    throw "Nginx 模板仍有未替换变量。"
}

function Assert-TemplateContains {
    param([string] $Text, [string] $Pattern, [string] $Reason)

    if ($Text -notmatch $Pattern) {
        throw "Nginx 模板缺少安全约束：$Reason"
    }
}

Assert-TemplateContains $template 'client_max_body_size\s+10m;' '请求体上限'
Assert-TemplateContains $template 'set_real_ip_from\s+10\.0\.0\.0/8;' '可信代理网段'
Assert-TemplateContains $template '(?s)location\s+\^~\s+/q/.*?allow\s+10\.0\.0\.0/8;.*?deny\s+all;' '管理健康端点 allowlist'
Assert-TemplateContains $template '(?s)location\s+\^~\s+/api/admin/.*?allow\s+10\.0\.0\.0/8;.*?deny\s+all;' '管理 API allowlist'
Assert-TemplateContains $template '(?s)location\s+\^~\s+/api/media/download/.*?proxy_buffering\s+off;.*?proxy_no_cache\s+1;.*?Cache-Control\s+"no-store"' '受保护下载禁缓存'
Assert-TemplateContains $template 'limit_req_zone\s+\$binary_remote_addr' '公开请求限流'

$temporaryConfig = Join-Path ([System.IO.Path]::GetTempPath()) ("windblog-edge-" + [Guid]::NewGuid().ToString('N') + '.conf')
try {
    [System.IO.File]::WriteAllText($temporaryConfig, $template, [System.Text.UTF8Encoding]::new($false))
    $configMount = "type=bind,source=$temporaryConfig,destination=/etc/nginx/conf.d/default.conf,readonly"
    $certificateMount = "type=bind,source=$(Split-Path -Parent $certificatePath),destination=/etc/nginx/certs,readonly"
    & docker.exe run --rm --mount $configMount --mount $certificateMount $NginxImage nginx -t
    if ($LASTEXITCODE -ne 0) {
        throw "Nginx 边缘模板语法验证失败。"
    }
    Write-Output "WindBlog edge Nginx template verification passed."
}
finally {
    if (Test-Path -LiteralPath $temporaryConfig) {
        Remove-Item -LiteralPath $temporaryConfig -Force -ErrorAction SilentlyContinue
    }
}
