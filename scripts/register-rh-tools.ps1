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
    throw 'BANDY_REGISTRATION_KEY must be set in this PowerShell session.'
}

$tool = @{
    name = 'searchEmployees'
    description = 'Recherche des agents RH par nom, prénom, matricule, poste, service ou direction.'
    whenToUse = 'Utiliser lorsqu’un utilisateur cherche un ou plusieurs agents. Ne pas utiliser pour modifier un dossier.'
    method = 'GET'
    url = "$($assistantRhApiUrl.TrimEnd('/'))/api/employes/recherche"
    enabled = $true
    parameters = @(
        @{
            name = 'query'
            type = 'string'
            description = 'Nom, prénom, matricule, poste, service ou direction à rechercher.'
            required = $true
            location = 'query'
        }
    )
}

$headers = @{
    'X-Bandy-Registration-Key' = $registrationKey
}
$result = Invoke-RestMethod `
    -Uri "$($bandyApiUrl.TrimEnd('/'))/api/tools" `
    -Method Post `
    -Headers $headers `
    -ContentType 'application/json; charset=utf-8' `
    -Body ($tool | ConvertTo-Json -Depth 8)

Write-Output "Registered tool: $($result.name)"
