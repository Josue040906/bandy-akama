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

$tools = @(
    @{
        name = 'search_agents'
        description = 'Recherche les agents par critères RH structurés. Utiliser deux filtres FUZZY séparés pour un nom complet, et resultMode=COUNT pour un total exact.'
        whenToUse = 'Utiliser pour toute recherche d’agents par critères, y compris les suivis qui ajoutent ou modifient des filtres. criteriaJson doit contenir un objet JSON avec 1 à 10 filtres utilisant uniquement les champs et opérateurs supportés par l’API. resultMode=COUNT demande un comptage exact backend; ne jamais compter une page de résultats. Utiliser LIST pour afficher les correspondances.'
        method = 'POST'
        url = "$($assistantRhApiUrl.TrimEnd('/'))/api/recherche/agents/criteres"
        enabled = $true
        sensitive = $true
        parameters = @(
            @{
                name = 'criteriaJson'
                type = 'string'
                description = 'Objet JSON encodé en chaîne, ex. {"filters":[{"field":"direction","operator":"CONTAINS","value":"Finances"}],"limit":20,"offset":0}. Champs autorisés: matricule, nom, prenom, sexe, adresse, poste, service, direction, categorie, corps, type_emploi, classe, age, echelon, date_naissance, date_embauche. Opérateurs: EQ, NE, CONTAINS, FUZZY (texte; FUZZY interdit pour matricule et sexe); EQ, NE, GT, GTE, LT, LTE, BETWEEN (numérique); EQ, NE, BEFORE, AFTER, BETWEEN, WITHIN_LAST_YEARS, OLDER_THAN_YEARS (date).'
                required = $true
                location = 'body'
            },
            @{
                name = 'resultMode'
                type = 'string'
                description = 'LIST (défaut) ou COUNT pour obtenir le nombre exact côté API.'
                required = $false
                location = 'body'
            }
        )
    },
    @{
        name = 'get_agent_profile'
        description = 'Récupère le profil détaillé d’un agent à partir de son nom ou de son matricule : identité, informations personnelles disponibles, situation professionnelle actuelle, affectation, parcours de carrière, diplômes et analyse d’évolution de carrière calculée par le backend.'
        whenToUse = 'Utiliser pour toute question portant sur un agent précis : identité et coordonnées enregistrées, sexe, adresse et localité, date d’embauche, type d’emploi, catégorie, corps, classe, échelon, poste, service, direction, dates de situation ou d’affectation, historique de carrière, diplômes (niveau, domaine, établissement, mention, date et numéro lorsqu’ils sont disponibles) et évolution de carrière. Fournir au paramètre query le nom complet ou le matricule mentionné dans la conversation. Si plusieurs agents correspondent, ne choisir personne au hasard : demander le matricule ou un autre élément distinctif. Si aucun agent ne correspond, le signaler et demander de vérifier le nom ou le matricule. S’appuyer sur les valeurs retournées par l’API; ne jamais déduire une information absente ni présenter une éligibilité indicative comme une décision administrative. Ne rappeler l’outil que si une donnée manque ou doit être actualisée.'
        method = 'GET'
        url = "$($assistantRhApiUrl.TrimEnd('/'))/api/profil/agent"
        enabled = $true
        sensitive = $true
        parameters = @(
            @{
                name = 'query'
                type = 'string'
                description = 'Nom complet ou matricule de l’agent à identifier, transmis au paramètre query de l’API. Transmettre la valeur telle qu’elle apparaît dans la demande; demander une précision si l’identité est ambiguë.'
                required = $true
                location = 'query'
            }
        )
    },
    @{
        name = 'get_organization_info'
        description = 'Recherche les directions, services et postes avec leurs rattachements et descriptions réellement enregistrés.'
        whenToUse = 'Pour expliquer la structure des directions, services et postes, leurs rattachements ou leurs descriptions disponibles. Ne pas inventer de responsabilités absentes des données.'
        method = 'GET'
        url = "$($assistantRhApiUrl.TrimEnd('/'))/api/assistant/organization"
        enabled = $true
        sensitive = $false
        parameters = @(
            @{
                name = 'query'
                type = 'string'
                description = 'Nom ou terme à rechercher dans les directions, services, postes et descriptions.'
                required = $true
                location = 'query'
            },
            @{
                name = 'scope'
                type = 'string'
                description = 'ALL, DIRECTION, SERVICE ou POSTE.'
                required = $false
                location = 'query'
            }
        )
    }
)
$managedToolNames = @($tools | ForEach-Object { $_.name })

$headers = @{
    'X-Bandy-Registration-Key' = $registrationKey
}
$toolsUri = "$($bandyApiUrl.TrimEnd('/'))/api/tools"
$existingTools = Invoke-RestMethod `
    -Uri $toolsUri `
    -Method Get `
    -Headers $headers `
    -TimeoutSec 15

foreach ($tool in $tools) {
    if ($tool.whenToUse.Length -gt 2000) {
        throw "Tool instructions for $($tool.name) exceed the 2000-character API limit."
    }
    $existingTool = @($existingTools |
        Where-Object { $_.name -eq $tool.name } |
        Select-Object -First 1)
    $jsonBody = $tool | ConvertTo-Json -Depth 10
    $bodyBytes = [Text.Encoding]::UTF8.GetBytes($jsonBody)
    if ($existingTool.Count -gt 0) {
        $method = 'Put'
        $uri = "$toolsUri/$($tool.name)"
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
        throw "Registration of $($tool.name) failed (HTTP $statusCode). Check the service logs for details."
    }

    $registeredParameters = @($result.parameters | ForEach-Object { $_.name })
    $expectedParameters = @($tool.parameters | ForEach-Object { $_.name })
    if ($registeredParameters.Count -ne $expectedParameters.Count) {
        throw "Bandy did not retain the expected parameter list for $($tool.name)."
    }
    foreach ($parameterName in $expectedParameters) {
        if ($registeredParameters -notcontains $parameterName) {
            throw "Bandy did not retain parameter $parameterName for $($tool.name)."
        }
    }
    if ($result.method -and $result.method -ne $tool.method) {
        throw "Bandy retained an unexpected HTTP method for $($tool.name)."
    }
    if ($result.url -and $result.url -ne $tool.url) {
        throw "Bandy retained an unexpected endpoint URL for $($tool.name)."
    }
    if ([bool]$result.enabled -ne [bool]$tool.enabled) {
        throw "Bandy retained an unexpected enabled flag for $($tool.name)."
    }
    if ([bool]$result.sensitive -ne [bool]$tool.sensitive) {
        throw "Bandy retained an unexpected sensitivity flag for $($tool.name)."
    }
    foreach ($expectedParameter in $tool.parameters) {
        $registeredParameter = @($result.parameters |
            Where-Object { $_.name -eq $expectedParameter.name } |
            Select-Object -First 1)
        if ($registeredParameter.Count -eq 0) {
            throw "Bandy did not retain parameter $($expectedParameter.name)."
        }
        $parameter = $registeredParameter[0]
        if ($parameter.type -ne $expectedParameter.type -or
            [bool]$parameter.required -ne [bool]$expectedParameter.required -or
            $parameter.location -ne $expectedParameter.location) {
            throw "Bandy retained an unexpected schema for parameter $($expectedParameter.name) of $($tool.name)."
        }
    }
    Write-Output "Registered tool: $($tool.name) ($method); verified parameters: $($registeredParameters -join ', ')"
}

$obsoleteTools = @($existingTools | Where-Object {
    $managedToolNames -notcontains $_.name
})
if ($obsoleteTools.Count -gt 0) {
    Write-Output "Existing tools not managed by this script were preserved: $((@($obsoleteTools | ForEach-Object { $_.name }) -join ', '))"
}

if ($RemoveLegacyTools) {
    foreach ($legacyTool in @($existingTools | Where-Object {
        $managedToolNames -notcontains $_.name
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
    Write-Output "Unmanaged tools were left unchanged. Re-run with -RemoveLegacyTools to remove them explicitly."
}
