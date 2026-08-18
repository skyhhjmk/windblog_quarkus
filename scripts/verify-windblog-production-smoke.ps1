[CmdletBinding()]
param(
    [string] $JarPath = (Join-Path (Get-Location) "target\quarkus-app\quarkus-run.jar"),
    [int] $HttpPort = 18080,
    [int] $GrpcPort = 19000,
    [string] $PublicBaseUrl = "http://127.0.0.1:18080",
    [int] $StartupTimeoutSeconds = 90
)

$ErrorActionPreference = "Stop"

function Invoke-DockerRabbit {
    param([string[]] $Arguments)

    & docker exec rabbitmq rabbitmqctl @Arguments | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "RabbitMQ 临时 smoke 用户操作失败。"
    }
}

function Get-ReadyStatus {
    param([int] $Port)

    try {
        $response = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/q/health/ready" `
            -UseBasicParsing -TimeoutSec 5
        return [int]$response.StatusCode
    } catch {
        if ($null -ne $_.Exception.Response) {
            return [int]$_.Exception.Response.StatusCode
        }
        return 0
    }
}

if (-not (Test-Path -LiteralPath $JarPath -PathType Leaf)) {
    throw "找不到打包 JVM 入口：$JarPath。请先运行 .\mvnw.cmd -q -DskipTests package。"
}
if ($HttpPort -lt 1 -or $HttpPort -gt 65535 -or $GrpcPort -lt 1 -or $GrpcPort -gt 65535) {
    throw "smoke 端口无效。"
}
if (-not (Get-Command keytool -ErrorAction SilentlyContinue)) {
    throw "找不到 keytool。"
}

$rootPath = (Get-Location).Path
$smokeUser = "windblog_prod_smoke"
$smokePassword = "Smoke" + [Guid]::NewGuid().ToString("N") + "R9"
$applicationSecret = "Prod" + [Guid]::NewGuid().ToString("N") + [Guid]::NewGuid().ToString("N")
$trustStorePassword = "Trust" + [Guid]::NewGuid().ToString("N") + "R7"
$trustStorePath = Join-Path $env:TEMP ("windblog-prod-smoke-" + [Guid]::NewGuid().ToString("N") + ".p12")
$stdoutPath = Join-Path $env:TEMP ("windblog-prod-smoke-" + [Guid]::NewGuid().ToString("N") + ".out.log")
$stderrPath = Join-Path $env:TEMP ("windblog-prod-smoke-" + [Guid]::NewGuid().ToString("N") + ".err.log")
$appProcess = $null

try {
    Invoke-DockerRabbit @("delete_user", $smokeUser)
} catch {
    # The user is expected not to exist on the first run.
}

try {
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

    $environment = @{
        QUARKUS_PROFILE = "prod"
        QUARKUS_HTTP_PORT = "$HttpPort"
        QUARKUS_GRPC_SERVER_PORT = "$GrpcPort"
        DB_JDBC_URL = "jdbc:postgresql://127.0.0.1:5432/postgres"
        DB_USERNAME = "postgres"
        DB_PASSWORD = "postgres"
        REDIS_URL = "redis://:redis@127.0.0.1:6379"
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
        WINDBLOG_NODE_ROLE = "primary"
    }
    foreach ($entry in $environment.GetEnumerator()) {
        Set-Item -Path ("Env:" + $entry.Key) -Value $entry.Value
    }

    $appProcess = Start-Process `
        -FilePath "java" `
        -ArgumentList @("-jar", $JarPath) `
        -WorkingDirectory $rootPath `
        -WindowStyle Hidden `
        -RedirectStandardOutput $stdoutPath `
        -RedirectStandardError $stderrPath `
        -PassThru

    $readyStatus = 0
    $deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        $readyStatus = Get-ReadyStatus $HttpPort
        if ($readyStatus -eq 200) {
            break
        }
        $appProcess.Refresh()
        if ($appProcess.HasExited) {
            break
        }
    }
    if ($readyStatus -ne 200) {
        $tail = if (Test-Path -LiteralPath $stderrPath) {
            (Get-Content -LiteralPath $stderrPath -Tail 30) -join [Environment]::NewLine
        } else {
            ""
        }
        throw "生产 smoke readiness 未达到 200（实际 $readyStatus）。$([Environment]::NewLine)$tail"
    }

    & (Join-Path $rootPath "scripts\verify-windblog-cloud.ps1") `
        -PublicBaseUrl $PublicBaseUrl -AllowHttpForLocalTest -SkipComposeCheck
    if ($LASTEXITCODE -ne 0) {
        throw "生产 smoke 云验收失败。"
    }

    Write-Output "WindBlog production smoke verification passed."
} finally {
    if ($null -ne $appProcess) {
        $appProcess.Refresh()
        if (-not $appProcess.HasExited) {
            Stop-Process -Id $appProcess.Id -Force -ErrorAction SilentlyContinue
        }
    }
    try {
        Invoke-DockerRabbit @("delete_user", $smokeUser)
    } catch {
    }
    foreach ($temporaryFile in @($trustStorePath, $stdoutPath, $stderrPath)) {
        if (Test-Path -LiteralPath $temporaryFile) {
            Remove-Item -LiteralPath $temporaryFile -Force -ErrorAction SilentlyContinue
        }
    }
}
