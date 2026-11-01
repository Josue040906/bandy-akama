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
        description = 'Recherche unifiée d’agents RH : identité approximative et critères combinés en ET/OU/NON, avec classement des candidats.'
        whenToUse = 'Utiliser pour toute recherche, identification ou filtrage d’agents, y compris avec un nom complet. Ne pas appeler get_agent_profile pour identifier. criteriaJson est un AST JSON avec must (ET), should (OU), must_not (NON) et minimum_should_match. Chaque feuille contient field, operator, value et upperValue facultatif; groupes imbriqués via bool. Pour une identité complète ou approximative, utiliser field=personne, operator=FUZZY_PERSON et value=nom complet; FUZZY pour les autres champs textuels. Respecter les champs/opérateurs autorisés et les dates ISO AAAA-MM-JJ. Interpréter récemment embauché comme WITHIN_LAST_MONTHS=12; demander une précision pour jeune/vieux ou toute ambiguïté déterminante. Garde-fou : ne jamais appeler avec AST vide, uniquement must_not ou uniquement limit/offset; exiger must non vide ou should non vide avec minimum_should_match >= 1. Sans critère positif et sans recherche active, demander nom/prénom, matricule, service, direction ou poste, sans appel API. Si l’utilisateur demande seulement d’afficher N agents dans une recherche active, conserver l’AST et modifier uniquement limit/offset. Pour un suivi, conserver les critères actifs. Pour un comptage, reprendre l’AST actif et passer resultMode=COUNT. LIST affiche les candidats.'
        method = 'POST'
        url = "$($assistantRhApiUrl.TrimEnd('/'))/api/recherche/agents/avancee"
        enabled = $true
        sensitive = $true
        parameters = @(
            @{
                name = 'criteriaJson'
                type = 'string'
                description = 'AST JSON encodé en chaîne, ex. {"must":[{"field":"direction","operator":"CONTAINS","value":"Finances"}],"should":[],"must_not":[],"minimum_should_match":0,"limit":20,"offset":0}. Champs autorisés: matricule, nom, prenom, personne, nom_complet, sexe, adresse, poste, service, direction, categorie, corps, type_emploi, classe, age, echelon, date_naissance, date_embauche. limit: 1 à 50; offset: 0 à 10000; sortBy: score, nom, prenom, age, date_embauche, poste, service, direction; sortDirection: ASC ou DESC. Opérateurs: EQ, NE, CONTAINS, FUZZY (texte; FUZZY interdit pour matricule/sexe); FUZZY_PERSON (personne/nom_complet); EQ, NE, GT, GTE, LT, LTE, BETWEEN (numérique); EQ, BEFORE, AFTER, BETWEEN, WITHIN_LAST_MONTHS, WITHIN_LAST_YEARS, OLDER_THAN_YEARS (date).'
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
        whenToUse = 'Utiliser uniquement après identification d’un agent, lorsque l’utilisateur demande explicitement son profil ou des détails personnels/professionnels : coordonnées, sexe, adresse, localité, dates, affectation, carrière, diplômes ou évolution. Ne pas utiliser cet outil pour une demande de recherche ou d’identification; utiliser search_agents dans ce cas. Fournir au paramètre query le matricule ou le nom complet. Si plusieurs agents correspondent, demander un élément distinctif. S’appuyer sur les valeurs retournées par l’API et ne jamais inventer une information absente.'
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
