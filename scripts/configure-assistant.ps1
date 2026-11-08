# Applies the assistant profile and the tool catalogue stored in scripts/config.
# The texts live in UTF-8 JSON files so that this script stays pure ASCII:
# Windows PowerShell 5.1 then reads it correctly whatever its encoding.
param(
    [switch]$RemoveLegacyTools
)

$ErrorActionPreference = 'Stop'

$bandyApiUrl = $env:BANDY_API_URL
if ([string]::IsNullOrWhiteSpace($bandyApiUrl)) {
    $bandyApiUrl = 'http://localhost:8090'
}
$rhApiUrl = $env:ASSISTANT_RH_API_URL
if ([string]::IsNullOrWhiteSpace($rhApiUrl)) {
    $rhApiUrl = 'http://localhost:8080'
}

$registrationKey = $env:BANDY_REGISTRATION_KEY
if ([string]::IsNullOrWhiteSpace($registrationKey)) {
    $environmentFile = Join-Path (Split-Path -Parent $PSScriptRoot) '.env'
    if (Test-Path -LiteralPath $environmentFile) {
        $keyLine = Get-Content -LiteralPath $environmentFile |
            Where-Object { $_ -match '^\s*BANDY_REGISTRATION_KEY\s*=' } |
            Select-Object -First 1
        if ($keyLine) {
            $registrationKey = ($keyLine -split '=', 2)[1].Trim().Trim('"').Trim("'")
        }
    }
}
if ([string]::IsNullOrWhiteSpace($registrationKey)) {
    $secureKey = Read-Host -Prompt 'Enter BANDY_REGISTRATION_KEY (input is hidden)' -AsSecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureKey)
    try {
        $registrationKey = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}
if ([string]::IsNullOrWhiteSpace($registrationKey)) {
    throw 'BANDY_REGISTRATION_KEY cannot be blank.'
}

function Read-Config([string]$fileName) {
    $path = Join-Path (Join-Path $PSScriptRoot 'config') $fileName
    # Assigned first: PowerShell 5.1 emits a JSON array as one object, the return unrolls it.
    $parsed = Get-Content -LiteralPath $path -Raw -Encoding UTF8 | ConvertFrom-Json
    return $parsed
}

# Bandy answers "application/json" without a charset, which Windows PowerShell 5.1
# would decode as ISO-8859-1: the raw bytes are decoded as UTF-8 here instead.
function Invoke-Bandy([string]$method, [string]$uri, $body = $null) {
    $request = @{ Uri = $uri; Method = $method; Headers = $headers; TimeoutSec = 15; UseBasicParsing = $true }
    if ($null -ne $body) {
        $request.ContentType = 'application/json; charset=utf-8'
        $request.Body = [Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 10))
    }
    try {
        $response = Invoke-WebRequest @request
    } catch {
        $status = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 'unavailable' }
        throw "$method $uri failed (HTTP $status). Check the Bandy logs for details."
    }
    $text = [Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray())
    if ([string]::IsNullOrWhiteSpace($text)) { return $null }
    $parsed = $text | ConvertFrom-Json
    return $parsed
}

$headers = @{ 'X-Bandy-Registration-Key' = $registrationKey }
$baseUri = $bandyApiUrl.TrimEnd('/')

# Profile: the paragraphs are joined with a blank line.
$profileConfig = Read-Config 'assistant-profile.json'
$profile = [ordered]@{
    name = $profileConfig.name
    role = $profileConfig.role
    instructions = ($profileConfig.instructions -join "`n`n")
}
$savedProfile = Invoke-Bandy 'Put' "$baseUri/api/assistant/profile" $profile
if ($savedProfile.instructions -ne $profile.instructions -or $savedProfile.role -ne $profile.role) {
    throw 'Bandy did not store the profile text unchanged (check the encoding).'
}
Write-Output "Profile applied: $($savedProfile.name)"

# Tools: {RH_API_URL} in the catalogue is replaced by the RH API base URL.
$tools = @(Read-Config 'tools.json')
$toolsUri = "$baseUri/api/tools"
$existingNames = @(Invoke-Bandy 'Get' $toolsUri |
    ForEach-Object { $_.name })
foreach ($tool in $tools) {
    $tool.url = $tool.url.Replace('{RH_API_URL}', $rhApiUrl.TrimEnd('/'))
    if ($existingNames -contains $tool.name) {
        $saved = Invoke-Bandy 'Put' "$toolsUri/$($tool.name)" $tool
    } else {
        $saved = Invoke-Bandy 'Post' $toolsUri $tool
    }
    $savedParameters = (@($saved.parameters | ForEach-Object { $_.name }) | Sort-Object) -join ','
    $expectedParameters = (@($tool.parameters | ForEach-Object { $_.name }) | Sort-Object) -join ','
    $savedContextFields = @($saved.userContextFields) -join ','
    $expectedContextFields = @($tool.userContextFields) -join ','
    if ($saved.description -ne $tool.description -or
        $saved.whenToUse -ne $tool.whenToUse -or
        $saved.url -ne $tool.url -or
        [bool]$saved.enabled -ne [bool]$tool.enabled -or
        [bool]$saved.sensitive -ne [bool]$tool.sensitive -or
        $saved.actionLevel -ne $tool.actionLevel -or
        $savedParameters -ne $expectedParameters -or
        $savedContextFields -ne $expectedContextFields) {
        throw "Bandy did not store tool $($tool.name) unchanged (check the encoding and the schema)."
    }
    Write-Output "Tool applied: $($tool.name) ($savedParameters)"
}

$managedNames = @($tools | ForEach-Object { $_.name })
foreach ($name in @($existingNames | Where-Object { $managedNames -notcontains $_ })) {
    if ($RemoveLegacyTools) {
        $null = Invoke-Bandy 'Delete' "$toolsUri/$name"
        Write-Output "Removed unmanaged tool: $name"
    } else {
        Write-Output "Unmanaged tool left unchanged (use -RemoveLegacyTools to remove it): $name"
    }
}
