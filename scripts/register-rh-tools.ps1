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

$tool = @{
    name = 'searchEmployees'
    description = 'Recherche multicritère d’agents RH à partir de critères structurés.'
    whenToUse = 'Pour toute recherche multicritère d’agents par âge, sexe, embauche, poste, service, direction, catégorie, corps, type d’emploi, classe ou échelon, utilise searchEmployees avec criteriaJson au format {"filters":[{"field":"age","operator":"LT","value":30}],"limit":20}; les filtres sont combinés avec ET. Champs autorisés : matricule, nom, prenom, sexe, adresse, poste, service, direction, categorie, corps, type_emploi, classe, echelon, age, date_naissance, date_embauche (pas de filtre grade). Texte : EQ, NE, CONTAINS, FUZZY; utilise FUZZY si la valeur peut contenir une faute (minimum 4 caractères), mais garde matricule et sexe exacts. Numérique : EQ, NE, GT, GTE, LT, LTE, BETWEEN avec upperValue. Date AAAA-MM-JJ : EQ, NE, BEFORE, AFTER, BETWEEN avec upperValue, WITHIN_LAST_YEARS ou OLDER_THAN_YEARS. « Avant 2020 » = BEFORE 2020-01-01. Convertis « moins de 30 ans » en age LT 30, « trentaine » en age BETWEEN 30 et 39 et « femmes » en sexe EQ F. En cas de durée récente/ancienne non précisée ou d’ambiguïté, demande une précision. Pour les noms approximatifs, les fautes ou une orthographe incertaine, utilise plutôt searchEmployeesApproximately et n’invente pas de variantes. Ne jamais inventer de résultats.'
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
    description = 'Recherche et classe les candidats agents par similarité de nom, avec filtres optionnels de service, poste ou direction.'
    whenToUse = 'Utiliser cet outil pour retrouver une personne lorsque l’utilisateur donne un nom ou prénom approximatif, mentionne une faute de frappe, ou que la recherche exacte ne trouve aucun agent. Transmettre le nom comme query, sans le corriger ni inventer une variante. L’outil renvoie les deux meilleurs candidats disponibles, triés par score_correspondance, ainsi que leur score de similarité de 0 à 1 (ce n’est pas une probabilité d’identité). Si l’utilisateur ajoute un service, poste ou direction dans la même recherche, conserver le query original et ne transmettre que le ou les filtres ajoutés; ils s’appliquent en plus du nom. Pour supprimer un filtre existant, transmettre une chaîne vide. Ne pas enchaîner des recherches en modifiant le nom au hasard. Si aucun candidat n’est retourné, demander une précision; si plusieurs candidats sont retournés, les présenter avec leur score et laisser l’utilisateur choisir, sans confirmer son identité.'
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
        },
        @{
            name = 'service'
            type = 'string'
            description = 'Filtre facultatif de service, ajouté lors d’un suivi de la même recherche.'
            required = $false
            location = 'query'
        },
        @{
            name = 'poste'
            type = 'string'
            description = 'Filtre facultatif de poste, ajouté lors d’un suivi de la même recherche.'
            required = $false
            location = 'query'
        },
        @{
            name = 'direction'
            type = 'string'
            description = 'Filtre facultatif de direction, ajouté lors d’un suivi de la même recherche.'
            required = $false
            location = 'query'
        }
    )
}

$organizationOverviewTool = @{
    name = 'getOrganizationOverview'
    description = 'Compte les agents et les principaux référentiels RH.'
    whenToUse = 'Pour répondre à une question de nombre global d’agents, postes, services, directions, catégories, types d’emploi, grades, classes, échelons ou niveaux de diplôme, appeler cet outil et reprendre les compteurs renvoyés. Ce sont des nombres d’enregistrements, pas nécessairement des effectifs actifs.'
    method = 'GET'
    url = "$($assistantRhApiUrl.TrimEnd('/'))/api/assistant/insights/organization"
    enabled = $true
    parameters = @()
}

$positionCountTool = @{
    name = 'countEmployeesByPosition'
    description = 'Compte les agents actuellement affectés à chaque poste ou à un poste donné.'
    whenToUse = 'Pour compter les agents affectés actuellement à un poste ou comparer les effectifs par poste. Transmettre query si un poste précis est demandé; omettre query pour obtenir les postes et leurs effectifs. Les effectifs correspondent aux affectations en cours selon les dates RH. Ne déduis pas qu’un poste est vacant si le résultat est vide.'
    method = 'GET'
    url = "$($assistantRhApiUrl.TrimEnd('/'))/api/assistant/insights/employees-by-position"
    enabled = $true
    parameters = @(
        @{
            name = 'query'
            type = 'string'
            description = 'Partie du libellé du poste à rechercher; facultatif.'
            required = $false
            location = 'query'
        }
    )
}

$positionsTool = @{
    name = 'getPositions'
    description = 'Liste les postes enregistrés dans le référentiel RH.'
    whenToUse = 'Pour connaître ou citer les postes enregistrés. Pour compter les agents affectés à un poste, utiliser countEmployeesByPosition plutôt que de compter cette liste.'
    method = 'GET'
    url = "$($assistantRhApiUrl.TrimEnd('/'))/api/postes"
    enabled = $true
    parameters = @()
}

$servicesTool = @{
    name = 'getServices'
    description = 'Liste les services enregistrés dans le référentiel RH.'
    whenToUse = 'Pour connaître ou citer les services enregistrés dans SYGPERS. Ne complète pas la liste avec des valeurs supposées.'
    method = 'GET'
    url = "$($assistantRhApiUrl.TrimEnd('/'))/api/services"
    enabled = $true
    parameters = @()
}

$directionsTool = @{
    name = 'getDirections'
    description = 'Liste les directions enregistrées dans le référentiel RH.'
    whenToUse = 'Pour connaître ou citer les directions enregistrées dans SYGPERS. Ne complète pas la liste avec des valeurs supposées.'
    method = 'GET'
    url = "$($assistantRhApiUrl.TrimEnd('/'))/api/directions"
    enabled = $true
    parameters = @()
}

$gradesTool = @{
    name = 'getGrades'
    description = 'Liste les grades enregistrés dans le référentiel RH.'
    whenToUse = 'Pour citer les grades enregistrés. Pour expliquer la progression de carrière, utiliser également les règles de carrière et distinguer les possibilités des décisions validées.'
    method = 'GET'
    url = "$($assistantRhApiUrl.TrimEnd('/'))/api/grades"
    enabled = $true
    parameters = @()
}

$highestDegreeTool = @{
    name = 'getHighestDegreeEmployees'
    description = 'Identifie les agents ayant un diplôme de niveau académique maximal enregistré.'
    whenToUse = 'Pour demander quel agent ou quels agents ont le niveau de diplôme le plus élevé. Utiliser les résultats réels et préciser que le classement suit ordre_academique configuré dans le référentiel; si aucun résultat n’est fourni, le dire. Ne jamais déduire l’ordre académique des libellés.'
    method = 'GET'
    url = "$($assistantRhApiUrl.TrimEnd('/'))/api/assistant/insights/highest-degree"
    enabled = $true
    sensitive = $true
    parameters = @()
}

$recentAssignmentsTool = @{
    name = 'getRecentAssignments'
    description = 'Liste les affectations récentes confirmées par le système RH.'
    whenToUse = 'Pour une question sur les affectations les plus récentes, appeler cet outil et rapporter les dates et postes renvoyés. Il s’agit des dates d’effet enregistrées; ne pas les présenter comme une création récente si le système ne le confirme pas.'
    method = 'GET'
    url = "$($assistantRhApiUrl.TrimEnd('/'))/api/assistant/insights/recent-assignments"
    enabled = $true
    sensitive = $true
    parameters = @(
        @{
            name = 'limit'
            type = 'integer'
            description = 'Nombre de résultats, de 1 à 50.'
            required = $false
            location = 'query'
        }
    )
}

$myActivitiesTool = @{
    name = 'getMyActivities'
    description = 'Consulte le journal des activités du compte RH connecté.'
    whenToUse = 'Uniquement pour raconter ou résumer les activités de l’utilisateur connecté. L’identité est déterminée par le JWT RH transmis par le serveur; ne cherche jamais l’activité d’une autre personne avec cet outil. Ne pas confondre une consultation ou un essai avec une action réussie.'
    method = 'GET'
    url = "$($assistantRhApiUrl.TrimEnd('/'))/api/activites"
    enabled = $true
    sensitive = $true
    parameters = @()
}

$referenceTablesTool = @{
    name = 'getEmploymentReferences'
    description = 'Liste les catégories et types d’emploi du référentiel RH.'
    whenToUse = 'Pour citer les catégories ou types d’emploi configurés dans le référentiel. Ne complète pas la liste avec des valeurs supposées.'
    method = 'GET'
    url = "$($assistantRhApiUrl.TrimEnd('/'))/api/employes/referentiels"
    enabled = $true
    parameters = @()
}

$diplomaReferencesTool = @{
    name = 'getDiplomaReferences'
    description = 'Liste les niveaux et domaines de diplôme disponibles dans le référentiel RH.'
    whenToUse = 'Pour citer les niveaux, domaines, établissements ou mentions proposés par le formulaire RH. Ce référentiel décrit les options disponibles et non les diplômes détenus par les agents.'
    method = 'GET'
    url = "$($assistantRhApiUrl.TrimEnd('/'))/api/referentiels/diplomes/options"
    enabled = $true
    parameters = @()
}

$careerRulesTool = @{
    name = 'getCareerRules'
    description = 'Consulte les règles de reclassement et les cibles de carrière configurées.'
    whenToUse = 'Pour expliquer les règles ou possibilités de carrière/échelon présentes dans SYGPERS. Appeler cet outil avant de présenter une règle comme applicable; distinguer les données configurées d’une explication générale et ne pas donner d’avis juridique.'
    method = 'GET'
    url = "$($assistantRhApiUrl.TrimEnd('/'))/api/referentiels/reclassements"
    enabled = $true
    parameters = @()
}

$careerTargetsTool = @{
    name = 'getCareerTargets'
    description = 'Liste les classes et échelons cibles de carrière disponibles.'
    whenToUse = 'Pour connaître les classes, échelons et cibles de carrière enregistrés dans SYGPERS. Ne pas transformer les options en décision d’avancement ou d’éligibilité.'
    method = 'GET'
    url = "$($assistantRhApiUrl.TrimEnd('/'))/api/referentiels/reclassements/options"
    enabled = $true
    parameters = @()
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

foreach ($definition in @($agentProfileTool)) {
    if ($definition.whenToUse.Length -gt 2000) {
        throw "Tool instructions for $($definition.name) exceed the 2000-character API limit."
    }
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
        throw "Registration of $($definition.name) failed (HTTP $statusCode): $detail"
    }

    $registeredParameters = @($result.parameters | ForEach-Object { $_.name })
    $expectedParameters = switch ($definition.name) {
        'get_agent_profile' { @('nom_agent') }
        default { @() }
    }
    if ($expectedParameters.Count -gt 0) {
        $missingParameters = @($expectedParameters | Where-Object {
            $registeredParameters -notcontains $_
        })
        if ($missingParameters.Count -gt 0) {
            throw "Bandy did not retain the expected parameters for $($result.name): $($missingParameters -join ', ')."
        }
        Write-Output "Registered tool: $($result.name) ($method); parameters: $($registeredParameters -join ', ')"
    } else {
        Write-Output "Registered tool: $($result.name) ($method)"
    }
}

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
