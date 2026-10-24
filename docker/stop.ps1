$ErrorActionPreference = 'Stop'

$backendDir = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $PSScriptRoot '.env'

if (-not (Test-Path -LiteralPath $envFile)) {
    throw 'docker/.env does not exist; nothing managed by this launcher can be stopped.'
}

Push-Location $backendDir
try {
    docker compose --env-file docker/.env down
} finally {
    Pop-Location
}
