[CmdletBinding()]
param(
    [string] $JarPath = (Join-Path (Get-Location) "target\quarkus-app\quarkus-run.jar"),
    [int] $FirstHttpPort = 18280,
    [int] $SecondHttpPort = 18281,
    [int] $FirstGrpcPort = 19280,
    [int] $SecondGrpcPort = 19281,
    [int] $StartupTimeoutSeconds = 90,
    [int] $HomeLimit = 8
)

$ErrorActionPreference = "Stop"

function Invoke-DockerRabbit {
    param([string[]] $Arguments)

    & docker.exe exec rabbitmq rabbitmqctl @Arguments | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "RabbitMQ 临时 smoke 用户操作失败。"
    }
}

function Get-ReadyStatus {
    param([int] $Port)

    try {
        $response = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/q/health/ready" -UseBasicParsing -TimeoutSec 5
        return [int]$response.StatusCode
    } catch {
        if ($null -ne $_.Exception.Response) {
            return [int]$_.Exception.Response.StatusCode
        }
        return 0
    }
}

function Set-EnvironmentMap {
    param([hashtable] $Values)

    foreach ($entry in $Values.GetEnumerator()) {
        Set-Item -Path ("Env:" + $entry.Key) -Value ([string]$entry.Value)
    }
}

function Start-WindBlogNode {
    param(
        [string] $NodeId,
        [int] $HttpPort,
        [int] $GrpcPort,
        [string] $LogDirectory,
        [string] $StdoutPath,
        [string] $StderrPath
    )

    Set-EnvironmentMap @{
        QUARKUS_HTTP_PORT = $HttpPort
        QUARKUS_GRPC_SERVER_PORT = $GrpcPort
        WINDBLOG_NODE_ID = $NodeId
        QUARKUS_LOG_FILE_PATH = (Join-Path $LogDirectory "application.log")
    }
    New-Item -ItemType Directory -Force -Path $LogDirectory | Out-Null
    return Start-Process -FilePath "java" `
        -ArgumentList @("-jar", $JarPath) `
        -WorkingDirectory (Get-Location).Path `
        -WindowStyle Hidden `
        -RedirectStandardOutput $StdoutPath `
        -RedirectStandardError $StderrPath `
        -PassThru
}

function Get-HttpStatus {
    param([string] $Url)

    try {
        $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 10
        return [int]$response.StatusCode
    } catch {
        if ($null -ne $_.Exception.Response) {
            return [int]$_.Exception.Response.StatusCode
        }
        throw
    }
}

function Assert-PortFree {
    param([int] $Port)

    if ((Get-NetTCPConnection -LocalPort $Port -ErrorAction SilentlyContinue | Measure-Object).Count -gt 0) {
        throw "smoke 端口已被占用，拒绝把其他进程当作测试节点：$Port"
    }
}

if (-not (Test-Path -LiteralPath $JarPath -PathType Leaf)) {
    throw "找不到打包 JVM 入口：$JarPath。请先运行 .\mvnw.cmd -q -DskipTests package。"
}
if ($HomeLimit -lt 2 -or $HomeLimit -gt 1000) {
    throw "HomeLimit 必须在 2 到 1000 之间。"
}
foreach ($port in @($FirstHttpPort, $SecondHttpPort, $FirstGrpcPort, $SecondGrpcPort)) {
    Assert-PortFree $port
}

$rootPath = (Get-Location).Path
$runId = [Guid]::NewGuid().ToString("N")
$smokeUser = "windblog_multi_smoke"
$smokePassword = "Multi" + $runId + "R9"
$applicationSecret = "MultiProd" + $runId + [Guid]::NewGuid().ToString("N")
$trustStorePassword = "Trust" + $runId + "R7"
$trustStorePath = Join-Path $env:TEMP ("windblog-multi-smoke-" + $runId + ".p12")
$temporaryRoot = Join-Path $env:TEMP ("windblog-multi-smoke-" + $runId)
$processes = @()

try {
    try {
        Invoke-DockerRabbit @("delete_user", $smokeUser)
    } catch {
    }
    Invoke-DockerRabbit @("add_user", $smokeUser, $smokePassword)
    Invoke-DockerRabbit @("set_permissions", "-p", "/", $smokeUser, ".*", ".*", ".*")

    & keytool -importkeystore -noprompt `
        -srckeystore (Join-Path $rootPath "certs\ca\truststore.p12") `
        -srcstoretype PKCS12 -srcstorepass "changeit" `
        -destkeystore $trustStorePath -deststoretype PKCS12 `
        -deststorepass $trustStorePassword | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "无法生成临时强密码 gRPC truststore。"
    }

    $serverCertificate = (Resolve-Path (Join-Path $rootPath "certs\ca\server.crt")).Path
    $serverKey = (Resolve-Path (Join-Path $rootPath "certs\ca\server.key")).Path
    $caCertificate = (Resolve-Path (Join-Path $rootPath "certs\ca\ca.crt")).Path
    Set-EnvironmentMap @{
        QUARKUS_PROFILE = "prod"
        DB_JDBC_URL = "jdbc:postgresql://127.0.0.1:5432/postgres"
        DB_USERNAME = "postgres"
        DB_PASSWORD = "postgres"
        REDIS_URL = "redis://:redis@127.0.0.1:6379/15"
        RABBITMQ_HOST = "127.0.0.1"
        RABBITMQ_PORT = "5672"
        RABBITMQ_USERNAME = $smokeUser
        RABBITMQ_PASSWORD = $smokePassword
        ADMIN_JWT_SECRET = $applicationSecret
        USER_JWT_SECRET = $applicationSecret
        SECURITY_EVENT_HASH_SECRET = $applicationSecret
        SECURITY_FAIL_ON_DEFAULT_SECRETS_IN_PROD = "true"
        COOKIE_SECURE = "true"
        WINDBLOG_SITE_PUBLIC_URL = "https://example.com"
        CORS_ORIGINS = "https://example.com"
        CORS_ALLOW_CREDENTIALS = "false"
        SECURITY_HEADERS_CSP_ENFORCE = "true"
        SECURITY_HEADERS_HSTS_ENABLED = "true"
        SECURITY_HEADERS_CSP_TRUSTED_TYPES_ENABLED = "true"
        SECURITY_HEADERS_CSP_IMG_SOURCES = "https://example.com"
        SECURITY_HEADERS_CSP_CONNECT_SOURCES = "https://example.com"
        SWAGGER_UI_ENABLED = "false"
        ELASTICSEARCH_HOSTS = "http://127.0.0.1:9200"
        ELASTICSEARCH_USERNAME = "elastic"
        ELASTICSEARCH_PASSWORD = "elastic123456"
        ELASTICSEARCH_SSL_TRUST_ALL = "false"
        ELASTICSEARCH_SSL_VERIFY = "full"
        GRPC_SERVER_CLIENT_AUTH = "required"
        GRPC_SERVER_CERTIFICATE = $serverCertificate
        GRPC_SERVER_KEY = $serverKey
        GRPC_SERVER_TRUST_STORE = $trustStorePath
        GRPC_SERVER_TRUST_STORE_PASSWORD = $trustStorePassword
        GRPC_CLIENT_CA_CERTIFICATE = $caCertificate
        GRPC_CLIENT_CERTIFICATE = $serverCertificate
        GRPC_CLIENT_KEY = $serverKey
        GRPC_CLIENT_ALLOW_PLAINTEXT_FALLBACK = "false"
        WIND_BLOG_MEDIA_VIRUS_SCAN_ENABLED = "true"
        WIND_BLOG_MEDIA_VIRUS_SCAN_REQUIRED = "true"
        WIND_BLOG_MEDIA_VIRUS_SCAN_HOST = "127.0.0.1"
        WIND_BLOG_MEDIA_VIRUS_SCAN_PORT = "3310"
        WINDBLOG_EDGE_MAX_ROUTED_BODY_BYTES = "10485760"
        WINDBLOG_PUBLIC_READ_HOME_LIMIT_PER_MINUTE = $HomeLimit
        WINDBLOG_NODE_ROLE = "primary"
    }
    & docker.exe exec -e REDISCLI_AUTH=redis redis redis-cli -n 15 FLUSHDB | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "无法清理本地多节点 smoke 使用的 Redis DB 15。"
    }

    New-Item -ItemType Directory -Force -Path $temporaryRoot | Out-Null
    $firstProcess = Start-WindBlogNode "multi-a" $FirstHttpPort $FirstGrpcPort `
        (Join-Path $temporaryRoot "node-a") (Join-Path $temporaryRoot "node-a.out.log") (Join-Path $temporaryRoot "node-a.err.log")
    $processes += $firstProcess
    Start-Sleep -Seconds 1
    $firstProcess.Refresh()
    if ($firstProcess.HasExited) {
        throw "节点 multi-a 启动后立即退出。"
    }
    $secondProcess = Start-WindBlogNode "multi-b" $SecondHttpPort $SecondGrpcPort `
        (Join-Path $temporaryRoot "node-b") (Join-Path $temporaryRoot "node-b.out.log") (Join-Path $temporaryRoot "node-b.err.log")
    $processes += $secondProcess
    Start-Sleep -Seconds 1
    $secondProcess.Refresh()
    if ($secondProcess.HasExited) {
        throw "节点 multi-b 启动后立即退出。"
    }

    foreach ($port in @($FirstHttpPort, $SecondHttpPort)) {
        $readyStatus = 0
        $deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
        while ((Get-Date) -lt $deadline) {
            Start-Sleep -Seconds 2
            $readyStatus = Get-ReadyStatus $port
            if ($readyStatus -eq 200) {
                break
            }
        }
        if ($readyStatus -ne 200) {
            throw "多节点 smoke readiness 未达到 200：端口 $port，实际 $readyStatus。"
        }
    }

    if ((Get-HttpStatus "http://127.0.0.1:$FirstHttpPort/") -ne 200 -or
            (Get-HttpStatus "http://127.0.0.1:$SecondHttpPort/") -ne 200) {
        throw "两个节点的公开首页未同时返回 200。"
    }

    $statusCounts = @{}
    for ($index = 0; $index -lt ($HomeLimit * 2 + 4); $index++) {
        $port = if (($index % 2) -eq 0) { $FirstHttpPort } else { $SecondHttpPort }
        $status = Get-HttpStatus "http://127.0.0.1:$port/"
        $statusCounts[([string]$status)] = 1 + [int]($statusCounts[[string]$status])
    }
    if (-not $statusCounts.ContainsKey("429")) {
        throw "两个节点未共享 Redis 公开阅读限流计数：$($statusCounts | ConvertTo-Json -Compress)"
    }

    Write-Output "WindBlog multi-node Redis rate-limit smoke verification passed."
    Write-Output ("HTTP status counts: " + ($statusCounts | ConvertTo-Json -Compress))
}
finally {
    foreach ($process in $processes) {
        $process.Refresh()
        if (-not $process.HasExited) {
            Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        }
    }
    try {
        Invoke-DockerRabbit @("delete_user", $smokeUser)
    } catch {
    }
    & docker.exe exec -e REDISCLI_AUTH=redis redis redis-cli -n 15 FLUSHDB | Out-Null
    if (Test-Path -LiteralPath $trustStorePath) {
        Remove-Item -LiteralPath $trustStorePath -Force -ErrorAction SilentlyContinue
    }
    if (Test-Path -LiteralPath $temporaryRoot) {
        Remove-Item -LiteralPath $temporaryRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
