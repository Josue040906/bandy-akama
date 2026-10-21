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
    whenToUse = 'Utiliser pour toute recherche multicritère d’agents par âge, sexe, date ou ancienneté de recrutement, poste, service, direction, catégorie, corps, type d’emploi, classe ou échelon. Pour un nom ou prénom approximatif, une faute de frappe ou une orthographe incertaine, préférer searchEmployeesApproximately, qui compare les noms et prénoms avec tolérance aux fautes. Ne pas enchaîner des recherches en inventant des variantes de nom. Convertir une recherche structurée en JSON dans criteriaJson selon ce format : {"filters":[{"field":"age","operator":"LT","value":30},{"field":"direction","operator":"CONTAINS","value":"Finances"}],"limit":20}. Tous les filtres sont combinés avec ET. Champs : matricule, nom, prenom, sexe, adresse, poste, service, direction, categorie, corps, type_emploi, classe, echelon, age, date_naissance, date_embauche. Le schéma actuel ne lie pas de table de grade distincte à l’agent : ne propose pas de filtre grade. Opérateurs texte : EQ, NE, CONTAINS, FUZZY. Utiliser FUZZY pour les valeurs textuelles saisies par l’utilisateur qui peuvent comporter une faute (nom, prénom, adresse, poste, service, direction, catégorie, corps, type d’emploi, classe); conserver la saisie originale sans la corriger. La recherche tolère les fautes par similarité de trigrammes et de mots. FUZZY nécessite au moins 4 caractères; matricule et sexe restent exacts. Opérateurs numériques : EQ, NE, GT, GTE, LT, LTE, BETWEEN avec upperValue. Dates au format AAAA-MM-JJ : EQ, NE, BEFORE, AFTER, BETWEEN avec upperValue, WITHIN_LAST_YEARS ou OLDER_THAN_YEARS avec un nombre d’années. Pour une date exacte, comparer les dates ISO. « Avant 2020 » signifie BEFORE 2020-01-01. Convertir « moins de 30 ans » en age LT 30 ; « dans la trentaine » en age BETWEEN 30 et 39 ; femmes = sexe EQ F. « Récemment » ou « depuis longtemps » sans durée précise est ambigu : demander le nombre d’années avant l’appel. Si l’identité ou la demande est ambiguë, demander une précision. Ne jamais inventer de résultats.'
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

$profileTool = @{
    name = 'getMyProfile'
    description = 'Retourne le profil professionnel du compte authentifié : identité, coordonnées de compte et parcours de carrière utile.'
    whenToUse = 'Utiliser uniquement lorsque l’utilisateur demande ce que Bandy sait de lui, son identité, son adresse e-mail ou ses informations/carrière professionnelles. L’API déduit son identité du jeton Bearer; ne demande ni matricule ni identifiant et ne recherche jamais le profil d’une autre personne avec cet outil. Les résultats de cet outil sont sensibles et ne doivent pas être répétés au-delà de la question posée.'
    method = 'GET'
    url = "$($assistantRhApiUrl.TrimEnd('/'))/api/profil/agent/mon-profil"
    enabled = $true
    sensitive = $true
    parameters = @()
}

$approximateSearchTool = @{
    name = 'searchEmployeesApproximately'
    description = 'Recherche un agent à partir de son nom saisi approximativement ou comportant une faute de frappe.'
    whenToUse = 'Utiliser cet outil pour retrouver une personne lorsque l’utilisateur donne un nom ou prénom approximatif, mentionne une faute de frappe, ou que la recherche exacte ne trouve aucun agent. Transmettre le nom complet tel que fourni dans query, sans le corriger ni en inventer une autre variante. Cet outil compare les noms et prénoms avec une tolérance aux fautes. Ne pas l’utiliser pour une recherche par âge, sexe, affectation ou critères multiples : utiliser searchEmployees. Ne pas enchaîner des recherches en modifiant le nom au hasard. Si aucun candidat fiable n’est retourné ou si plusieurs personnes correspondent, présenter la clarification nécessaire sans inventer de correspondance.'
    method = 'GET'
    url = "$($assistantRhApiUrl.TrimEnd('/'))/api/recherche/agents/approximative"
    enabled = $true
    parameters = @(
        @{
            name = 'query'
            type = 'string'
            description = 'Nom ou prénom de la personne exactement comme fourni par l’utilisateur, même s’il semble contenir une faute.'
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
foreach ($definition in @($tool, $approximateSearchTool, $profileTool)) {
    $existingTool = @($existingTools |
        Where-Object { $_.name -eq $definition.name } |
        Select-Object -First 1)
    $jsonBody = $definition | ConvertTo-Json -Depth 8
    $bodyBytes = [Text.Encoding]::UTF8.GetBytes($jsonBody)
    if ($existingTool.Count -gt 0) {
        $method = 'Put'
        $uri = "$toolsUri/$($definition.name)"
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

    Write-Output "Registered tool: $($result.name) ($method)"
}
