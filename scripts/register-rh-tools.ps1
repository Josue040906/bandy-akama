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

$tool = @{
    name = 'searchEmployees'
    description = 'Recherche multicritère d’agents RH à partir de critères structurés.'
    whenToUse = 'Utiliser pour toute recherche d’agents par description naturelle, y compris nom approximatif, âge, sexe, date ou ancienneté de recrutement, poste, service, direction, catégorie, corps, type d’emploi, classe ou échelon. Convertir la demande en JSON dans criteriaJson selon ce format : {"filters":[{"field":"age","operator":"LT","value":30},{"field":"direction","operator":"CONTAINS","value":"Finances"}],"limit":20}. Tous les filtres sont combinés avec ET. Champs : matricule, nom, prenom, sexe, adresse, poste, service, direction, categorie, corps, type_emploi, classe, echelon, age, date_naissance, date_embauche. Le schéma actuel ne lie pas de table de grade distincte à l’agent : ne propose pas de filtre grade. Opérateurs texte : EQ, NE, CONTAINS, FUZZY (FUZZY seulement pour noms et libellés professionnels, au moins 4 caractères). Opérateurs numériques : EQ, NE, GT, GTE, LT, LTE, BETWEEN avec upperValue. Dates au format AAAA-MM-JJ : EQ, NE, BEFORE, AFTER, BETWEEN avec upperValue, WITHIN_LAST_YEARS ou OLDER_THAN_YEARS avec un nombre d’années. Pour une date exacte, comparer les dates ISO. « Avant 2020 » signifie BEFORE 2020-01-01. Convertir « moins de 30 ans » en age LT 30 ; « dans la trentaine » en age BETWEEN 30 et 39 ; femmes = sexe EQ F. « Récemment » ou « depuis longtemps » sans durée précise est ambigu : demander le nombre d’années avant l’appel. Si l’identité ou la demande est ambiguë, demander une précision. Ne jamais inventer de résultats.'
    method = 'POST'
    url = "$($assistantRhApiUrl.TrimEnd('/'))/api/recherche/agents/criteres"
    enabled = $true
    parameters = @(
        @{
            name = 'criteriaJson'
            type = 'string'
            description = 'Objet JSON sérialisé sous forme de chaîne avec filters (1 à 10 critères), limit optionnel (1-50), sortBy et sortDirection. Chaque filtre : field, operator, value, upperValue facultatif. Exemple : {"filters":[{"field":"age","operator":"LT","value":30},{"field":"direction","operator":"CONTAINS","value":"Finances"}],"limit":20}. Utiliser les champs et opérateurs décrits dans les consignes de l’outil.'
            required = $true
            location = 'body'
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
$existingTool = @($existingTools | Where-Object { $_.name -eq $tool.name } |
    Select-Object -First 1)
$jsonBody = $tool | ConvertTo-Json -Depth 8
$bodyBytes = [Text.Encoding]::UTF8.GetBytes($jsonBody)
if ($existingTool.Count -gt 0) {
    $method = 'Put'
    $uri = "$toolsUri/$($tool.name)"
} else {
    $method = 'Post'
    $uri = $toolsUri
}
$result = Invoke-RestMethod `
    -Uri $uri `
    -Method $method `
    -Headers $headers `
    -ContentType 'application/json; charset=utf-8' `
    -Body $bodyBytes `
    -TimeoutSec 15

Write-Output "Registered structured search tool: $($result.name) ($method)"
