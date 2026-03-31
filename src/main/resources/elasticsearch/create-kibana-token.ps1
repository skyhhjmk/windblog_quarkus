# Kibana 服务账户令牌创建脚本
# Elasticsearch 9.x 使用服务账户进行认证

$elasticPassword = "elastic123456"
$securePassword = ConvertTo-SecureString $elasticPassword -AsPlainText -Force
$credential = New-Object System.Management.Automation.PSCredential("elastic", $securePassword)

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "创建 Kibana 服务账户令牌" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# 检查服务账户是否存在
Write-Host "步骤 1: 检查服务账户..." -ForegroundColor Yellow
try {
    $response = Invoke-RestMethod -Uri "http://localhost:9200/_security/service/elastic/kibana" `
        -Credential $credential `
        -ErrorAction Stop
    Write-Host "  [OK] 服务账户已存在" -ForegroundColor Green
} catch {
    Write-Host "  [ERROR] 服务账户不存在" -ForegroundColor Red
    Write-Host $_.Exception.Message
    exit 1
}

# 创建服务账户令牌
Write-Host ""
Write-Host "步骤 2: 创建服务账户令牌..." -ForegroundColor Yellow
try {
    $body = @{
        refresh = "true"
    } | ConvertTo-Json
    
    $response = Invoke-RestMethod -Uri "http://localhost:9200/_security/service/elastic/kibana/credential/token/kibana-token" `
        -Method POST `
        -ContentType "application/json" `
        -Body $body `
        -Credential $credential `
        -ErrorAction Stop
    
    $token = $response.token.value
    Write-Host "  [OK] 令牌创建成功" -ForegroundColor Green
    Write-Host ""
    Write-Host "令牌值:" -ForegroundColor Cyan
    Write-Host $token -ForegroundColor White
    Write-Host ""
    
    # 保存到 .env 文件
    Write-Host "步骤 3: 更新 .env 文件..." -ForegroundColor Yellow
    $envFile = ".env"
    if (Test-Path $envFile) {
        $envContent = Get-Content $envFile -Raw
        if ($envContent -match "KIBANA_SERVICE_ACCOUNT_TOKEN=") {
            $envContent = $envContent -replace "KIBANA_SERVICE_ACCOUNT_TOKEN=.*", "KIBANA_SERVICE_ACCOUNT_TOKEN=$token"
        } else {
            $envContent += "`nKIBANA_SERVICE_ACCOUNT_TOKEN=$token"
        }
        $envContent | Set-Content $envFile -NoNewline
        Write-Host "  [OK] .env 文件已更新" -ForegroundColor Green
    } else {
        Write-Host "  [WARN] .env 文件不存在，请手动设置 KIBANA_SERVICE_ACCOUNT_TOKEN=$token" -ForegroundColor Yellow
    }
    
    Write-Host ""
    Write-Host "========================================" -ForegroundColor Green
    Write-Host "完成！" -ForegroundColor Green
    Write-Host "========================================" -ForegroundColor Green
    Write-Host ""
    Write-Host "下一步:" -ForegroundColor Cyan
    Write-Host "  1. 重启 Kibana: docker-compose restart kibana" -ForegroundColor White
    Write-Host "  2. 查看 Kibana 日志：docker logs kibana" -ForegroundColor White
    Write-Host ""
    
} catch {
    Write-Host "  [ERROR] 创建令牌失败" -ForegroundColor Red
    Write-Host $_.Exception.Message
    exit 1
}
