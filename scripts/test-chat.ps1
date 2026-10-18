$ErrorActionPreference = 'Stop'

$secureToken = Read-Host -Prompt 'Enter your RH access token (input is hidden)' -AsSecureString
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureToken)
try {
    $accessToken = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
}
if ([string]::IsNullOrWhiteSpace($accessToken)) {
    throw 'An RH access token is required.'
}

$message = Read-Host 'Enter a message to send to Bandy'
if ([string]::IsNullOrWhiteSpace($message)) {
    throw 'A chat message is required.'
}

$bandyApiUrl = $env:BANDY_API_URL
if ([string]::IsNullOrWhiteSpace($bandyApiUrl)) {
    $bandyApiUrl = 'http://localhost:8090'
}

$response = Invoke-RestMethod `
    -Uri "$($bandyApiUrl.TrimEnd('/'))/api/chat" `
    -Method Post `
    -Headers @{ Authorization = "Bearer $accessToken" } `
    -ContentType 'application/json; charset=utf-8' `
    -Body (@{ message = $message } | ConvertTo-Json)

Write-Output ''
Write-Output $response.message
foreach ($toolResult in $response.toolResults) {
    Write-Output ("Executed tool: {0}" -f $toolResult.name)
}
