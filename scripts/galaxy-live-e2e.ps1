#requires -Version 5.1
<#
.SYNOPSIS
Runs one authenticated live Galaxy-to-Lila model turn using the same owner/character
conversation ID that Galaxy Android derives locally.

.DESCRIPTION
This is an executable Windows validation harness, not a deployment script.
It requires an HTTPS Lila backend and a running model server reachable FROM THE
BACKEND process/container. It never writes tokens or provider keys to disk or logs.

Default local target is LM Studio on Windows behind Docker Desktop:
  http://host.docker.internal:1234/v1

Other local examples:
  Ollama native: http://host.docker.internal:11434
  llama.cpp:     http://host.docker.internal:8080/v1

For a cloud provider, pass -ProviderType cloud and an HTTPS -ProviderBaseUrl.

The script creates or explicitly updates one owner-scoped provider, probes its model
catalog, selects it for the same deterministic conversation Galaxy uses, sends one
test turn with an idempotency key, then reads the durable history back.

The test turn is added to the selected Galaxy conversation. No API key is returned
by the backend; no credential is written to preferences, files, or output.
#>
[CmdletBinding()]
param(
    [string]$BackendBaseUrl,
    [ValidateSet('local', 'cloud')]
    [string]$ProviderType = 'local',
    [string]$ProviderBaseUrl = 'http://host.docker.internal:1234/v1',
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$')]
    [string]$ProviderId = 'lmstudio-live',
    [string]$ModelId,
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$')]
    [string]$CharacterId = 'galaxy.default',
    [ValidateRange(1, 120)]
    [int]$TimeoutSeconds = 90,
    [string]$Message = 'Проверка связи: ответь по-русски одной короткой фразой и представься как Лила.',
    [switch]$UpdateExisting,
    [switch]$SelfTest
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Read-SecretText {
    param([Parameter(Mandatory = $true)][string]$Prompt)

    $secure = Read-Host -Prompt $Prompt -AsSecureString
    if ($secure.Length -eq 0) {
        $secure.Dispose()
        return $null
    }

    $pointer = [IntPtr]::Zero
    try {
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    }
    finally {
        if ($pointer -ne [IntPtr]::Zero) {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
        }
        $secure.Dispose()
    }
}

function Get-JwtSubject {
    param([Parameter(Mandatory = $true)][string]$Token)

    $parts = $Token.Split('.')
    if ($parts.Length -ne 3) {
        throw 'The supplied Supabase access token is not a JWT.'
    }

    $payload = $parts[1].Replace('-', '+').Replace('_', '/')
    switch ($payload.Length % 4) {
        0 { }
        2 { $payload += '==' }
        3 { $payload += '=' }
        default { throw 'The supplied access token has an invalid JWT payload.' }
    }

    $payloadBytes = [Convert]::FromBase64String($payload)
    try {
        $claimsJson = [Text.Encoding]::UTF8.GetString($payloadBytes)
        $claims = ConvertFrom-Json -InputObject $claimsJson -ErrorAction Stop
        $subject = [string]$claims.sub
        if ([string]::IsNullOrWhiteSpace($subject) -or $subject.Length -gt 128) {
            throw 'The access token does not contain a valid subject claim.'
        }
        return $subject
    }
    finally {
        [Array]::Clear($payloadBytes, 0, $payloadBytes.Length)
        $payload = $null
        $claimsJson = $null
        $claims = $null
    }
}

function Get-OwnerPartitionKey {
    param([Parameter(Mandatory = $true)][string]$OwnerId)

    $ownerBytes = [Text.Encoding]::UTF8.GetBytes($OwnerId)
    $sha = [Security.Cryptography.SHA256]::Create()
    $digest = $null
    $prefix = New-Object byte[] 16
    try {
        $digest = $sha.ComputeHash($ownerBytes)
        [Array]::Copy($digest, $prefix, 16)
        return [BitConverter]::ToString($prefix).Replace('-', '').ToLowerInvariant()
    }
    finally {
        $sha.Dispose()
        [Array]::Clear($ownerBytes, 0, $ownerBytes.Length)
        if ($null -ne $digest) {
            [Array]::Clear($digest, 0, $digest.Length)
        }
        [Array]::Clear($prefix, 0, $prefix.Length)
    }
}

function Get-StableLilaConversationId {
    param(
        [Parameter(Mandatory = $true)][string]$OwnerKey,
        [Parameter(Mandatory = $true)][string]$Character
    )

    $material = 'lila-conversation-v1' + [char]0 + $OwnerKey + [char]0 + $Character
    $materialBytes = [Text.Encoding]::UTF8.GetBytes($material)
    $sha = [Security.Cryptography.SHA256]::Create()
    $digest = $null
    $uuidBytes = New-Object byte[] 16
    try {
        $digest = $sha.ComputeHash($materialBytes)
        [Array]::Copy($digest, $uuidBytes, 16)
        $uuidBytes[6] = [byte](($uuidBytes[6] -band 0x0F) -bor 0x50)
        $uuidBytes[8] = [byte](($uuidBytes[8] -band 0x3F) -bor 0x80)
        $hex = [BitConverter]::ToString($uuidBytes).Replace('-', '').ToLowerInvariant()
        return (
            $hex.Substring(0, 8) + '-' +
            $hex.Substring(8, 4) + '-' +
            $hex.Substring(12, 4) + '-' +
            $hex.Substring(16, 4) + '-' +
            $hex.Substring(20, 12)
        )
    }
    finally {
        $sha.Dispose()
        [Array]::Clear($materialBytes, 0, $materialBytes.Length)
        if ($null -ne $digest) {
            [Array]::Clear($digest, 0, $digest.Length)
        }
        [Array]::Clear($uuidBytes, 0, $uuidBytes.Length)
        $material = $null
    }
}

function Test-ConversationIdDerivation {
    $ownerKey = Get-OwnerPartitionKey -OwnerId '00000000-0000-0000-0000-000000000001'
    $actual = Get-StableLilaConversationId -OwnerKey $ownerKey -Character 'galaxy.default'
    $expected = 'caddeb26-f8c2-5686-a5a4-b420facd7e4a'
    if ($actual -ne $expected) {
        throw 'The conversation-ID derivation does not match the Android implementation.'
    }
}

function Read-BoundedResponseText {
    param(
        [Parameter(Mandatory = $true)][System.IO.Stream]$Stream,
        [long]$ContentLength = -1,
        [ValidateRange(1, 4194304)]
        [int]$MaxResponseBytes = 1048576
    )

    $buffer = New-Object byte[] 8192
    $memoryStream = [System.IO.MemoryStream]::new()
    $internalBuffer = $null
    try {
        if ($ContentLength -gt $MaxResponseBytes) {
            return [pscustomobject]@{ Text = $null; TooLarge = $true }
        }

        while ($true) {
            $remaining = [long]$MaxResponseBytes - $memoryStream.Length
            $readLimit = [int][Math]::Min([long]$buffer.Length, $remaining + 1)
            $read = $Stream.Read($buffer, 0, $readLimit)
            if ($read -le 0) {
                break
            }
            if ($read -gt $remaining) {
                return [pscustomobject]@{ Text = $null; TooLarge = $true }
            }
            $memoryStream.Write($buffer, 0, $read)
        }

        $internalBuffer = [byte[]]$memoryStream.GetBuffer()
        $text = [Text.Encoding]::UTF8.GetString(
            $internalBuffer,
            0,
            [int]$memoryStream.Length
        )
        return [pscustomobject]@{ Text = $text; TooLarge = $false }
    }
    finally {
        [Array]::Clear($buffer, 0, $buffer.Length)
        if ($null -ne $memoryStream) {
            $internalBuffer = [byte[]]$memoryStream.GetBuffer()
            if ($internalBuffer.Length -gt 0) {
                [Array]::Clear($internalBuffer, 0, $internalBuffer.Length)
            }
            $memoryStream.Dispose()
        }
    }
}

function Test-BoundedResponseRead {
    $exactBytes = [Text.Encoding]::UTF8.GetBytes('1234')
    $oversizedBytes = [Text.Encoding]::UTF8.GetBytes('12345')
    $exactStream = [System.IO.MemoryStream]::new($exactBytes, $false)
    $declaredOversizedStream = [System.IO.MemoryStream]::new($oversizedBytes, $false)
    $chunkedOversizedStream = [System.IO.MemoryStream]::new($oversizedBytes, $false)
    try {
        $exact = Read-BoundedResponseText -Stream $exactStream -ContentLength 4 -MaxResponseBytes 4
        if ($exact.TooLarge -or $exact.Text -ne '1234') {
            throw 'The bounded response reader rejected a body at the exact limit.'
        }

        $declaredOversized = Read-BoundedResponseText -Stream $declaredOversizedStream -ContentLength 5 -MaxResponseBytes 4
        if (-not $declaredOversized.TooLarge -or $declaredOversizedStream.Position -ne 0) {
            throw 'The bounded response reader did not reject an oversized Content-Length before reading.'
        }

        $chunkedOversized = Read-BoundedResponseText -Stream $chunkedOversizedStream -ContentLength -1 -MaxResponseBytes 4
        if (-not $chunkedOversized.TooLarge -or $chunkedOversizedStream.Position -ne 5) {
            throw 'The bounded response reader did not stop after the limit plus one byte.'
        }
    }
    finally {
        $exactStream.Dispose()
        $declaredOversizedStream.Dispose()
        $chunkedOversizedStream.Dispose()
        [Array]::Clear($exactBytes, 0, $exactBytes.Length)
        [Array]::Clear($oversizedBytes, 0, $oversizedBytes.Length)
    }
}

function Invoke-LilaApi {
    param(
        [Parameter(Mandatory = $true)]
        [ValidateSet('GET', 'POST', 'PUT', 'DELETE')]
        [string]$Method,
        [Parameter(Mandatory = $true)]
        [string]$Path,
        [object]$Body,
        [hashtable]$ExtraHeaders = @{},
        [ValidateRange(1, 4194304)]
        [int]$MaxResponseBytes = 1048576
    )

    $request = $null
    $response = $null
    $responseStream = $null
    $json = $null
    $responseText = $null
    $data = $null
    try {
        $uri = [Uri]::new($script:BackendBaseUrl + $Path)
        $request = [System.Net.Http.HttpRequestMessage]::new(
            [System.Net.Http.HttpMethod]::new($Method),
            $uri
        )
        $request.Headers.Authorization =
            [System.Net.Http.Headers.AuthenticationHeaderValue]::new(
                'Bearer',
                $script:AccessToken
            )
        [void]$request.Headers.Accept.ParseAdd('application/json')

        foreach ($headerName in $ExtraHeaders.Keys) {
            [void]$request.Headers.TryAddWithoutValidation(
                [string]$headerName,
                [string]$ExtraHeaders[$headerName]
            )
        }

        if ($null -ne $Body) {
            $json = ConvertTo-Json -InputObject $Body -Depth 10 -Compress
            $request.Content = [System.Net.Http.StringContent]::new(
                $json,
                [Text.Encoding]::UTF8,
                'application/json'
            )
            $json = $null
        }

        $response = $script:HttpClient.SendAsync(
            $request,
            [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead
        ).GetAwaiter().GetResult()

        $headers = @{}
        foreach ($header in $response.Headers) {
            $headers[$header.Key] = [string]::Join(',', [string[]]$header.Value)
        }
        foreach ($header in $response.Content.Headers) {
            $headers[$header.Key] = [string]::Join(',', [string[]]$header.Value)
        }

        $contentLength = -1
        if ($null -ne $response.Content.Headers.ContentLength) {
            $contentLength = [long]$response.Content.Headers.ContentLength
        }
        $responseStream = $response.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
        $bounded = Read-BoundedResponseText -Stream $responseStream -ContentLength $contentLength -MaxResponseBytes $MaxResponseBytes

        if ($bounded.TooLarge) {
            return [pscustomobject]@{
                StatusCode = [int]$response.StatusCode
                Data = $null
                Headers = $headers
                TransportError = $false
                ResponseTooLarge = $true
                ResponseLimitBytes = $MaxResponseBytes
            }
        }

        $responseText = $bounded.Text
        if (-not [string]::IsNullOrWhiteSpace($responseText)) {
            try {
                $data = ConvertFrom-Json -InputObject $responseText -ErrorAction Stop
            }
            catch {
                $data = $null
            }
        }

        return [pscustomobject]@{
            StatusCode = [int]$response.StatusCode
            Data = $data
            Headers = $headers
            TransportError = $false
            ResponseTooLarge = $false
            ResponseLimitBytes = $MaxResponseBytes
        }
    }
    catch {
        return [pscustomobject]@{
            StatusCode = 0
            Data = $null
            Headers = @{}
            TransportError = $true
            ResponseTooLarge = $false
            ResponseLimitBytes = $MaxResponseBytes
        }
    }
    finally {
        $json = $null
        $responseText = $null
        $data = $null
        if ($null -ne $responseStream) {
            $responseStream.Dispose()
        }
        if ($null -ne $response) {
            $response.Dispose()
        }
        if ($null -ne $request) {
            $request.Dispose()
        }
    }
}

function Get-SafeApiDetail {
    param([object]$Result)

    if ($null -ne $Result.Data -and
        $Result.Data.PSObject.Properties.Name -contains 'detail' -and
        -not [string]::IsNullOrWhiteSpace([string]$Result.Data.detail)) {
        return [string]$Result.Data.detail
    }
    return 'The backend returned no safe diagnostic detail.'
}

function Assert-LilaSuccess {
    param(
        [Parameter(Mandatory = $true)][object]$Result,
        [Parameter(Mandatory = $true)][string]$Operation
    )

    if ($Result.TransportError) {
        throw "$Operation could not reach the HTTPS backend. No request body or credentials were printed."
    }
    if ($Result.ResponseTooLarge) {
        throw "$Operation response exceeded the $($Result.ResponseLimitBytes)-byte safety limit; the body was not parsed."
    }
    if ($Result.StatusCode -lt 200 -or $Result.StatusCode -ge 300) {
        $detail = Get-SafeApiDetail -Result $Result
        throw "$Operation failed (HTTP $($Result.StatusCode)): $detail"
    }
    if ($null -eq $Result.Data) {
        throw "$Operation returned an empty or invalid JSON response."
    }
    return $Result.Data
}

function Get-TurnStatus {
    param(
        [Parameter(Mandatory = $true)][string]$ConversationId,
        [Parameter(Mandatory = $true)][string]$IdempotencyKey
    )

    $path = '/v1/lila/conversations/' + $ConversationId +
        '/turns/' + $IdempotencyKey
    $result = Invoke-LilaApi -Method GET -Path $path

    if ($result.TransportError -or $result.ResponseTooLarge) {
        return [pscustomobject]@{ Kind = 'unavailable'; Response = $null }
    }
    if ($result.StatusCode -eq 404) {
        return [pscustomobject]@{ Kind = 'not_found'; Response = $null }
    }
    if ($result.StatusCode -ne 200 -or $null -eq $result.Data) {
        return [pscustomobject]@{ Kind = 'unavailable'; Response = $null }
    }
    if ($result.Data.status -eq 'completed' -and $null -ne $result.Data.response) {
        return [pscustomobject]@{ Kind = 'completed'; Response = $result.Data.response }
    }
    if ($result.Data.status -eq 'pending') {
        return [pscustomobject]@{ Kind = 'pending'; Response = $null }
    }
    return [pscustomobject]@{ Kind = 'unavailable'; Response = $null }
}

function Send-LilaTurnSafely {
    param(
        [Parameter(Mandatory = $true)][string]$ConversationId,
        [Parameter(Mandatory = $true)][string]$IdempotencyKey,
        [Parameter(Mandatory = $true)][string]$Text
    )

    $path = '/v1/lila/turns'
    $body = @{
        conversation_id = $ConversationId
        message = $Text
    }
    $headers = @{ 'Idempotency-Key' = $IdempotencyKey }
    $first = Invoke-LilaApi -Method POST -Path $path -Body $body -ExtraHeaders $headers

    if (-not $first.TransportError -and -not $first.ResponseTooLarge -and
        $first.StatusCode -ge 200 -and $first.StatusCode -lt 300) {
        return $first.Data
    }

    if ($first.ResponseTooLarge) {
        $status = Get-TurnStatus -ConversationId $ConversationId -IdempotencyKey $IdempotencyKey
        if ($status.Kind -eq 'completed') {
            return $status.Response
        }
        if ($status.Kind -eq 'pending') {
            throw 'The turn response exceeded the local size limit and the turn is still pending. Do not resend it.'
        }
        throw 'The turn response exceeded the local size limit and its outcome is not confirmed. Do not resend it.'
    }

    $retrySafe = $false
    $outcomeUnknown = $false
    if ($first.Headers.ContainsKey('X-Lila-Retry-Safe')) {
        $retrySafe = $first.Headers['X-Lila-Retry-Safe'] -eq 'true'
    }
    if ($first.Headers.ContainsKey('X-Lila-Outcome-Unknown')) {
        $outcomeUnknown = $first.Headers['X-Lila-Outcome-Unknown'] -eq 'true'
    }

    if ($first.TransportError -or $outcomeUnknown -or $retrySafe) {
        $status = Get-TurnStatus -ConversationId $ConversationId -IdempotencyKey $IdempotencyKey
        if ($status.Kind -eq 'completed') {
            return $status.Response
        }
        if ($status.Kind -eq 'pending') {
            throw 'The turn is still pending. Do not resend it; the provider may already be processing it.'
        }
        if ($status.Kind -eq 'unavailable') {
            throw 'The turn outcome could not be reconciled. Do not resend it until the status endpoint is reachable.'
        }
        if ($outcomeUnknown) {
            throw 'The provider outcome is unknown. The backend did not confirm a safe retry; do not resend.'
        }
        if ($retrySafe -or $first.TransportError) {
            $retry = Invoke-LilaApi -Method POST -Path $path -Body $body -ExtraHeaders $headers
            if (-not $retry.TransportError -and -not $retry.ResponseTooLarge -and
                $retry.StatusCode -ge 200 -and $retry.StatusCode -lt 300) {
                return $retry.Data
            }

            if ($retry.TransportError -or $retry.ResponseTooLarge -or
                $retry.StatusCode -eq 409 -or
                ($retry.Headers.ContainsKey('X-Lila-Outcome-Unknown') -and
                 $retry.Headers['X-Lila-Outcome-Unknown'] -eq 'true')) {
                $afterRetry = Get-TurnStatus -ConversationId $ConversationId -IdempotencyKey $IdempotencyKey
                if ($afterRetry.Kind -eq 'completed') {
                    return $afterRetry.Response
                }
                if ($afterRetry.Kind -eq 'pending') {
                    throw 'The retried turn is pending. Do not send another generation request.'
                }
                throw 'The retry result is not confirmed. Do not send another generation request.'
            }
            $detail = Get-SafeApiDetail -Result $retry
            throw "The safe retry failed (HTTP $($retry.StatusCode)): $detail"
        }
    }

    $detail = Get-SafeApiDetail -Result $first
    throw "The Lila turn failed (HTTP $($first.StatusCode)): $detail"
}

function ConvertTo-EncodedPathSegment {
    param([Parameter(Mandatory = $true)][string]$Value)
    return [Uri]::EscapeDataString($Value)
}

# Verify the deterministic ID algorithm against a fixed vector before using credentials.
Test-ConversationIdDerivation
Test-BoundedResponseRead
if ($SelfTest) {
    Write-Host 'Galaxy Android conversation-ID compatibility: PASS'
    Write-Host 'Bounded HTTP response reader: PASS'
    exit 0
}

if ([string]::IsNullOrWhiteSpace($BackendBaseUrl)) {
    $BackendBaseUrl = Read-Host 'Lila backend HTTPS base URL (for example https://lila.example.com)'
}
try {
    $backendUri = [Uri]::new($BackendBaseUrl)
}
catch {
    throw 'BackendBaseUrl must be a valid absolute HTTPS URL.'
}
if ($backendUri.Scheme -ne 'https' -or
    -not [string]::IsNullOrWhiteSpace($backendUri.UserInfo) -or
    -not [string]::IsNullOrWhiteSpace($backendUri.Query) -or
    -not [string]::IsNullOrWhiteSpace($backendUri.Fragment) -or
    $backendUri.AbsolutePath.Trim('/') -ne '') {
    throw 'BackendBaseUrl must be an HTTPS origin without credentials, query, fragment, or path.'
}
$script:BackendBaseUrl = $backendUri.AbsoluteUri.TrimEnd('/')

if ([string]::IsNullOrWhiteSpace($ProviderBaseUrl)) {
    throw 'ProviderBaseUrl is required.'
}
try {
    $providerUri = [Uri]::new($ProviderBaseUrl)
}
catch {
    throw 'ProviderBaseUrl must be a valid absolute HTTP(S) URL.'
}
if (-not $providerUri.IsAbsoluteUri -or
    $providerUri.Scheme -notin @('http', 'https') -or
    -not [string]::IsNullOrWhiteSpace($providerUri.UserInfo) -or
    -not [string]::IsNullOrWhiteSpace($providerUri.Query) -or
    -not [string]::IsNullOrWhiteSpace($providerUri.Fragment)) {
    throw 'ProviderBaseUrl must be an absolute HTTP(S) URL without credentials, query, or fragment.'
}
if ($ProviderType -eq 'cloud' -and $providerUri.Scheme -ne 'https') {
    throw 'Cloud provider endpoints must use HTTPS.'
}
if ([string]::IsNullOrWhiteSpace($Message) -or $Message.Length -gt 12000) {
    throw 'Message must contain 1 to 12000 characters.'
}

[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
Add-Type -AssemblyName System.Net.Http
$handler = [System.Net.Http.HttpClientHandler]::new()
$handler.AllowAutoRedirect = $false
$script:HttpClient = [System.Net.Http.HttpClient]::new($handler)
$script:HttpClient.Timeout = [TimeSpan]::FromSeconds(160)
$script:AccessToken = $null
$providerApiKey = $null

try {
    $script:AccessToken = Read-SecretText -Prompt 'Paste the Supabase access token for the same account used in Galaxy'
    if ([string]::IsNullOrWhiteSpace($script:AccessToken)) {
        throw 'A Supabase access token is required.'
    }

    # The subject is read only to derive Galaxy's stable conversation ID.
    # The backend independently validates the JWT signature, issuer, and audience.
    $ownerId = Get-JwtSubject -Token $script:AccessToken
    $ownerKey = Get-OwnerPartitionKey -OwnerId $ownerId
    $conversationId = Get-StableLilaConversationId -OwnerKey $ownerKey -Character $CharacterId
    $ownerId = $null
    $ownerKey = $null

    $ready = Invoke-LilaApi -Method GET -Path '/ready'
    [void](Assert-LilaSuccess -Result $ready -Operation 'Backend readiness check')

    $providersResult = Invoke-LilaApi -Method GET -Path '/v1/lila/providers'
    $providersData = Assert-LilaSuccess -Result $providersResult -Operation 'Provider catalog read'
    $existing = @($providersData.providers | Where-Object { $_.provider_id -eq $ProviderId })
    if ($existing.Count -gt 1) {
        throw 'The provider catalog returned duplicate identifiers; no changes were made.'
    }
    if ($existing.Count -eq 1 -and -not $UpdateExisting) {
        throw "Provider '$ProviderId' already exists. Choose another ProviderId or rerun with -UpdateExisting."
    }

    $selectionPath = '/v1/lila/conversations/' + $conversationId + '/model'
    $oldSelectionResult = Invoke-LilaApi -Method GET -Path $selectionPath
    $oldSelection = Assert-LilaSuccess -Result $oldSelectionResult -Operation 'Current conversation model read'

    Write-Host ''
    Write-Host 'Live Lila validation plan'
    Write-Host "  Backend:       $script:BackendBaseUrl"
    Write-Host "  Provider type: $ProviderType"
    Write-Host "  Provider URL:  $ProviderBaseUrl"
    Write-Host "  Provider ID:   $ProviderId"
    Write-Host "  Character ID:  $CharacterId"
    Write-Host "  Conversation:  $conversationId"
    if ($oldSelection.selected) {
        Write-Host "  Previous model: $($oldSelection.provider_id) / $($oldSelection.model_id)"
    }
    else {
        Write-Host '  Previous model: none'
    }
    Write-Host ''
    Write-Host 'This will create or update the provider, select it for this shared conversation,'
    Write-Host 'and add one real test message to the Galaxy conversation history.'
    $confirmation = Read-Host 'Continue? (y/N)'
    if ($confirmation -notmatch '^(y|yes)$') {
        Write-Host 'Cancelled. No provider or conversation changes were made.'
        return
    }

    $apiKeyChoice = Read-Host 'Does this provider require an API key? (y/N)'
    if ($apiKeyChoice -match '^(y|yes)$') {
        $providerApiKey = Read-SecretText -Prompt 'Provider API key'
        if ([string]::IsNullOrWhiteSpace($providerApiKey)) {
            throw 'The provider API key was left blank.'
        }
    }

    $seedModelId = if (-not [string]::IsNullOrWhiteSpace($ModelId)) {
        $ModelId
    }
    elseif ($existing.Count -eq 1) {
        [string]$existing[0].model_id
    }
    else {
        'discovery-pending'
    }

    $providerBody = @{
        provider_type = $ProviderType
        base_url = $ProviderBaseUrl
        model_id = $seedModelId
        timeout_seconds = $TimeoutSeconds
        capabilities = @('chat')
        enabled = $true
    }
    if ($null -ne $providerApiKey) {
        $providerBody.api_key = $providerApiKey
    }

    if ($existing.Count -eq 1) {
        $providerPath = '/v1/lila/providers/' + (ConvertTo-EncodedPathSegment -Value $ProviderId)
        $saveResult = Invoke-LilaApi -Method PUT -Path $providerPath -Body $providerBody
        [void](Assert-LilaSuccess -Result $saveResult -Operation 'Provider update')
    }
    else {
        $createBody = $providerBody.Clone()
        $createBody.provider_id = $ProviderId
        $saveResult = Invoke-LilaApi -Method POST -Path '/v1/lila/providers' -Body $createBody
        [void](Assert-LilaSuccess -Result $saveResult -Operation 'Provider creation')
    }

    $providerPath = '/v1/lila/providers/' + (ConvertTo-EncodedPathSegment -Value $ProviderId)
    $probeResult = Invoke-LilaApi -Method POST -Path ($providerPath + '/probe')
    $probe = Assert-LilaSuccess -Result $probeResult -Operation 'Non-generative provider probe'
    if (-not $probe.connection_reachable) {
        throw 'The backend could not reach the configured model endpoint.'
    }

    if ([string]::IsNullOrWhiteSpace($ModelId)) {
        if ($probe.model_discovery_supported -and @($probe.model_ids).Count -gt 0) {
            Write-Host ''
            Write-Host 'Models discovered by the backend:'
            @($probe.model_ids) | ForEach-Object { Write-Host "  $_" }
            $ModelId = Read-Host 'Enter the exact model ID to use'
        }
        else {
            $ModelId = Read-Host 'No usable model list was returned; enter the exact model ID configured in the runtime'
        }
    }
    if ([string]::IsNullOrWhiteSpace($ModelId) -or $ModelId.Length -gt 256) {
        throw 'A valid model ID is required.'
    }
    if ($probe.model_discovery_supported -and
        @($probe.model_ids) -notcontains $ModelId) {
        throw 'The selected model ID was not returned by the provider catalog. No turn was sent.'
    }

    $providerBody.model_id = $ModelId
    if ($null -ne $providerApiKey) {
        $providerBody.api_key = $providerApiKey
    }
    $updateFinalResult = Invoke-LilaApi -Method PUT -Path $providerPath -Body $providerBody
    [void](Assert-LilaSuccess -Result $updateFinalResult -Operation 'Final provider model update')

    $selectionBody = @{
        provider_id = $ProviderId
        model_id = $ModelId
    }
    $setSelectionResult = Invoke-LilaApi -Method PUT -Path $selectionPath -Body $selectionBody
    [void](Assert-LilaSuccess -Result $setSelectionResult -Operation 'Conversation model selection')

    $verifySelectionResult = Invoke-LilaApi -Method GET -Path $selectionPath
    $verifiedSelection = Assert-LilaSuccess -Result $verifySelectionResult -Operation 'Conversation model selection verification'
    if (-not $verifiedSelection.selected -or
        $verifiedSelection.provider_id -ne $ProviderId -or
        $verifiedSelection.model_id -ne $ModelId) {
        throw 'The backend did not persist the expected provider/model selection.'
    }

    $idempotencyKey = [Guid]::NewGuid().ToString()
    $turn = Send-LilaTurnSafely -ConversationId $conversationId -IdempotencyKey $idempotencyKey -Text $Message
    if ($null -eq $turn -or
        $turn.conversation_id -ne $conversationId -or
        $turn.provider_id -ne $ProviderId -or
        $turn.model_id -ne $ModelId -or
        [string]::IsNullOrWhiteSpace([string]$turn.text)) {
        throw 'The completed turn did not report the expected conversation, provider, and model.'
    }

    $historyPath = '/v1/lila/conversations/' + $conversationId + '/messages?limit=20'
    $historyResult = Invoke-LilaApi -Method GET -Path $historyPath
    $history = Assert-LilaSuccess -Result $historyResult -Operation 'Durable conversation history read'
    $assistantRecord = @($history.messages | Where-Object {
        $_.role -eq 'assistant' -and $_.content -eq $turn.text
    }) | Select-Object -Last 1
    if ($null -eq $assistantRecord) {
        throw 'The assistant response was returned but was not found in durable conversation history.'
    }

    Write-Host ''
    Write-Host 'LIVE BACKEND E2E: PASS'
    Write-Host "  Shared conversation: $conversationId"
    Write-Host "  Active provider:     $ProviderId"
    Write-Host "  Active model:        $ModelId"
    Write-Host '  Provider probe:      PASS'
    Write-Host '  Model selection:     PASS'
    Write-Host '  Real model turn:     PASS'
    Write-Host '  Durable history:     PASS'
    Write-Host ''
    Write-Host 'Lila response:'
    Write-Host ([string]$turn.text)
    Write-Host ''
    Write-Host 'Next: open or restart Galaxy on the same Supabase account and the same character.'
    Write-Host 'The test turn was written to the same deterministic conversation ID used by Android.'
    Write-Host 'This script does not install the APK or claim physical-device verification.'
}
finally {
    $script:AccessToken = $null
    $providerApiKey = $null
    $providerBody = $null
    $createBody = $null
    $ownerId = $null
    $ownerKey = $null
    if ($null -ne $script:HttpClient) {
        $script:HttpClient.Dispose()
        $script:HttpClient = $null
    }
    if ($null -ne $handler) {
        $handler.Dispose()
        $handler = $null
    }
}
