$ErrorActionPreference = 'Stop'

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

foreach ($name in 'GEMINI_API_KEY', 'JWT_SECRET', 'BANDY_REGISTRATION_KEY') {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
        Set-SecretForCurrentSession $name
    }
}

$jwtSecret = [Environment]::GetEnvironmentVariable('JWT_SECRET')
if ([Text.Encoding]::UTF8.GetByteCount($jwtSecret) -lt 32) {
    throw 'JWT_SECRET must contain at least 32 UTF-8 bytes and match assistant-rh-api.'
}

if ([string]::IsNullOrWhiteSpace($env:BANDY_DB_URL)) {
    $env:BANDY_DB_URL = 'jdbc:postgresql://localhost:5433/bandy_akama'
}
if ([string]::IsNullOrWhiteSpace($env:BANDY_DB_USERNAME)) {
    $env:BANDY_DB_USERNAME = 'postgres'
}
if ([string]::IsNullOrWhiteSpace($env:BANDY_CORS_ALLOWED_ORIGINS)) {
    $env:BANDY_CORS_ALLOWED_ORIGINS = 'http://localhost:5173'
}

foreach ($port in 8080, 8090) {
    $listener = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($listener) {
        throw "Port $port is already in use. Stop both existing applications, then rerun this script so they share the same JWT secret."
    }
}

function Start-And-Wait([int]$port, [string]$command) {
    Start-Process -FilePath $env:ComSpec -ArgumentList @('/k', $command)
    $deadline = (Get-Date).AddSeconds(90)
    do {
        Start-Sleep -Seconds 2
        $listener = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue |
            Select-Object -First 1
    } while (-not $listener -and (Get-Date) -lt $deadline)
    if (-not $listener) {
        throw "The application on port $port did not start. Check its terminal window for startup errors."
    }
}

Start-And-Wait 8080 'cd /d "D:\Projets\assistant-rh-api" && mvn spring-boot:run'
Start-And-Wait 8090 'cd /d "D:\Projets\bandy-akama" && mvn spring-boot:run'

Set-Location 'D:\Projets\bandy-akama'
& '.\scripts\register-rh-tools.ps1'
