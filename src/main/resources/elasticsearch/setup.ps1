# Elasticsearch 和 Kibana 初始化脚本 (PowerShell 版本)
# 用于创建 ILM 策略、索引模板和导入 Kibana 视图

param(
    [string]$ES_HOST = "localhost",
    [string]$ES_PORT = "9200",
    [string]$KIBANA_HOST = "localhost",
    [string]$KIBANA_PORT = "5601",
    [string]$ES_USERNAME = "elastic",
    [string]$ES_PASSWORD = "elastic123456"
)

# 根据是否提供证书决定是否使用 HTTPS
$USE_HTTPS = $env:ELASTICSEARCH_USE_HTTPS -eq "true"
$PROTOCOL = if ($USE_HTTPS) { "https" } else { "http" }

$ES_URL = "${PROTOCOL}://${ES_HOST}:${ES_PORT}"
$KIBANA_URL = "http://${KIBANA_HOST}:${KIBANA_PORT}"
$SCRIPT_DIR = Split-Path -Parent $MyInvocation.MyCommand.Path

# 认证头
$AUTH_HEADER = @{
    Authorization = "Basic " + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${ES_USERNAME}:${ES_PASSWORD}"))
}

Write-Host "========================================"
Write-Host "Elasticsearch 和 Kibana 初始化脚本"
Write-Host "目标：${ES_URL}"
Write-Host "用户：${ES_USERNAME}"
Write-Host "HTTPS: $USE_HTTPS"
Write-Host "========================================"
Write-Host ""

# 等待 Elasticsearch 启动
Write-Host "步骤 1: 等待 Elasticsearch 启动..."
$maxAttempts = 30
$attempt = 0

while ($attempt -lt $maxAttempts) {
    try {
        $params = @{
            Uri = "${ES_URL}/_cluster/health"
            TimeoutSec = 5
            UseBasicParsing = $true
            Headers = $AUTH_HEADER
        }
        if ($USE_HTTPS) {
            $params.SkipCertificateCheck = $true
        }
        $response = Invoke-WebRequest @params
        Write-Host "✓ Elasticsearch 已就绪"
        break
    } catch {
        $attempt++
        Write-Host "  等待中... (尝试 $attempt/$maxAttempts)"
        Start-Sleep -Seconds 5
    }
}

if ($attempt -eq $maxAttempts) {
    Write-Host "✗ Elasticsearch 启动超时"
    exit 1
}

# 创建 ILM 策略
Write-Host ""
Write-Host "步骤 2: 创建 ILM 策略..."
$ILM_POLICY_FILE = Join-Path $SCRIPT_DIR "ilm-policy.json"

if (-not (Test-Path $ILM_POLICY_FILE)) {
    Write-Host "✗ 找不到 ILM 策略文件：$ILM_POLICY_FILE"
    exit 1
}

try {
    $policyContent = Get-Content $ILM_POLICY_FILE -Raw
    $params = @{
        Uri = "${ES_URL}/_ilm/policy/windblog-logs-policy"
        Method = PUT
        Body = $policyContent
        ContentType = "application/json; charset=utf-8"
        UseBasicParsing = $true
        Headers = $AUTH_HEADER
    }
    if ($USE_HTTPS) {
        $params.SkipCertificateCheck = $true
    }
    $response = Invoke-WebRequest @params
    
    Write-Host "✓ ILM 策略创建成功"
    Write-Host $response.Content | ConvertFrom-Json | ConvertTo-Json -Depth 10
} catch {
    Write-Host "✗ ILM 策略创建失败"
    Write-Host $_.Exception.Message
    exit 1
}

# 删除可能冲突的遗留模板
Write-Host ""
Write-Host "步骤 3: 清理遗留模板..."
$legacyTemplates = @("windblog-logs", "windblog-posts")
foreach ($templateName in $legacyTemplates)
{
    try
    {
        Write-Host "  检查遗留模板: $templateName"
        $params = @{
            Uri = "${ES_URL}/_index_template/${templateName}"
            Method = GET
            UseBasicParsing = $true
            Headers = $AUTH_HEADER
        }
        if ($USE_HTTPS)
        {
            $params.SkipCertificateCheck = $true
        }
        $response = Invoke-WebRequest @params -ErrorAction SilentlyContinue

        if ($response.StatusCode -eq 200)
        {
            Write-Host "  发现遗留模板 $templateName，正在删除..."
            $deleteParams = @{
                Uri = "${ES_URL}/_index_template/${templateName}"
                Method = DELETE
                UseBasicParsing = $true
                Headers = $AUTH_HEADER
            }
            if ($USE_HTTPS)
            {
                $deleteParams.SkipCertificateCheck = $true
            }
            Invoke-WebRequest @deleteParams | Out-Null
            Write-Host "  ✓ 遗留模板 $templateName 已删除"
        }
    }
    catch
    {
        Write-Host "  检查/删除遗留模板 $templateName 时出错: $( $_.Exception.Message )"
    }
}

# 创建索引模板
Write-Host ""
Write-Host "步骤 4: 创建索引模板..."
$TEMPLATE_FILE = Join-Path $SCRIPT_DIR "index-template.json"

if (-not (Test-Path $TEMPLATE_FILE)) {
    Write-Host "✗ 找不到索引模板文件：$TEMPLATE_FILE"
    exit 1
}

try {
    $templateContent = Get-Content $TEMPLATE_FILE -Raw
    $params = @{
        Uri = "${ES_URL}/_index_template/windblog-logs-template"
        Method = PUT
        Body = $templateContent
        ContentType = "application/json; charset=utf-8"
        UseBasicParsing = $true
        Headers = $AUTH_HEADER
    }
    if ($USE_HTTPS) {
        $params.SkipCertificateCheck = $true
    }
    $response = Invoke-WebRequest @params
    
    Write-Host "✓ 索引模板创建成功"
    Write-Host $response.Content | ConvertFrom-Json | ConvertTo-Json -Depth 10
} catch {
    Write-Host "✗ 索引模板创建失败"
    Write-Host $_.Exception.Message
    exit 1
}

# 创建初始索引
Write-Host ""
Write-Host "步骤 5: 创建初始索引..."

$initialIndexBody = @"
{
  "aliases": {
    "windblog-logs": {
      "is_write_index": true
    }
  }
}
"@

try {
    $params = @{
        Uri = "${ES_URL}/windblog-logs-000001"
        Method = PUT
        Body = $initialIndexBody
        ContentType = "application/json; charset=utf-8"
        UseBasicParsing = $true
        Headers = $AUTH_HEADER
    }
    if ($USE_HTTPS) {
        $params.SkipCertificateCheck = $true
    }
    $response = Invoke-WebRequest @params
    
    Write-Host "✓ 初始索引创建成功"
    Write-Host $response.Content | ConvertFrom-Json | ConvertTo-Json -Depth 10
} catch {
    Write-Host "⚠ 初始索引可能已存在"
    Write-Host $_.Exception.Message
}

# 创建文章搜索索引
Write-Host ""
Write-Host "步骤 6: 创建文章搜索索引..."

# 创建文章 ILM 策略
$POSTS_ILM_FILE = Join-Path $SCRIPT_DIR "ilm-policy-posts.json"

if (Test-Path $POSTS_ILM_FILE)
{
    try
    {
        $policyContent = Get-Content $POSTS_ILM_FILE -Raw
        $params = @{
            Uri = "${ES_URL}/_ilm/policy/windblog-posts-policy"
            Method = PUT
            Body = $policyContent
            ContentType = "application/json; charset=utf-8"
            UseBasicParsing = $true
            Headers = $AUTH_HEADER
        }
        if ($USE_HTTPS)
        {
            $params.SkipCertificateCheck = $true
        }
        $response = Invoke-WebRequest @params

        Write-Host "✓ 文章 ILM 策略创建成功"
    }
    catch
    {
        Write-Host "⚠ 文章 ILM 策略可能已存在"
    }
}
else
{
    Write-Host "⚠ 找不到文章 ILM 策略文件：$POSTS_ILM_FILE"
}

# 创建文章索引模板
$POSTS_TEMPLATE_FILE = Join-Path $SCRIPT_DIR "index-template-posts.json"

if (Test-Path $POSTS_TEMPLATE_FILE)
{
    try
    {
        $templateContent = Get-Content $POSTS_TEMPLATE_FILE -Raw
        $params = @{
            Uri = "${ES_URL}/_index_template/windblog-posts-template"
            Method = PUT
            Body = $templateContent
            ContentType = "application/json; charset=utf-8"
            UseBasicParsing = $true
            Headers = $AUTH_HEADER
        }
        if ($USE_HTTPS)
        {
            $params.SkipCertificateCheck = $true
        }
        $response = Invoke-WebRequest @params

        Write-Host "✓ 文章索引模板创建成功"
    }
    catch
    {
        Write-Host "⚠ 文章索引模板创建失败"
    }
}
else
{
    Write-Host "⚠ 找不到文章索引模板文件：$POSTS_TEMPLATE_FILE"
}

# 创建初始文章索引（静态索引）
try
{
    $params = @{
        Uri = "${ES_URL}/windblog-posts"
        Method = PUT
        ContentType = "application/json; charset=utf-8"
        UseBasicParsing = $true
        Headers = $AUTH_HEADER
    }
    if ($USE_HTTPS)
    {
        $params.SkipCertificateCheck = $true
    }
    $response = Invoke-WebRequest @params

    Write-Host "✓ 初始文章索引创建成功"
}
catch
{
    Write-Host "⚠ 初始文章索引可能已存在"
}

# 验证配置
Write-Host ""
Write-Host "步骤 7: 验证配置..."
Write-Host ""
Write-Host "ILM 策略 (日志):"
try {
    $params = @{
        Uri = "${ES_URL}/_ilm/policy/windblog-logs-policy"
        UseBasicParsing = $true
        Headers = $AUTH_HEADER
    }
    if ($USE_HTTPS) {
        $params.SkipCertificateCheck = $true
    }
    $response = Invoke-WebRequest @params
    Write-Host $response.Content | ConvertFrom-Json | ConvertTo-Json -Depth 10
} catch {
    Write-Host "无法获取日志 ILM 策略"
}

Write-Host ""
Write-Host "ILM 策略 (文章):"
try
{
    $params = @{
        Uri = "${ES_URL}/_ilm/policy/windblog-posts-policy"
        UseBasicParsing = $true
        Headers = $AUTH_HEADER
    }
    if ($USE_HTTPS)
    {
        $params.SkipCertificateCheck = $true
    }
    $response = Invoke-WebRequest @params
    Write-Host $response.Content | ConvertFrom-Json | ConvertTo-Json -Depth 10
}
catch
{
    Write-Host "无法获取文章 ILM 策略"
}

Write-Host ""
Write-Host "索引模板 (日志):"
try {
    $params = @{
        Uri = "${ES_URL}/_index_template/windblog-logs-template"
        UseBasicParsing = $true
        Headers = $AUTH_HEADER
    }
    if ($USE_HTTPS) {
        $params.SkipCertificateCheck = $true
    }
    $response = Invoke-WebRequest @params
    Write-Host $response.Content | ConvertFrom-Json | ConvertTo-Json -Depth 10
} catch {
    Write-Host "无法获取日志索引模板"
}

Write-Host ""
Write-Host "索引模板 (文章):"
try
{
    $params = @{
        Uri = "${ES_URL}/_index_template/windblog-posts-template"
        UseBasicParsing = $true
        Headers = $AUTH_HEADER
    }
    if ($USE_HTTPS)
    {
        $params.SkipCertificateCheck = $true
    }
    $response = Invoke-WebRequest @params
    Write-Host $response.Content | ConvertFrom-Json | ConvertTo-Json -Depth 10
}
catch
{
    Write-Host "无法获取文章索引模板"
}

# 导入 Kibana 视图和仪表板
Write-Host ""
Write-Host "步骤 8: 导入 Kibana 视图和仪表板..."
$KIBANA_SAVED_OBJECTS_FILE = Join-Path $SCRIPT_DIR "kibana-saved-objects.json"

if (Test-Path $KIBANA_SAVED_OBJECTS_FILE) {
    try {
        $savedObjectsContent = Get-Content $KIBANA_SAVED_OBJECTS_FILE -Raw
        $response = Invoke-WebRequest -Uri "${KIBANA_URL}/api/saved_objects/_import?overwrite=true" `
            -Method POST `
            -InFile $KIBANA_SAVED_OBJECTS_FILE `
            -ContentType "application/json" `
            -UseBasicParsing
        
        Write-Host "✓ Kibana 视图导入成功"
        Write-Host $response.Content
    } catch {
        Write-Host "⚠ Kibana 视图导入失败，可以稍后手动导入"
        Write-Host $_.Exception.Message
    }
} else {
    Write-Host "⚠ 找不到 Kibana saved objects 文件"
}

# 导入 Kibana 仪表板
Write-Host ""
Write-Host "步骤 9: 导入 Kibana 仪表板..."
$KIBANA_DASHBOARD_FILE = Join-Path $SCRIPT_DIR "kibana-dashboard.json"

if (Test-Path $KIBANA_DASHBOARD_FILE) {
    try {
        $response = Invoke-WebRequest -Uri "${KIBANA_URL}/api/saved_objects/_import?overwrite=true" `
            -Method POST `
            -InFile $KIBANA_DASHBOARD_FILE `
            -ContentType "application/json" `
            -UseBasicParsing
        
        Write-Host "✓ Kibana 仪表板导入成功"
        Write-Host $response.Content
    } catch {
        Write-Host "⚠ Kibana 仪表板导入失败，可以稍后手动导入"
        Write-Host $_.Exception.Message
    }
} else {
    Write-Host "⚠ 找不到 Kibana 仪表板文件"
}

Write-Host ""
Write-Host "========================================"
Write-Host "✓ Elasticsearch 和 Kibana 初始化完成"
Write-Host "========================================"
Write-Host ""
Write-Host "下一步:"
Write-Host "1. 启动应用，日志将自动推送到 Elasticsearch"
Write-Host "2. 访问 Kibana (http://localhost:5601) 查看日志和仪表板"
Write-Host "3. 预配置的视图和仪表板已自动导入"
Write-Host ""
