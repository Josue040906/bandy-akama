# Sends one message to Bandy from a terminal: creates a conversation, then
# posts the message to it. Responses are decoded as UTF-8 (Bandy sends no
# charset, which Windows PowerShell 5.1 would otherwise read as ISO-8859-1).
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
$baseUri = "$($bandyApiUrl.TrimEnd('/'))/api/conversations"
$headers = @{ Authorization = "Bearer $accessToken" }

function Invoke-Bandy([string]$uri, $body = $null) {
    $request = @{ Uri = $uri; Method = 'Post'; Headers = $headers; UseBasicParsing = $true }
    if ($null -ne $body) {
        $request.ContentType = 'application/json; charset=utf-8'
        $request.Body = [Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json))
    }
    $response = Invoke-WebRequest @request
    $parsed = [Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray()) | ConvertFrom-Json
    return $parsed
}

$conversation = Invoke-Bandy $baseUri
$response = Invoke-Bandy "$baseUri/$($conversation.id)/messages" @{ message = $message }

Write-Output ''
Write-Output $response.message
foreach ($toolResult in $response.toolResults) {
    Write-Output ("Executed tool: {0}" -f $toolResult.name)
}
foreach ($action in $response.actions) {
    Write-Output ("Proposed action: {0} {1} {2}" -f $action.type, $action.tool, $action.route)
}
