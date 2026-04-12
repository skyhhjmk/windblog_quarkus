# Docker Compose 快速启动脚本
# 用于启动 Filebeat + Elasticsearch + Kibana

param(
    [switch]$OnlyFilebeat, # 只启动 Filebeat（假设 ES 和 Kibana 已存在）
    [switch]$NoSecurity, # 禁用安全认证
    [switch]$Stop, # 停止所有服务
    [switch]$Logs           # 查看日志
)

# 检查 Docker 是否运行
Write-Host "检查 Docker 服务..." -ForegroundColor Cyan
try
{
    docker ps | Out-Null
}
catch
{
    Write-Host "错误：Docker 未运行或未安装" -ForegroundColor Red
    Write-Host "请先启动 Docker Desktop 或安装 Docker" -ForegroundColor Yellow
    exit 1
}

# 创建 .env 文件（如果不存在）
if (-not (Test-Path ".env"))
{
    Write-Host "创建 .env 配置文件..." -ForegroundColor Green
    Copy-Item ".env.example" ".env"
}

if ($Stop)
{
    Write-Host "停止所有服务..." -ForegroundColor Yellow
    docker-compose down
    exit 0
}

if ($Logs)
{
    Write-Host "查看 Filebeat 日志..." -ForegroundColor Cyan
    docker-compose logs -f filebeat
    exit 0
}

if ($OnlyFilebeat)
{
    Write-Host "只启动 Filebeat 服务..." -ForegroundColor Green
    docker-compose up -d filebeat
}
else
{
    Write-Host "启动所有服务（Filebeat + Elasticsearch + Kibana）..." -ForegroundColor Green
    docker-compose up -d
}

# 等待服务启动
Write-Host "`n等待服务启动..." -ForegroundColor Cyan
Start-Sleep -Seconds 10

# 检查服务状态
Write-Host "`n检查服务状态..." -ForegroundColor Cyan
docker-compose ps

# 显示访问信息
Write-Host "`n========================================" -ForegroundColor Green
Write-Host "服务已启动！" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green
Write-Host "Kibana: http://localhost:5601" -ForegroundColor Cyan
Write-Host "Elasticsearch: http://localhost:9200" -ForegroundColor Cyan
Write-Host ""
Write-Host "查看日志：docker-compose logs -f filebeat" -ForegroundColor Yellow
Write-Host "停止服务：docker-compose down" -ForegroundColor Yellow
Write-Host "========================================" -ForegroundColor Green
