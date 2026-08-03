[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [Uri] $PublicBaseUrl,

    [string] $ComposeFile = "docker-compose.yml",

    [switch] $SkipComposeCheck,

    [switch] $AllowHttpForLocalTest,

    [string] $ManagementProbeHost = "",

    [int[]] $ManagementProbePorts = @(5432, 6379, 5672, 15672, 9200, 5601),

    [int] $ManagementProbeTimeoutMilliseconds = 1500,

    [int] $TimeoutSeconds = 15
)

$ErrorActionPreference = "Stop"

function Assert-Condition {
    param(
        [bool] $Condition,
        [string] $Message
    )

    if (-not $Condition) {
        throw $Message
    }
}

function Get-HeaderValue {
    param(
        [object] $Response,
        [string] $Name
    )

    return $Response.Headers.Get($Name)
}

function Invoke-CloudRequest {
    param(
        [Uri] $Uri,
        [hashtable] $Headers = @{}
    )

    $request = [System.Net.HttpWebRequest]::Create($Uri)
    $request.Method = "GET"
    $request.Timeout = $TimeoutSeconds * 1000
    $request.AllowAutoRedirect = $false
    foreach ($header in $Headers.GetEnumerator()) {
        $request.Headers[$header.Key] = [string]$header.Value
    }

    try {
        $response = [System.Net.HttpWebResponse]$request.GetResponse()
        $statusCode = [int]$response.StatusCode
        $headers = $response.Headers
        $reader = New-Object System.IO.StreamReader($response.GetResponseStream())
        try {
            $body = $reader.ReadToEnd()
        } finally {
            $reader.Dispose()
            $response.Dispose()
        }
        return [pscustomobject]@{
            StatusCode = $statusCode
            Headers = $headers
            Body = $body
        }
    } catch [System.Net.WebException] {
        if ($null -eq $_.Exception.Response) {
            throw
        }
        $response = [System.Net.HttpWebResponse]$_.Exception.Response
        $statusCode = [int]$response.StatusCode
        $headers = $response.Headers
        $reader = New-Object System.IO.StreamReader($response.GetResponseStream())
        try {
            $body = $reader.ReadToEnd()
        } finally {
            $reader.Dispose()
            $response.Dispose()
        }
        return [pscustomobject]@{
            StatusCode = $statusCode
            Headers = $headers
            Body = $body
        }
    }
}

function Join-CloudUri {
    param(
        [Uri] $BaseUri,
        [string] $Path
    )

    return [Uri]::new($BaseUri, $Path)
}

function Test-TcpPortReachable {
    param(
        [string] $HostName,
        [int] $Port,
        [int] $TimeoutMilliseconds
    )

    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $connectTask = $client.ConnectAsync($HostName, $Port)
        if (-not $connectTask.Wait($TimeoutMilliseconds)) {
            return $false
        }
        return $client.Connected
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

Assert-Condition ($PublicBaseUrl.Scheme -eq "https" -or $AllowHttpForLocalTest) `
    "生产验收必须使用 HTTPS；仅本地临时检查可传入 -AllowHttpForLocalTest。"
Assert-Condition ($TimeoutSeconds -ge 1 -and $TimeoutSeconds -le 120) `
    "TimeoutSeconds 必须在 1 到 120 之间。"
Assert-Condition ($ManagementProbeTimeoutMilliseconds -ge 250 -and
        $ManagementProbeTimeoutMilliseconds -le 30000) `
    "ManagementProbeTimeoutMilliseconds 必须在 250 到 30000 之间。"

$baseUri = [Uri]::new($PublicBaseUrl.AbsoluteUri.TrimEnd("/") + "/")
$checks = [System.Collections.Generic.List[string]]::new()

$health = Invoke-CloudRequest (Join-CloudUri $baseUri "q/health/ready")
Assert-Condition ($health.StatusCode -eq 200) "就绪检查失败，HTTP $($health.StatusCode)。"
$checks.Add("health ready")

$homepage = Invoke-CloudRequest (Join-CloudUri $baseUri "")
Assert-Condition ($homepage.StatusCode -ge 200 -and $homepage.StatusCode -lt 400) `
    "公开首页不可用，HTTP $($homepage.StatusCode)。"
Assert-Condition (-not $homepage.Body.Contains("post_pw_")) `
    "公开首页响应中发现旧的明文文章密码 Cookie 名称。"
$setCookie = $homepage.Headers.Get("Set-Cookie")
Assert-Condition ($null -eq $setCookie -or -not $setCookie -match "post_pw_") `
    "公开首页 Set-Cookie 中发现旧的明文文章密码 Cookie。"
$checks.Add("public homepage")

$securityHeaders = @(
    @{ Name = "Content-Security-Policy"; Required = $true },
    @{ Name = "Strict-Transport-Security"; Required = ($PublicBaseUrl.Scheme -eq "https") },
    @{ Name = "X-Content-Type-Options"; Required = $true }
)
foreach ($securityHeader in $securityHeaders) {
    $value = Get-HeaderValue $homepage $securityHeader.Name
    if ($securityHeader.Required) {
        Assert-Condition (-not [string]::IsNullOrWhiteSpace($value)) `
            "缺少安全响应头 $($securityHeader.Name)。"
    }
}
$cspReportOnly = $homepage.Headers.Get("Content-Security-Policy-Report-Only")
Assert-Condition ([string]::IsNullOrWhiteSpace($cspReportOnly)) `
    "生产响应仍发送 Content-Security-Policy-Report-Only。"
$csp = Get-HeaderValue $homepage "Content-Security-Policy"
Assert-Condition ($csp -match "(?i)(^|;)\s*img-src\s+'self'") `
    "CSP 未明确限制 img-src 到同源和配置的媒体 origin。"
Assert-Condition ($csp -notmatch "(?i)(^|[ ;])https:\s*(;|$)") `
    "CSP 仍使用任意 HTTPS 图片或连接来源。"
Assert-Condition ($csp -notmatch "(?i)script-src[^;]*unsafe-inline") `
    "CSP script-src 仍允许 unsafe-inline。"
Assert-Condition ($csp -match "(?i)require-trusted-types-for\s+'script'") `
    "生产 CSP 未启用 Trusted Types DOM sink 防护。"
Assert-Condition ($csp -match "(?i)trusted-types\s+default") `
    "生产 CSP 未限制 Trusted Types policy 名称。"
$exposedHeaders = $homepage.Headers.Get("Access-Control-Expose-Headers")
Assert-Condition ($null -eq $exposedHeaders -or $exposedHeaders -notmatch "(?i)(^|,)\s*Set-Cookie\s*(,|$)") `
    "CORS 暴露了 Set-Cookie。"
$checks.Add("security headers")

$corsProbe = Invoke-CloudRequest (Join-CloudUri $baseUri "") @{ Origin = "https://windblog-cors-probe.invalid" }
$allowOrigin = $corsProbe.Headers.Get("Access-Control-Allow-Origin")
Assert-Condition ($allowOrigin -ne "https://windblog-cors-probe.invalid" -and $allowOrigin -ne "*") `
    "CORS 接受了未配置的 probe origin。"
$allowCredentials = $corsProbe.Headers.Get("Access-Control-Allow-Credentials")
Assert-Condition ($allowCredentials -ne "true") `
    "CORS 对未配置的 probe origin 返回了 credentials=true。"
$checks.Add("cors deny probe")

$docs = Invoke-CloudRequest (Join-CloudUri $baseUri "api/admin/docs")
Assert-Condition ($docs.StatusCode -eq 404) `
    "生产 Swagger UI 未关闭，HTTP $($docs.StatusCode)。"
$checks.Add("swagger disabled")

$adminProbe = Invoke-CloudRequest (Join-CloudUri $baseUri "api/admin/users")
Assert-Condition ($adminProbe.StatusCode -eq 401) `
    "未授权管理 API 未返回 401，实际 HTTP $($adminProbe.StatusCode)。"
$checks.Add("admin unauthorised")

$downloadProbe = Invoke-CloudRequest (Join-CloudUri $baseUri "api/media/download/not-a-real-ticket")
Assert-Condition ($downloadProbe.StatusCode -ge 400 -and $downloadProbe.StatusCode -lt 500) `
    "伪造媒体下载票据未被拒绝，实际 HTTP $($downloadProbe.StatusCode)。"
$checks.Add("download ticket denied")

if (-not $SkipComposeCheck) {
    Assert-Condition (Test-Path -LiteralPath $ComposeFile) "Compose 文件不存在：$ComposeFile"
    $composeJson = docker compose -f $ComposeFile config --format json | ConvertFrom-Json
    foreach ($serviceName in @("db", "redis", "rabbitmq", "elasticsearch", "kibana")) {
        $service = $composeJson.services.$serviceName
        if ($null -eq $service) {
            continue
        }
        $ports = @($service.ports)
        Assert-Condition ($ports.Count -gt 0) `
            "服务 $serviceName 未映射宿主机端口；请确认 *_HOST_PORT 配置。"
    }
    $checks.Add("compose exposed services")
}

if (-not [string]::IsNullOrWhiteSpace($ManagementProbeHost)) {
    foreach ($port in $ManagementProbePorts) {
        Assert-Condition ($port -ge 1 -and $port -le 65535) `
            "ManagementProbePorts 含有无效端口：$port"
        $reachable = Test-TcpPortReachable $ManagementProbeHost $port $ManagementProbeTimeoutMilliseconds
        Assert-Condition (-not $reachable) `
            "公网探测主机 $ManagementProbeHost 仍可连接管理端口 $port；请检查防火墙、安全组或端口转发。"
    }
    $checks.Add("public management ports blocked")
}

Write-Output ("WindBlog cloud verification passed: " + ($checks -join ", "))
