param(
    [switch]$RemoveLegacyTools
)

$ErrorActionPreference = 'Stop'

$bandyApiUrl = $env:BANDY_API_URL
if ([string]::IsNullOrWhiteSpace($bandyApiUrl)) {
    $bandyApiUrl = 'http://localhost:8090'
}
$assistantRhApiUrl = $env:ASSISTANT_RH_API_URL
if ([string]::IsNullOrWhiteSpace($assistantRhApiUrl)) {
    $assistantRhApiUrl = 'http://localhost:8080'
}
$registrationKey = $env:BANDY_REGISTRATION_KEY
if ([string]::IsNullOrWhiteSpace($registrationKey)) {
    $environmentFile = Join-Path (Split-Path -Parent $PSScriptRoot) '.env'
    if (Test-Path -LiteralPath $environmentFile) {
        $keyLine = Get-Content -LiteralPath $environmentFile |
            Where-Object { $_ -match '^\s*BANDY_REGISTRATION_KEY\s*=' } |
            Select-Object -First 1
        if ($keyLine) {
            $registrationKey = ($keyLine -split '=', 2)[1].Trim()
            if ($registrationKey.Length -ge 2 -and
                (($registrationKey.StartsWith('"') -and $registrationKey.EndsWith('"')) -or
                 ($registrationKey.StartsWith("'") -and $registrationKey.EndsWith("'")))) {
                $registrationKey = $registrationKey.Substring(1, $registrationKey.Length - 2)
            }
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

$agentProfileTool = @{
    name = 'get_agent_profile'
    description = 'Récupère le profil professionnel concis d’un agent identifié par son nom ou matricule.'
    whenToUse = 'Pour une question sur l’âge, les diplômes, le parcours, le poste actuel ou les informations professionnelles d’un agent. Utiliser le profil déjà présent dans le contexte de la conversation sans refaire cet appel. Passer le nom ou le matricule fourni par l’utilisateur, sans inventer de correspondance.'
    method = 'GET'
    url = "$($assistantRhApiUrl.TrimEnd('/'))/api/profil/agent/assistant"
    enabled = $true
    parameters = @(
        @{
            name = 'nom_agent'
            type = 'string'
            description = 'Nom complet ou matricule exact de l’agent.'
            required = $true
            location = 'query'
        }
    )
}

$headers = @{
    'X-Bandy-Registration-Key' = $registrationKey
}
$toolsUri = "$($bandyApiUrl.TrimEnd('/'))/api/tools"
$existingTools = Invoke-RestMethod `
    -Uri $toolsUri `
    -Method Get `
    -Headers $headers `
    -TimeoutSec 15

if ($agentProfileTool.whenToUse.Length -gt 2000) {
    throw "Tool instructions for $($agentProfileTool.name) exceed the 2000-character API limit."
}
$existingTool = @($existingTools |
    Where-Object { $_.name -eq $agentProfileTool.name } |
    Select-Object -First 1)
$jsonBody = $agentProfileTool | ConvertTo-Json -Depth 8
$bodyBytes = [Text.Encoding]::UTF8.GetBytes($jsonBody)
if ($existingTool.Count -gt 0) {
    $method = 'Put'
    $uri = "$toolsUri/$($agentProfileTool.name)"
} else {
    $method = 'Post'
    $uri = $toolsUri
}
try {
    $result = Invoke-RestMethod `
        -Uri $uri `
        -Method $method `
        -Headers $headers `
        -ContentType 'application/json; charset=utf-8' `
        -Body $bodyBytes `
        -TimeoutSec 15
} catch {
    $statusCode = if ($_.Exception.Response) {
        [int]$_.Exception.Response.StatusCode
    } else {
        'unavailable'
    }
    $detail = if ($_.ErrorDetails.Message) {
        $_.ErrorDetails.Message
    } else {
        $_.Exception.Message
    }
    throw "Registration of $($agentProfileTool.name) failed (HTTP $statusCode): $detail"
}

$registeredParameters = @($result.parameters | ForEach-Object { $_.name })
if ($registeredParameters -notcontains 'nom_agent') {
    throw "Bandy did not retain the expected parameter for $($result.name): nom_agent."
}
Write-Output "Registered tool: $($result.name) ($method); parameters: $($registeredParameters -join ', ')"

if ($RemoveLegacyTools) {
    foreach ($legacyTool in @($existingTools | Where-Object {
        $_.name -ne $agentProfileTool.name
    })) {
        try {
            Invoke-RestMethod `
                -Uri "$toolsUri/$($legacyTool.name)" `
                -Method Delete `
                -Headers $headers `
                -TimeoutSec 15
        } catch {
            $statusCode = if ($_.Exception.Response) {
                [int]$_.Exception.Response.StatusCode
            } else {
                'unavailable'
            }
            throw "Removal of legacy tool $($legacyTool.name) failed (HTTP $statusCode): $($_.Exception.Message)"
        }
        Write-Output "Removed legacy tool: $($legacyTool.name)"
    }
} else {
    Write-Output "Legacy tools were left unchanged. Re-run with -RemoveLegacyTools to remove them."
}
