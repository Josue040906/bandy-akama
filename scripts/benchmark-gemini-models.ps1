param(
    [ValidateRange(1, 5)]
    [int]$Repetitions = 3
)

$ErrorActionPreference = 'Stop'

function Get-ProviderErrorBody {
    param(
        [System.Management.Automation.ErrorRecord]$ErrorRecord
    )

    if (-not [string]::IsNullOrWhiteSpace($ErrorRecord.ErrorDetails.Message)) {
        return $ErrorRecord.ErrorDetails.Message
    }

    $response = $ErrorRecord.Exception.Response
    if ($null -eq $response) {
        return ''
    }

    $stream = $null
    $reader = $null
    try {
        $stream = $response.GetResponseStream()
        if ($null -eq $stream) {
            return ''
        }
        $reader = [System.IO.StreamReader]::new($stream)
        return $reader.ReadToEnd()
    } catch {
        return ''
    } finally {
        if ($null -ne $reader) {
            $reader.Dispose()
        } elseif ($null -ne $stream) {
            $stream.Dispose()
        }
    }
}

$models = @('gemini-3.8-flash', 'gemini-3.5-flash-lite')
$cases = @(
    [pscustomobject]@{
        Name = 'approximate-agent-search'
        Prompt = 'Recherche l’agent nommé Agent Exemple 001.'
        FunctionName = 'searchEmployeesApproximately'
        Description = 'Recherche approximative d’agents RH par nom, avec filtres organisationnels facultatifs.'
        Parameters = @{
            type = 'OBJECT'
            properties = @{
                query = @{ type = 'STRING'; description = 'Nom ou partie du nom à rechercher.' }
                service = @{ type = 'STRING'; description = 'Filtre facultatif par service.' }
                poste = @{ type = 'STRING'; description = 'Filtre facultatif par poste.' }
                direction = @{ type = 'STRING'; description = 'Filtre facultatif par direction.' }
            }
            required = @('query')
        }
    },
    [pscustomobject]@{
        Name = 'employee-count-by-position'
        Prompt = 'Combien d’agents occupent le poste de chef de service ?'
        FunctionName = 'countEmployeesByPosition'
        Description = 'Compte les agents affectés à un poste donné.'
        Parameters = @{
            type = 'OBJECT'
            properties = @{
                query = @{ type = 'STRING'; description = 'Intitulé du poste à compter.' }
            }
            required = @('query')
        }
    }
)

$apiKey = $env:GEMINI_API_KEY
if ([string]::IsNullOrWhiteSpace($apiKey)) {
    $secureKey = Read-Host -Prompt 'Enter GEMINI_API_KEY (input is hidden)' -AsSecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureKey)
    try {
        $apiKey = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}
if ([string]::IsNullOrWhiteSpace($apiKey)) {
    throw 'A Gemini API key is required.'
}

$results = [System.Collections.Generic.List[object]]::new()
$headers = @{ 'x-goog-api-key' = $apiKey }
$abortBenchmark = $false
$systemInstruction = @'
Tu es un assistant RH. Pour répondre à une demande nécessitant des données RH,
utilise l’outil disponible et transmets les paramètres utiles. N’invente jamais
de résultat métier.
'@.Trim()

for ($repetition = 1; $repetition -le $Repetitions; $repetition++) {
    for ($caseIndex = 0; $caseIndex -lt $cases.Count; $caseIndex++) {
        $case = $cases[$caseIndex]
        if (($repetition + $caseIndex) % 2 -eq 0) {
            $modelOrder = $models
        } else {
            $modelOrder = @($models[1], $models[0])
        }

        foreach ($model in $modelOrder) {
            $body = @{
                systemInstruction = @{ parts = @(@{ text = $systemInstruction }) }
                contents = @(
                    @{
                        role = 'user'
                        parts = @(@{ text = $case.Prompt })
                    }
                )
                tools = @(
                    @{
                        functionDeclarations = @(
                            @{
                                name = $case.FunctionName
                                description = $case.Description
                                parameters = $case.Parameters
                            }
                        )
                    }
                )
                toolConfig = @{
                    functionCallingConfig = @{ mode = 'AUTO' }
                }
                generationConfig = @{
                    candidateCount = 1
                    maxOutputTokens = 256
                    thinkingConfig = @{ thinkingLevel = 'LOW' }
                }
            }

            if ($body.ContainsKey('serviceTier')) {
                throw 'The benchmark must omit serviceTier to use the API default Standard tier.'
            }

            $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
            $status = 'ok'
            $toolName = ''
            $inputTokens = 0
            $outputTokens = 0
            $errorDetail = ''
            try {
                $response = Invoke-RestMethod `
                    -Uri "https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent" `
                    -Method Post `
                    -Headers $headers `
                    -ContentType 'application/json; charset=utf-8' `
                    -TimeoutSec 45 `
                    -Body ($body | ConvertTo-Json -Depth 20 -Compress)

                $functionCalls = @(
                    foreach ($candidate in $response.candidates) {
                        foreach ($part in $candidate.content.parts) {
                            if ($part.functionCall) {
                                $part.functionCall
                            }
                        }
                    }
                )
                $toolName = (@($functionCalls | ForEach-Object { $_.name }) -join ', ')
                $expectedFunctionCall = @(
                    $functionCalls |
                        Where-Object { $_.name -eq $case.FunctionName } |
                        Select-Object -First 1
                )
                $queryArgument = if ($expectedFunctionCall.Count -gt 0) {
                    [string]$expectedFunctionCall[0].args.query
                } else {
                    ''
                }
                if ([string]::IsNullOrWhiteSpace($queryArgument)) {
                    $status = 'wrong-or-missing-tool'
                }
                if ($response.usageMetadata) {
                    $inputTokens = [int]$response.usageMetadata.promptTokenCount
                    $outputTokens = [int]$response.usageMetadata.candidatesTokenCount
                }
            } catch {
                $httpStatus = $null
                if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
                    $httpStatus = [int]$_.Exception.Response.StatusCode
                }
                $errorBody = Get-ProviderErrorBody -ErrorRecord $_
                if (-not [string]::IsNullOrWhiteSpace($errorBody)) {
                    try {
                        $parsedError = $errorBody | ConvertFrom-Json
                        $errorDetail = [string]$parsedError.error.message
                    } catch {
                        $errorDetail = $errorBody
                    }
                }
                if ([string]::IsNullOrWhiteSpace($errorDetail)) {
                    $errorDetail = $_.Exception.Message
                }
                $errorDetail = $errorDetail.Replace($apiKey, '[REDACTED]')
                $errorDetail = $errorDetail -replace 'AIza[0-9A-Za-z_-]{30,}', '[REDACTED]'
                if ($errorDetail.Length -gt 400) {
                    $errorDetail = $errorDetail.Substring(0, 400) + '...'
                }
                $status = if ($httpStatus) { "http-$httpStatus" } else { 'network-or-timeout-error' }
                if ($httpStatus -eq 400) {
                    $abortBenchmark = $true
                }
            } finally {
                $stopwatch.Stop()
            }

            $results.Add([pscustomobject]@{
                Model = $model
                Repetition = $repetition
                Scenario = $case.Name
                LatencyMs = $stopwatch.ElapsedMilliseconds
                Status = $status
                Tool = $toolName
                InputTokens = $inputTokens
                OutputTokens = $outputTokens
                Error = $errorDetail
            })
            if ($abortBenchmark) {
                break
            }
        }
        if ($abortBenchmark) {
            break
        }
    }
    if ($abortBenchmark) {
        break
        }
    }

Write-Output 'Individual runs (no RH tools are executed):'
$results |
    Sort-Object Repetition, Scenario, Model |
    Format-Table Model, Repetition, Scenario, LatencyMs, Status, Tool, InputTokens, OutputTokens -AutoSize

$failedRuns = @($results | Where-Object { -not [string]::IsNullOrWhiteSpace($_.Error) })
if ($failedRuns.Count -gt 0) {
    Write-Output ''
    Write-Output 'Provider error details (API keys redacted):'
    foreach ($failedRun in $failedRuns) {
        Write-Output ('{0} / {1}: {2} - {3}' -f $failedRun.Model, $failedRun.Scenario, $failedRun.Status, $failedRun.Error)
    }
}

Write-Output ''
Write-Output 'Summary by model:'
$plannedRunsPerModel = $Repetitions * $cases.Count
foreach ($model in $models) {
    $modelResults = @($results | Where-Object Model -eq $model)
    $latencies = @($modelResults | Where-Object Status -eq 'ok' | ForEach-Object LatencyMs | Sort-Object)
    $medianMs = if ($latencies.Count -eq 0) {
        'n/a'
    } elseif ($latencies.Count % 2 -eq 1) {
        $latencies[[int][math]::Floor($latencies.Count / 2)]
    } else {
        [math]::Round(($latencies[($latencies.Count / 2) - 1] + $latencies[$latencies.Count / 2]) / 2)
    }
    $responsesReceived = @(
        $modelResults |
            Where-Object { $_.Status -notmatch '^(http-\d+|network-or-timeout-error)$' }
    ).Count
    $toolSuccesses = @($modelResults | Where-Object Status -eq 'ok').Count
    [pscustomobject]@{
        Model = $model
        AttemptsCompleted = "$($modelResults.Count)/$plannedRunsPerModel"
        ResponsesReceived = "$responsesReceived/$plannedRunsPerModel"
        CorrectToolSelection = "$toolSuccesses/$plannedRunsPerModel"
        MedianLatencyMs = $medianMs
    }
}
