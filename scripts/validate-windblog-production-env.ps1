[CmdletBinding()]
param(
    [string] $EnvFile = (Join-Path (Get-Location) ".env")
)

$ErrorActionPreference = "Stop"
$fileValues = @{}
$errors = [System.Collections.Generic.List[string]]::new()

if (Test-Path -LiteralPath $EnvFile -PathType Leaf) {
    foreach ($line in Get-Content -LiteralPath $EnvFile) {
        $trimmedLine = $line.Trim()
        if ($trimmedLine -eq "" -or $trimmedLine.StartsWith("#")) {
            continue
        }
        if ($trimmedLine -notmatch '^([^=]+)=(.*)$') {
            $errors.Add("环境文件存在无法解析的行")
            continue
        }
        $name = $Matches[1].Trim()
        $value = $Matches[2].Trim()
        if ((($value.StartsWith("'") -and $value.EndsWith("'")) -or
                ($value.StartsWith('"') -and $value.EndsWith('"')))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        $fileValues[$name] = $value
    }
}

function Get-EffectiveValue {
    param([string] $Name)
    $environmentItem = Get-Item -LiteralPath ("Env:" + $Name) -ErrorAction SilentlyContinue
    if ($null -ne $environmentItem) {
        return [string]$environmentItem.Value
    }
    if ($fileValues.ContainsKey($Name)) {
        return [string]$fileValues[$Name]
    }
    return ""
}

function Add-MissingOrWeakSecret {
    param([string] $Name, [int] $MinimumLength = 32)
    $value = Get-EffectiveValue $Name
    $normalized = $value.ToLowerInvariant()
    if (([string]::IsNullOrWhiteSpace($value)) -or
            ($value.Length -lt $MinimumLength) -or
            ($normalized.Contains("replace_with")) -or
            ($normalized.Contains("use-a-secret")) -or
            ($normalized.Contains("change-me")) -or
            ($normalized -in @("admin", "guest", "postgres", "password", "root"))) {
        $errors.Add("$Name 未配置强随机值")
    }
}

function Require-Value {
    param([string] $Name, [string] $Expected)
    $value = Get-EffectiveValue $Name
    if ($value -ne $Expected) {
        $errors.Add("$Name 必须为 $Expected")
    }
}

function Require-Port {
    param([string] $Name, [int] $Default)
    $value = Get-EffectiveValue $Name
    if ([string]::IsNullOrWhiteSpace($value)) {
        $value = [string]$Default
    }
    $port = 0
    if (-not [int]::TryParse($value, [ref]$port) -or $port -lt 1 -or $port -gt 65535) {
        $errors.Add("$Name 必须是 1 到 65535 的端口")
    }
}

function Require-IpAddress {
    param([string] $Name, [string] $Default)
    $value = Get-EffectiveValue $Name
    if ([string]::IsNullOrWhiteSpace($value)) {
        $value = $Default
    }
    $ipAddress = $null
    if (-not [System.Net.IPAddress]::TryParse($value.Trim(), [ref]$ipAddress)) {
        $errors.Add("$Name 必须是有效的 IPv4 或 IPv6 地址")
    }
}

function Require-ByteLimit {
    param([string] $Name, [long] $Default, [long] $Minimum, [long] $Maximum)
    $value = Get-EffectiveValue $Name
    if ([string]::IsNullOrWhiteSpace($value)) {
        $value = [string]$Default
    }
    $bytes = 0L
    if (-not [long]::TryParse($value, [ref]$bytes) -or $bytes -lt $Minimum -or $bytes -gt $Maximum) {
        $errors.Add("$Name 必须在 $Minimum 到 $Maximum 字节之间")
    }
}

function Test-PublicHttpsUrl {
    param([string] $Value)

    if ([string]::IsNullOrWhiteSpace($Value)) {
        return $false
    }
    try {
        $uri = [Uri]$Value.Trim()
    } catch {
        return $false
    }
    if (-not $uri.IsAbsoluteUri -or $uri.Scheme -ine "https" -or
            -not [string]::IsNullOrWhiteSpace($uri.UserInfo) -or
            [string]::IsNullOrWhiteSpace($uri.DnsSafeHost)) {
        return $false
    }

    $hostName = $uri.DnsSafeHost.ToLowerInvariant()
    if ($hostName -eq "localhost" -or $hostName.EndsWith(".localhost") -or
            $hostName.EndsWith(".local")) {
        return $false
    }

    $ipAddress = $null
    if (-not [System.Net.IPAddress]::TryParse($hostName, [ref]$ipAddress)) {
        return $true
    }
    if ([System.Net.IPAddress]::IsLoopback($ipAddress)) {
        return $false
    }
    $bytes = $ipAddress.GetAddressBytes()
    if ($ipAddress.AddressFamily -eq [System.Net.Sockets.AddressFamily]::InterNetwork) {
        $first = $bytes[0]
        $second = $bytes[1]
        if ($first -eq 0 -or $first -eq 10 -or $first -eq 127 -or
                ($first -eq 100 -and $second -ge 64 -and $second -le 127) -or
                ($first -eq 169 -and $second -eq 254) -or
                ($first -eq 192 -and $second -eq 0) -or
                ($first -eq 172 -and $second -ge 16 -and $second -le 31) -or
                ($first -eq 192 -and $second -eq 168) -or
                ($first -eq 198 -and ($second -eq 18 -or $second -eq 19 -or $second -eq 51)) -or
                ($first -eq 203 -and $second -eq 0 -and $bytes[2] -eq 113) -or
                $first -ge 224) {
            return $false
        }
        return $true
    }

    if ($bytes[0] -eq 0 -and $bytes[1] -eq 0 -and $bytes[2] -eq 0 -and $bytes[3] -eq 0 -and
            $bytes[4] -eq 0 -and $bytes[5] -eq 0 -and $bytes[6] -eq 0 -and $bytes[7] -eq 0) {
        return $false
    }
    if ($bytes[0] -eq 0xfc -or $bytes[0] -eq 0xfd -or
            ($bytes[0] -eq 0xfe -and ($bytes[1] -band 0xc0) -eq 0x80)) {
        return $false
    }
    $mappedIpv4 = $true
    for ($index = 0; $index -lt 10; $index++) {
        if ($bytes[$index] -ne 0) {
            $mappedIpv4 = $false
            break
        }
    }
    if ($mappedIpv4 -and $bytes[10] -eq 0xff -and $bytes[11] -eq 0xff) {
        $mappedFirst = $bytes[12]
        $mappedSecond = $bytes[13]
        if ($mappedFirst -eq 0 -or $mappedFirst -eq 10 -or $mappedFirst -eq 127 -or
                ($mappedFirst -eq 100 -and $mappedSecond -ge 64 -and $mappedSecond -le 127) -or
                ($mappedFirst -eq 169 -and $mappedSecond -eq 254) -or
                ($mappedFirst -eq 192 -and ($mappedSecond -eq 0 -or $mappedSecond -eq 168)) -or
                ($mappedFirst -eq 172 -and $mappedSecond -ge 16 -and $mappedSecond -le 31) -or
                ($mappedFirst -eq 198 -and ($mappedSecond -eq 18 -or $mappedSecond -eq 19 -or $mappedSecond -eq 51)) -or
                ($mappedFirst -eq 203 -and $mappedSecond -eq 0 -and $bytes[14] -eq 113) -or
                $mappedFirst -ge 224) {
            return $false
        }
    }
    if ($bytes[0] -eq 0xff) {
        return $false
    }
    if ($bytes[0] -eq 0x20 -and $bytes[1] -eq 0x01 -and $bytes[2] -eq 0x0d -and $bytes[3] -eq 0xb8) {
        return $false
    }
    return $true
}

Add-MissingOrWeakSecret "POSTGRES_PASSWORD"
Add-MissingOrWeakSecret "REDIS_PASSWORD"
Add-MissingOrWeakSecret "RABBITMQ_DEFAULT_PASS"
Add-MissingOrWeakSecret "ELASTIC_PASSWORD"
Add-MissingOrWeakSecret "KIBANA_SERVICE_ACCOUNT_TOKEN"
Add-MissingOrWeakSecret "KIBANA_ENCRYPTION_KEY"
Add-MissingOrWeakSecret "KIBANA_REPORTING_KEY"
Add-MissingOrWeakSecret "ADMIN_JWT_SECRET"
Add-MissingOrWeakSecret "USER_JWT_SECRET"
Add-MissingOrWeakSecret "SECURITY_EVENT_HASH_SECRET"
Add-MissingOrWeakSecret "ADMIN_INIT_PASSWORD"
Add-MissingOrWeakSecret "GRPC_SERVER_TRUST_STORE_PASSWORD"
Add-MissingOrWeakSecret "WINDBLOG_BACKUP_GPG_RECIPIENT" 16

$rabbitUser = Get-EffectiveValue "RABBITMQ_DEFAULT_USER"
if ([string]::IsNullOrWhiteSpace($rabbitUser) -or $rabbitUser -ieq "guest") {
    $errors.Add("RABBITMQ_DEFAULT_USER 不能使用 guest 或空值")
}

$publicUrl = Get-EffectiveValue "WINDBLOG_SITE_PUBLIC_URL"
if (-not (Test-PublicHttpsUrl $publicUrl)) {
    $errors.Add("WINDBLOG_SITE_PUBLIC_URL 必须是非本机 HTTPS 地址")
}

$corsOrigins = Get-EffectiveValue "CORS_ORIGINS"
if ([string]::IsNullOrWhiteSpace($corsOrigins) -or $corsOrigins.Contains("*")) {
    $errors.Add("CORS_ORIGINS 必须是明确来源，不能为空或使用通配符")
} else {
    foreach ($origin in $corsOrigins.Split(',')) {
        $trimmedOrigin = $origin.Trim()
        if (-not (Test-PublicHttpsUrl $trimmedOrigin)) {
            $errors.Add("CORS_ORIGINS 含有非 HTTPS 或本机来源")
            break
        }
    }
}

Require-Value "COOKIE_SECURE" "true"
Require-Value "SECURITY_FAIL_ON_DEFAULT_SECRETS_IN_PROD" "true"
Require-Value "SECURITY_HEADERS_CSP_ENFORCE" "true"
Require-Value "SECURITY_HEADERS_HSTS_ENABLED" "true"
Require-Value "SECURITY_HEADERS_CSP_TRUSTED_TYPES_ENABLED" "true"
Require-Value "CORS_ALLOW_CREDENTIALS" "false"
Require-Value "ELASTICSEARCH_SSL_TRUST_ALL" "false"
Require-Value "ELASTICSEARCH_SSL_VERIFY" "full"
Require-Value "ADMIN_INIT_ENABLED" "false"
Require-Value "SWAGGER_UI_ENABLED" "false"
Require-Value "GRPC_SERVER_CLIENT_AUTH" "required"
Require-Value "GRPC_CLIENT_ALLOW_PLAINTEXT_FALLBACK" "false"
Require-Value "WIND_BLOG_MEDIA_VIRUS_SCAN_ENABLED" "true"
Require-Value "WIND_BLOG_MEDIA_VIRUS_SCAN_REQUIRED" "true"
Require-Port "POSTGRES_HOST_PORT" 5432
Require-Port "REDIS_HOST_PORT" 6379
Require-Port "RABBITMQ_HOST_PORT" 5672
Require-Port "RABBITMQ_MANAGEMENT_HOST_PORT" 15672
Require-Port "ELASTICSEARCH_HOST_PORT" 9200
Require-Port "KIBANA_HOST_PORT" 5601
Require-Port "CLAMAV_HOST_PORT" 3310
Require-IpAddress "WINDBLOG_HOST_BIND_IP" "0.0.0.0"
Require-IpAddress "CLAMAV_HOST_BIND_IP" "127.0.0.1"
Require-ByteLimit "WINDBLOG_EDGE_MAX_ROUTED_BODY_BYTES" 10485760 1 67108864

if ($errors.Count -gt 0) {
    $message = ($errors | ForEach-Object { "- $_" }) -join [Environment]::NewLine
    throw "WindBlog 生产环境预检失败：$([Environment]::NewLine)$message"
}

Write-Output "WindBlog production environment validation passed."
