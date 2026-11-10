$ErrorActionPreference = 'Stop'

# Starts Bandy Akama for local work.
# - Secrets are asked once per session (hidden input) unless already set.
# - The working database is bandy_akama_v2; the legacy bandy_akama database is
#   never used (its migration history belongs to an old branch).
# - Optional overrides: BANDY_DB_URL, SERVER_PORT, RH_API_PORT (RH API check only).

function Set-SecretForCurrentSession([string]$name) {
    $secureValue = Read-Host -Prompt "Enter $name (input is hidden)" -AsSecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureValue)
    try {
        $value = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
        if ([string]::IsNullOrWhiteSpace($value)) {
            throw "$name cannot be blank."
        }
        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

function Test-PortListening([int]$port) {
    return [bool](Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)
}

# 1. Database: bandy_akama_v2 unless BANDY_DB_URL says otherwise, never the legacy one.
$databaseUrl = [Environment]::GetEnvironmentVariable('BANDY_DB_URL')
if ([string]::IsNullOrWhiteSpace($databaseUrl)) {
    $databaseUrl = 'jdbc:postgresql://localhost:5433/bandy_akama_v2'
}
if ($databaseUrl -match '/bandy_akama(\?.*)?$') {
    throw ("BANDY_DB_URL points to the legacy database bandy_akama, which must not be used. " +
        "Remove it (Remove-Item Env:BANDY_DB_URL) to use bandy_akama_v2.")
}
Write-Output "Database: $databaseUrl"

# 2. Port: Bandy listens on 8090 unless SERVER_PORT is set.
$bandyPort = [Environment]::GetEnvironmentVariable('SERVER_PORT')
if ([string]::IsNullOrWhiteSpace($bandyPort)) {
    $bandyPort = '8090'
}
if (Test-PortListening ([int]$bandyPort)) {
    throw "Port $bandyPort is already in use: Bandy may already be running. Stop it first."
}

# 3. RH API: Bandy starts without it, but its tools need it.
$rhPort = [Environment]::GetEnvironmentVariable('RH_API_PORT')
if ([string]::IsNullOrWhiteSpace($rhPort)) {
    $rhPort = '8080'
}
if (-not (Test-PortListening ([int]$rhPort))) {
    Write-Warning "The RH API does not answer on port $rhPort yet: start it, or Bandy's tools will fail."
}

# 4. Secrets.
foreach ($name in 'GEMINI_API_KEY', 'JWT_SECRET', 'BANDY_REGISTRATION_KEY') {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
        Set-SecretForCurrentSession $name
    }
}

$jwtSecret = [Environment]::GetEnvironmentVariable('JWT_SECRET')
if ([Text.Encoding]::UTF8.GetByteCount($jwtSecret) -lt 32) {
    throw 'JWT_SECRET must contain at least 32 UTF-8 bytes and match assistant-rh-api.'
}

[Environment]::SetEnvironmentVariable('BANDY_DB_URL', $databaseUrl, 'Process')
Write-Output "Starting Bandy on http://localhost:$bandyPort ..."

Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    & mvn spring-boot:run
} finally {
    Pop-Location
}
