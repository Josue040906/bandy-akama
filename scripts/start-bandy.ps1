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

Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    & mvn spring-boot:run
} finally {
    Pop-Location
}
