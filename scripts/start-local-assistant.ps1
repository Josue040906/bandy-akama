$ErrorActionPreference = 'Stop'

# Starts the RH API (8080) and Bandy (8090) for local work, then applies the tool catalogue.
# Settings come from the .env files of both projects; a variable already set in the
# session wins. A missing secret is asked once (hidden input).

$rhApiDirectory = 'D:\Projets\assistant-rh-api'
$bandyDirectory = 'D:\Projets\bandy-akama'

function Read-DotEnv([string]$path) {
    $values = @{}
    if (Test-Path -LiteralPath $path) {
        foreach ($line in Get-Content -LiteralPath $path -Encoding UTF8) {
            if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$') {
                $values[$Matches[1]] = $Matches[2].Trim().Trim('"').Trim("'")
            }
        }
    }
    return $values
}

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

$rhEnv = Read-DotEnv (Join-Path $rhApiDirectory '.env')
$bandyEnv = Read-DotEnv (Join-Path $bandyDirectory '.env')

# Both applications must sign and check tokens with the same secret.
if ($rhEnv['JWT_SECRET'] -and $bandyEnv['JWT_SECRET'] -and ($rhEnv['JWT_SECRET'] -ne $bandyEnv['JWT_SECRET'])) {
    throw 'JWT_SECRET differs between assistant-rh-api\.env and bandy-akama\.env: put the same value in both files.'
}

foreach ($name in 'GEMINI_API_KEY', 'JWT_SECRET', 'BANDY_REGISTRATION_KEY') {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
        $fromFile = $bandyEnv[$name]
        if ([string]::IsNullOrWhiteSpace($fromFile)) { $fromFile = $rhEnv[$name] }
        if ([string]::IsNullOrWhiteSpace($fromFile)) {
            Set-SecretForCurrentSession $name
        } else {
            [Environment]::SetEnvironmentVariable($name, $fromFile, 'Process')
        }
    }
}

$jwtSecret = [Environment]::GetEnvironmentVariable('JWT_SECRET')
if ([Text.Encoding]::UTF8.GetByteCount($jwtSecret) -lt 32) {
    throw 'JWT_SECRET must contain at least 32 UTF-8 bytes and match assistant-rh-api.'
}

# Local database of Bandy: bandy_akama_v2 (the legacy bandy_akama must not be used).
$databaseUrl = $env:BANDY_DB_URL
if ([string]::IsNullOrWhiteSpace($databaseUrl)) { $databaseUrl = $bandyEnv['BANDY_DB_URL'] }
if ([string]::IsNullOrWhiteSpace($databaseUrl)) { $databaseUrl = 'jdbc:postgresql://localhost:5433/bandy_akama_v2' }
if ($databaseUrl -match '/bandy_akama(\?.*)?$') {
    throw 'BANDY_DB_URL points to the legacy database bandy_akama: use bandy_akama_v2.'
}
if ($databaseUrl -notmatch 'localhost|127\.0\.0\.1') {
    throw ("BANDY_DB_URL points to an online database ($($databaseUrl -replace '\?.*$', '')). " +
        'For local work, set it to jdbc:postgresql://localhost:5433/bandy_akama_v2 in bandy-akama\.env.')
}
$env:BANDY_DB_URL = $databaseUrl
Write-Output "Bandy database: $databaseUrl"

foreach ($port in 8080, 8090) {
    $listener = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($listener) {
        throw "Port $port is already in use. Stop both existing applications, then rerun this script so they share the same JWT secret."
    }
}

function Start-And-Wait([int]$port, [string]$command) {
    Start-Process -FilePath $env:ComSpec -ArgumentList @('/k', $command)
    $deadline = (Get-Date).AddSeconds(120)
    do {
        Start-Sleep -Seconds 2
        $listener = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue |
            Select-Object -First 1
    } while (-not $listener -and (Get-Date) -lt $deadline)
    if (-not $listener) {
        throw "The application on port $port did not start. Check its terminal window for startup errors."
    }
}

Start-And-Wait 8080 "cd /d `"$rhApiDirectory`" && mvn spring-boot:run"
Start-And-Wait 8090 "cd /d `"$bandyDirectory`" && mvn spring-boot:run"

Set-Location $bandyDirectory
& '.\scripts\configure-assistant.ps1'
