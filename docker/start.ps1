$ErrorActionPreference = 'Stop'

$backendDir = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $PSScriptRoot '.env'
$localConfig = Join-Path $backendDir 'config\local.properties'

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'Docker CLI not found. Install and start Docker Desktop first.'
}

docker info *> $null
if ($LASTEXITCODE -ne 0) {
    throw 'Docker daemon is not running. Start Docker Desktop and retry.'
}

if (-not (Test-Path -LiteralPath $envFile)) {
    function New-RandomSecret {
        $bytes = [byte[]]::new(24)
        [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
        return [Convert]::ToHexString($bytes).ToLowerInvariant()
    }

    $lines = @(
        "MYSQL_ROOT_PASSWORD=$(New-RandomSecret)",
        "MYSQL_APP_PASSWORD=$(New-RandomSecret)",
        "DEMO_A_PASSWORD=$(New-RandomSecret)",
        "DEMO_B_PASSWORD=$(New-RandomSecret)",
        "POSTGRES_PASSWORD=$(New-RandomSecret)",
        'FRONTEND_PORT=5174',
        'GATEWAY_PORT=8080',
        'SERVICE_PORT=8081',
        'ADMIN_PORT=8083',
        'NACOS_CONSOLE_PORT=8082',
        'NACOS_PORT=8848',
        'NACOS_GRPC_PORT=9848',
        'NACOS_RAFT_PORT=9849',
        'MYSQL_PORT=3306',
        'REDIS_PORT=6379',
        'POSTGRES_PORT=5432'
    )
    [IO.File]::WriteAllLines($envFile, $lines, [Text.UTF8Encoding]::new($false))
    Write-Host 'Generated docker/.env with random local passwords.'
}

if (-not (Test-Path -LiteralPath $localConfig)) {
    throw 'Missing config/local.properties. Copy .env.example to config/local.properties and configure the encryption key and optional model credentials.'
}

$envValues = @{}
Get-Content -LiteralPath $envFile | ForEach-Object {
    if ($_ -match '^\s*([^#=]+?)\s*=\s*(.*?)\s*$') {
        $envValues[$matches[1]] = $matches[2]
    }
}

$portKeys = @(
    'FRONTEND_PORT',
    'GATEWAY_PORT',
    'SERVICE_PORT',
    'ADMIN_PORT',
    'NACOS_CONSOLE_PORT',
    'NACOS_PORT',
    'NACOS_GRPC_PORT',
    'NACOS_RAFT_PORT',
    'MYSQL_PORT',
    'REDIS_PORT',
    'POSTGRES_PORT'
)
$ports = $portKeys | ForEach-Object { [int]$envValues[$_] }
$busy = foreach ($port in $ports) {
    if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) { $port }
}
if ($busy) {
    throw "Ports already in use: $($busy -join ', '). Stop the local non-Docker stack first, or change the host-side ports in docker/.env."
}

Push-Location $backendDir
try {
    docker compose --env-file docker/.env config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose validation failed.' }
    docker compose --env-file docker/.env up -d --build --wait
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose startup failed.' }
    Write-Host 'QueryForge is ready at http://127.0.0.1:5174/'
} finally {
    Pop-Location
}
