#requires -Version 5.1
<#
.SYNOPSIS
Runs one authenticated live ComfyUI generation through Lila Core.

.DESCRIPTION
Use after ComfyUI has been configured and tested in Galaxy Android.
The script derives the same owner/character conversation UUID used by Galaxy,
submits one idempotent image job, waits for its terminal state, downloads the
Core-stored PNG, verifies metadata/SHA-256/dimensions, and confirms the job is
present in the durable conversation history.

When -RequiredCheckpointName is supplied, the script also validates the saved
built-in SDXL API graph wiring and requires the exact checkpoint to appear in
ComfyUI's authenticated checkpoint discovery response before submitting a job.

The bearer token is read from LILA_ACCESS_TOKEN or requested as a SecureString.
It is never written to disk or output. TLS certificate validation remains enabled;
redirects are disabled. Plain HTTP is accepted only for loopback Core URLs.

The PNG is kept in memory unless -OutputPath is explicitly supplied.
A timed-out job is NOT cancelled or resubmitted; its job ID is reported so the
existing history can be checked without risking a duplicate generation.
#>
[CmdletBinding()]
param(
    [string]$BackendBaseUrl,
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$')]
    [string]$CharacterId = 'galaxy.default',
    [ValidateLength(1, 12000)]
    [string]$Prompt = 'A clean test portrait of an elegant silver-haired fantasy heroine, neutral studio background.',
    [ValidateLength(0, 4000)]
    [string]$NegativePrompt = '',
    [ValidateRange(64, 2048)]
    [int]$Width = 512,
    [ValidateRange(64, 2048)]
    [int]$Height = 512,
    [long]$Seed = -1,
    [ValidateRange(1, 30)]
    [int]$PollIntervalSeconds = 3,
    [ValidateRange(10, 1800)]
    [int]$MaxWaitSeconds = 900,
    [string]$OutputPath,
    [string]$RequiredWorkflowId,
    [ValidateLength(1, 512)]
    [string]$RequiredCheckpointName,
    [switch]$SelfTest
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-ValidatedBackendUri {
    param([Parameter(Mandatory = $true)][string]$Value)

    $parsed = $null
    try {
        $parsed = [System.Uri]::new($Value.TrimEnd('/') + '/')
    }
    catch {
        throw 'BackendBaseUrl must be an absolute HTTPS URL or loopback HTTP URL.'
    }

    if (-not $parsed.IsAbsoluteUri -or
        $parsed.UserInfo -or $parsed.Query -or $parsed.Fragment -or
        ($parsed.Scheme -ne 'https' -and -not ($parsed.Scheme -eq 'http' -and $parsed.IsLoopback))) {
        throw 'BackendBaseUrl must use HTTPS; HTTP is allowed only for loopback.'
    }

    return $parsed
}

function Get-AccessToken {
    $value = [string]$env:LILA_ACCESS_TOKEN
    if (-not [string]::IsNullOrWhiteSpace($value)) {
        return $value.Trim()
    }

    $secure = Read-Host -Prompt 'Supabase access token (input is hidden)' -AsSecureString
    if ($secure.Length -eq 0) {
        $secure.Dispose()
        throw 'An access token is required.'
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

function Get-LilaConversationId {
    param(
        [Parameter(Mandatory = $true)][string]$Token,
        [Parameter(Mandatory = $true)][string]$Character
    )

    $parts = $Token.Split('.')
    if ($parts.Length -ne 3) {
        throw 'The access token is not a JWT.'
    }

    $payload = $parts[1].Replace('-', '+').Replace('_', '/')
    switch ($payload.Length % 4) {
        0 { }
        2 { $payload += '==' }
        3 { $payload += '=' }
        default { throw 'The access token payload is invalid.' }
    }

    $payloadBytes = [Convert]::FromBase64String($payload)
    $ownerBytes = $null
    $ownerDigest = $null
    $ownerPrefix = New-Object byte[] 16
    $materialBytes = $null
    $conversationDigest = $null
    $uuidBytes = New-Object byte[] 16
    $sha = [Security.Cryptography.SHA256]::Create()
    $claimsJson = $null
    $claims = $null
    try {
        $claimsJson = [Text.Encoding]::UTF8.GetString($payloadBytes)
        $claims = ConvertFrom-Json -InputObject $claimsJson -ErrorAction Stop
        $subject = [string]$claims.sub
        if ([string]::IsNullOrWhiteSpace($subject) -or $subject.Length -gt 128) {
            throw 'The access token has no valid subject claim.'
        }

        $ownerBytes = [Text.Encoding]::UTF8.GetBytes($subject)
        $ownerDigest = $sha.ComputeHash($ownerBytes)
        [Array]::Copy($ownerDigest, $ownerPrefix, 16)
        $ownerKey = [BitConverter]::ToString($ownerPrefix).Replace('-', '').ToLowerInvariant()

        $material = 'lila-conversation-v1' + [char]0 + $ownerKey + [char]0 + $Character
        $materialBytes = [Text.Encoding]::UTF8.GetBytes($material)
        $conversationDigest = $sha.ComputeHash($materialBytes)
        [Array]::Copy($conversationDigest, $uuidBytes, 16)
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
        [Array]::Clear($payloadBytes, 0, $payloadBytes.Length)
        [Array]::Clear($ownerPrefix, 0, $ownerPrefix.Length)
        [Array]::Clear($uuidBytes, 0, $uuidBytes.Length)
        if ($null -ne $ownerBytes) { [Array]::Clear($ownerBytes, 0, $ownerBytes.Length) }
        if ($null -ne $ownerDigest) { [Array]::Clear($ownerDigest, 0, $ownerDigest.Length) }
        if ($null -ne $materialBytes) { [Array]::Clear($materialBytes, 0, $materialBytes.Length) }
        if ($null -ne $conversationDigest) { [Array]::Clear($conversationDigest, 0, $conversationDigest.Length) }
        $payload = $null
        $claimsJson = $null
        $claims = $null
    }
}

function Read-BoundedContent {
    param(
        [Parameter(Mandatory = $true)][System.Net.Http.HttpResponseMessage]$Response,
        [Parameter(Mandatory = $true)][ValidateRange(1, 26214400)][int]$Limit
    )

    $contentLength = $Response.Content.Headers.ContentLength
    if ($null -ne $contentLength -and [long]$contentLength -gt $Limit) {
        throw 'Lila Core response exceeds the configured size limit.'
    }

    $stream = $Response.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
    $memory = [System.IO.MemoryStream]::new()
    $buffer = New-Object byte[] 8192
    try {
        while ($true) {
            $remaining = [long]$Limit - $memory.Length
            $readLimit = [int][Math]::Min([long]$buffer.Length, $remaining + 1)
            $read = $stream.Read($buffer, 0, $readLimit)
            if ($read -le 0) { break }
            if ($read -gt $remaining) {
                throw 'Lila Core response exceeds the configured size limit.'
            }
            $memory.Write($buffer, 0, $read)
        }
        return $memory.ToArray()
    }
    finally {
        [Array]::Clear($buffer, 0, $buffer.Length)
        $internalBuffer = [byte[]]$memory.GetBuffer()
        if ($internalBuffer.Length -gt 0) {
            [Array]::Clear($internalBuffer, 0, $internalBuffer.Length)
        }
        $memory.Dispose()
        $stream.Dispose()
    }
}

function Invoke-CoreJson {
    param(
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][string]$Path,
        [object]$Body = $null,
        [string]$IdempotencyKey,
        [ValidateRange(1, 1048576)][int]$MaxResponseBytes = 1048576
    )

    $request = [System.Net.Http.HttpRequestMessage]::new(
        [System.Net.Http.HttpMethod]::new($Method),
        [System.Uri]::new($script:BackendUri, $Path.TrimStart('/'))
    )
    $bodyBytes = $null
    $response = $null
    $cancellation = [System.Threading.CancellationTokenSource]::new([TimeSpan]::FromSeconds(60))
    try {
        $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $script:AccessToken)
        $request.Headers.Accept.ParseAdd('application/json')
        if (-not [string]::IsNullOrWhiteSpace($IdempotencyKey)) {
            $request.Headers.Add('Idempotency-Key', $IdempotencyKey)
        }
        if ($null -ne $Body) {
            $json = ConvertTo-Json -InputObject $Body -Depth 20 -Compress
            $bodyBytes = [Text.Encoding]::UTF8.GetBytes($json)
            $json = $null
            $content = [System.Net.Http.ByteArrayContent]::new($bodyBytes)
            $content.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::new('application/json')
            $request.Content = $content
        }

        try {
            $response = $script:HttpClient.SendAsync(
                $request,
                [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead,
                $cancellation.Token
            ).GetAwaiter().GetResult()
        }
        catch {
            throw 'Could not complete the authenticated request to Lila Core.'
        }

        if (-not $response.IsSuccessStatusCode) {
            throw ('Lila Core returned HTTP {0} for {1}.' -f [int]$response.StatusCode, $Path)
        }

        $bytes = Read-BoundedContent -Response $response -Limit $MaxResponseBytes
        try {
            $text = [Text.Encoding]::UTF8.GetString($bytes)
            try {
                return ConvertFrom-Json -InputObject $text -ErrorAction Stop
            }
            catch {
                throw ('Lila Core returned invalid JSON for {0}.' -f $Path)
            }
            finally {
                $text = $null
            }
        }
        finally {
            [Array]::Clear($bytes, 0, $bytes.Length)
        }
    }
    finally {
        if ($null -ne $response) { $response.Dispose() }
        $request.Dispose()
        $cancellation.Dispose()
        if ($null -ne $bodyBytes) { [Array]::Clear($bodyBytes, 0, $bodyBytes.Length) }
    }
}

function Invoke-CoreBinary {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [ValidateRange(1, 26214400)][int]$MaxResponseBytes = 26214400
    )

    $request = [System.Net.Http.HttpRequestMessage]::new(
        [System.Net.Http.HttpMethod]::Get,
        [System.Uri]::new($script:BackendUri, $Path.TrimStart('/'))
    )
    $response = $null
    $cancellation = [System.Threading.CancellationTokenSource]::new([TimeSpan]::FromSeconds(60))
    try {
        $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $script:AccessToken)
        $request.Headers.Accept.ParseAdd('image/png')
        try {
            $response = $script:HttpClient.SendAsync(
                $request,
                [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead,
                $cancellation.Token
            ).GetAwaiter().GetResult()
        }
        catch {
            throw 'Could not download the authenticated image asset from Lila Core.'
        }

        if (-not $response.IsSuccessStatusCode) {
            throw ('Lila Core returned HTTP {0} for the image asset.' -f [int]$response.StatusCode)
        }
        $contentType = [string]$response.Content.Headers.ContentType.MediaType
        if ($contentType -ne 'image/png') {
            throw 'Lila Core returned an unsupported image content type.'
        }
        return Read-BoundedContent -Response $response -Limit $MaxResponseBytes
    }
    finally {
        if ($null -ne $response) { $response.Dispose() }
        $request.Dispose()
        $cancellation.Dispose()
    }
}

function Get-PngDimensions {
    param([Parameter(Mandatory = $true)][byte[]]$Bytes)

    if ($Bytes.Length -lt 24) { throw 'The returned PNG is shorter than its required header.' }
    $signature = [byte[]](137, 80, 78, 71, 13, 10, 26, 10)
    for ($index = 0; $index -lt 8; $index++) {
        if ($Bytes[$index] -ne $signature[$index]) { throw 'The returned asset is not a PNG.' }
    }
    if ($Bytes[12] -ne 73 -or $Bytes[13] -ne 72 -or $Bytes[14] -ne 68 -or $Bytes[15] -ne 82) {
        throw 'The returned PNG does not start with an IHDR chunk.'
    }

    $width = ([long]$Bytes[16] -shl 24) -bor ([long]$Bytes[17] -shl 16) -bor
        ([long]$Bytes[18] -shl 8) -bor [long]$Bytes[19]
    $height = ([long]$Bytes[20] -shl 24) -bor ([long]$Bytes[21] -shl 16) -bor
        ([long]$Bytes[22] -shl 8) -bor [long]$Bytes[23]
    if ($width -lt 1 -or $width -gt 8192 -or $height -lt 1 -or $height -gt 8192) {
        throw 'The returned PNG dimensions are outside the supported range.'
    }
    return [pscustomobject]@{ Width = [int]$width; Height = [int]$height }
}

function Get-Sha256Hex {
    param([Parameter(Mandatory = $true)][byte[]]$Bytes)

    $sha = [Security.Cryptography.SHA256]::Create()
    $digest = $null
    try {
        $digest = $sha.ComputeHash($Bytes)
        return [BitConverter]::ToString($digest).Replace('-', '').ToLowerInvariant()
    }
    finally {
        $sha.Dispose()
        if ($null -ne $digest) { [Array]::Clear($digest, 0, $digest.Length) }
    }
}

function Assert-RequiredWorkflowId {
    param(
        [string]$ActualWorkflowId,
        [string]$RequiredWorkflowId
    )

    if (-not [string]::IsNullOrWhiteSpace($RequiredWorkflowId) -and
        $ActualWorkflowId -cne $RequiredWorkflowId) {
        throw 'Configured ComfyUI workflow ID does not match RequiredWorkflowId; no image job was submitted.'
    }
}


function Get-RequiredSdxlNode {
    param(
        [Parameter(Mandatory = $true)][object]$Graph,
        [Parameter(Mandatory = $true)][string]$NodeId,
        [Parameter(Mandatory = $true)][string]$ClassType
    )

    $property = $Graph.PSObject.Properties[$NodeId]
    if ($null -eq $property) {
        throw 'Configured SDXL graph is missing a required node.'
    }
    $node = $property.Value
    if ($null -eq $node -or [string]$node.class_type -cne $ClassType -or $null -eq $node.inputs) {
        throw 'Configured SDXL graph contains an unexpected node type or missing inputs.'
    }
    return $node
}

function Assert-SdxlNodeLink {
    param(
        [Parameter(Mandatory = $true)][object]$Node,
        [Parameter(Mandatory = $true)][string]$InputName,
        [Parameter(Mandatory = $true)][string]$ExpectedNodeId,
        [Parameter(Mandatory = $true)][int]$ExpectedOutput
    )

    $property = $Node.inputs.PSObject.Properties[$InputName]
    if ($null -eq $property) {
        throw 'Configured SDXL graph is missing a required connection.'
    }
    $link = @($property.Value)
    if ($link.Count -ne 2 -or
        [string]$link[0] -cne $ExpectedNodeId -or
        [int]$link[1] -ne $ExpectedOutput) {
        throw 'Configured SDXL graph has an unexpected node connection.'
    }
}

function Assert-RequiredSdxlWorkflow {
    param(
        [Parameter(Mandatory = $true)][object]$Workflow,
        [Parameter(Mandatory = $true)][ValidateLength(1, 512)][string]$RequiredCheckpointName
    )

    $checkpointName = $RequiredCheckpointName.Trim()
    if ([string]::IsNullOrWhiteSpace($checkpointName) -or
        $checkpointName.StartsWith('/') -or
        $checkpointName.Contains('\') -or
        $checkpointName.Contains(':')) {
        throw 'Required checkpoint name must be a safe relative ComfyUI model path.'
    }
    foreach ($character in $checkpointName.ToCharArray()) {
        if ([int]$character -lt 32 -or [int]$character -eq 127) {
            throw 'Required checkpoint name contains a control character.'
        }
    }
    foreach ($segment in $checkpointName.Split('/')) {
        if ([string]::IsNullOrWhiteSpace($segment) -or $segment -eq '.' -or $segment -eq '..') {
            throw 'Required checkpoint name contains an unsafe path segment.'
        }
    }

    if ([string]$Workflow.workflow_id -cne 'lila.sdxl.v1') {
        throw 'Required checkpoint validation only supports the built-in lila.sdxl.v1 workflow.'
    }
    $workflowJson = [string]$Workflow.workflow_json
    if ([string]::IsNullOrWhiteSpace($workflowJson) -or $workflowJson.Length -gt 2097152) {
        throw 'Saved SDXL workflow JSON is missing or exceeds the configured limit.'
    }
    try {
        $graph = ConvertFrom-Json -InputObject $workflowJson -ErrorAction Stop
    }
    catch {
        throw 'Saved SDXL workflow JSON is invalid.'
    }

    $checkpoint = Get-RequiredSdxlNode -Graph $graph -NodeId '4' -ClassType 'CheckpointLoaderSimple'
    if ([string]$checkpoint.inputs.ckpt_name -cne $checkpointName) {
        throw 'Saved SDXL workflow does not use the required checkpoint filename.'
    }
    $positive = Get-RequiredSdxlNode -Graph $graph -NodeId '5' -ClassType 'CLIPTextEncode'
    $negative = Get-RequiredSdxlNode -Graph $graph -NodeId '6' -ClassType 'CLIPTextEncode'
    $latent = Get-RequiredSdxlNode -Graph $graph -NodeId '7' -ClassType 'EmptyLatentImage'
    $sampler = Get-RequiredSdxlNode -Graph $graph -NodeId '3' -ClassType 'KSampler'
    $decode = Get-RequiredSdxlNode -Graph $graph -NodeId '8' -ClassType 'VAEDecode'
    $save = Get-RequiredSdxlNode -Graph $graph -NodeId '9' -ClassType 'SaveImage'

    foreach ($entry in @(
        @{ Node = $positive; Input = 'text' },
        @{ Node = $negative; Input = 'text' },
        @{ Node = $latent; Input = 'width' },
        @{ Node = $latent; Input = 'height' },
        @{ Node = $sampler; Input = 'seed' },
        @{ Node = $sampler; Input = 'steps' },
        @{ Node = $sampler; Input = 'cfg' },
        @{ Node = $sampler; Input = 'sampler_name' },
        @{ Node = $sampler; Input = 'scheduler' },
        @{ Node = $save; Input = 'filename_prefix' }
    )) {
        if ($null -eq $entry.Node.inputs.PSObject.Properties[$entry.Input]) {
            throw 'Saved SDXL workflow is missing a required node input.'
        }
    }

    if ([int]$latent.inputs.width -ne 1024 -or
        [int]$latent.inputs.height -ne 1024 -or
        [int]$latent.inputs.batch_size -ne 1 -or
        [int]$sampler.inputs.seed -ne 0 -or
        [int]$sampler.inputs.steps -ne 30 -or
        [double]$sampler.inputs.cfg -ne 5.5 -or
        [string]$sampler.inputs.sampler_name -cne 'dpmpp_2m' -or
        [string]$sampler.inputs.scheduler -cne 'karras' -or
        [double]$sampler.inputs.denoise -ne 1.0 -or
        [string]$save.inputs.filename_prefix -cne 'lila') {
        throw 'Saved SDXL workflow does not match the built-in Galaxy SDXL preset.'
    }

    Assert-SdxlNodeLink -Node $positive -InputName 'clip' -ExpectedNodeId '4' -ExpectedOutput 1
    Assert-SdxlNodeLink -Node $negative -InputName 'clip' -ExpectedNodeId '4' -ExpectedOutput 1
    Assert-SdxlNodeLink -Node $sampler -InputName 'model' -ExpectedNodeId '4' -ExpectedOutput 0
    Assert-SdxlNodeLink -Node $sampler -InputName 'positive' -ExpectedNodeId '5' -ExpectedOutput 0
    Assert-SdxlNodeLink -Node $sampler -InputName 'negative' -ExpectedNodeId '6' -ExpectedOutput 0
    Assert-SdxlNodeLink -Node $sampler -InputName 'latent_image' -ExpectedNodeId '7' -ExpectedOutput 0
    Assert-SdxlNodeLink -Node $decode -InputName 'samples' -ExpectedNodeId '3' -ExpectedOutput 0
    Assert-SdxlNodeLink -Node $decode -InputName 'vae' -ExpectedNodeId '4' -ExpectedOutput 2
    Assert-SdxlNodeLink -Node $save -InputName 'images' -ExpectedNodeId '8' -ExpectedOutput 0

    if ($null -eq $Workflow.positive_prompt -or
        [string]$Workflow.positive_prompt.node_id -cne '5' -or
        [string]$Workflow.positive_prompt.input_name -cne 'text' -or
        $null -eq $Workflow.negative_prompt -or
        [string]$Workflow.negative_prompt.node_id -cne '6' -or
        [string]$Workflow.negative_prompt.input_name -cne 'text' -or
        $null -eq $Workflow.width -or
        [string]$Workflow.width.node_id -cne '7' -or
        [string]$Workflow.width.input_name -cne 'width' -or
        $null -eq $Workflow.height -or
        [string]$Workflow.height.node_id -cne '7' -or
        [string]$Workflow.height.input_name -cne 'height' -or
        $null -eq $Workflow.seed -or
        [string]$Workflow.seed.node_id -cne '3' -or
        [string]$Workflow.seed.input_name -cne 'seed') {
        throw 'Saved SDXL workflow mappings do not match the built-in graph.'
    }
}

function Invoke-SelfTest {
    $https = Get-ValidatedBackendUri -Value 'https://example.invalid'
    if ($https.Scheme -ne 'https') { throw 'HTTPS URL validation failed.' }
    $loopback = Get-ValidatedBackendUri -Value 'http://127.0.0.1:8000'
    if (-not $loopback.IsLoopback) { throw 'Loopback URL validation failed.' }

    $rejected = $false
    try { Get-ValidatedBackendUri -Value 'http://example.invalid' | Out-Null }
    catch { $rejected = $true }
    if (-not $rejected) { throw 'Non-loopback HTTP was not rejected.' }

    Assert-RequiredWorkflowId -ActualWorkflowId 'lila.sdxl.v1' -RequiredWorkflowId 'lila.sdxl.v1'
    Assert-RequiredWorkflowId -ActualWorkflowId 'custom-v1' -RequiredWorkflowId ''
    $wrongWorkflowRejected = $false
    try {
        Assert-RequiredWorkflowId -ActualWorkflowId 'empty-smoke-v1' -RequiredWorkflowId 'lila.sdxl.v1'
    }
    catch {
        $wrongWorkflowRejected = $true
    }
    if (-not $wrongWorkflowRejected) {
        throw 'Required workflow ID guard accepted a different workflow.'
    }

    $sdxlGraph = [ordered]@{
        '4' = @{ class_type = 'CheckpointLoaderSimple'; inputs = @{ ckpt_name = 'sdxl.safetensors' } }
        '5' = @{ class_type = 'CLIPTextEncode'; inputs = @{ text = ''; clip = @('4', 1) } }
        '6' = @{ class_type = 'CLIPTextEncode'; inputs = @{ text = ''; clip = @('4', 1) } }
        '7' = @{ class_type = 'EmptyLatentImage'; inputs = @{ width = 1024; height = 1024; batch_size = 1 } }
        '3' = @{ class_type = 'KSampler'; inputs = @{
            seed = 0; steps = 30; cfg = 5.5; sampler_name = 'dpmpp_2m'; scheduler = 'karras'
            denoise = 1.0; model = @('4', 0); positive = @('5', 0); negative = @('6', 0); latent_image = @('7', 0)
        } }
        '8' = @{ class_type = 'VAEDecode'; inputs = @{ samples = @('3', 0); vae = @('4', 2) } }
        '9' = @{ class_type = 'SaveImage'; inputs = @{ images = @('8', 0); filename_prefix = 'lila' } }
    }
    $sdxlWorkflow = [pscustomobject]@{
        workflow_id = 'lila.sdxl.v1'
        workflow_json = ConvertTo-Json -InputObject $sdxlGraph -Depth 12 -Compress
        positive_prompt = [pscustomobject]@{ node_id = '5'; input_name = 'text' }
        negative_prompt = [pscustomobject]@{ node_id = '6'; input_name = 'text' }
        width = [pscustomobject]@{ node_id = '7'; input_name = 'width' }
        height = [pscustomobject]@{ node_id = '7'; input_name = 'height' }
        seed = [pscustomobject]@{ node_id = '3'; input_name = 'seed' }
    }
    Assert-RequiredSdxlWorkflow -Workflow $sdxlWorkflow -RequiredCheckpointName 'sdxl.safetensors'

    $wrongSamplerGraph = ConvertFrom-Json -InputObject $sdxlWorkflow.workflow_json
    $wrongSamplerGraph.'3'.inputs.cfg = 7.0
    $wrongSamplerWorkflow = [pscustomobject]@{
        workflow_id = $sdxlWorkflow.workflow_id
        workflow_json = ConvertTo-Json -InputObject $wrongSamplerGraph -Depth 12 -Compress
        positive_prompt = $sdxlWorkflow.positive_prompt
        negative_prompt = $sdxlWorkflow.negative_prompt
        width = $sdxlWorkflow.width
        height = $sdxlWorkflow.height
        seed = $sdxlWorkflow.seed
    }
    $wrongSamplerRejected = $false
    try {
        Assert-RequiredSdxlWorkflow -Workflow $wrongSamplerWorkflow -RequiredCheckpointName 'sdxl.safetensors'
    }
    catch {
        $wrongSamplerRejected = $true
    }
    if (-not $wrongSamplerRejected) {
        throw 'SDXL graph validation accepted a modified CFG preset.'
    }

    $wrongCheckpointRejected = $false
    try {
        Assert-RequiredSdxlWorkflow -Workflow $sdxlWorkflow -RequiredCheckpointName 'missing.safetensors'
    }
    catch {
        $wrongCheckpointRejected = $true
    }
    if (-not $wrongCheckpointRejected) {
        throw 'SDXL graph validation accepted a different checkpoint.'
    }

    $unsafeCheckpointRejected = $false
    try {
        Assert-RequiredSdxlWorkflow -Workflow $sdxlWorkflow -RequiredCheckpointName '../outside.safetensors'
    }
    catch {
        $unsafeCheckpointRejected = $true
    }
    if (-not $unsafeCheckpointRejected) {
        throw 'SDXL graph validation accepted an unsafe checkpoint path.'
    }

    $brokenWorkflow = [pscustomobject]@{
        workflow_id = $sdxlWorkflow.workflow_id
        workflow_json = $sdxlWorkflow.workflow_json.Replace('"images":["8",0]', '"images":["3",0]')
        positive_prompt = $sdxlWorkflow.positive_prompt
        negative_prompt = $sdxlWorkflow.negative_prompt
        width = $sdxlWorkflow.width
        height = $sdxlWorkflow.height
        seed = $sdxlWorkflow.seed
    }
    if ($brokenWorkflow.workflow_json -ceq $sdxlWorkflow.workflow_json) {
        throw 'SDXL graph self-test could not construct the broken output link.'
    }
    $brokenGraphRejected = $false
    try {
        Assert-RequiredSdxlWorkflow -Workflow $brokenWorkflow -RequiredCheckpointName 'sdxl.safetensors'
    }
    catch {
        $brokenGraphRejected = $true
    }
    if (-not $brokenGraphRejected) {
        throw 'SDXL graph validation accepted a disconnected output.'
    }

    $png = New-Object byte[] 24
    $signature = [byte[]](137, 80, 78, 71, 13, 10, 26, 10)
    [Array]::Copy($signature, $png, 8)
    $png[12] = 73; $png[13] = 72; $png[14] = 68; $png[15] = 82
    $png[19] = 64; $png[23] = 64
    try {
        $dimensions = Get-PngDimensions -Bytes $png
        if ($dimensions.Width -ne 64 -or $dimensions.Height -ne 64) {
            throw 'PNG dimension parsing failed.'
        }
    }
    finally {
        [Array]::Clear($png, 0, $png.Length)
    }

    Write-Output 'Required ComfyUI workflow ID guard: PASS'
    Write-Output 'Built-in SDXL graph/checkpoint validation: PASS'
    Write-Output 'Lila ComfyUI live-E2E script self-test passed.'
}

if ($SelfTest) {
    Invoke-SelfTest
    return
}

if ([string]::IsNullOrWhiteSpace($BackendBaseUrl)) {
    throw 'Pass -BackendBaseUrl, for example https://your-trusted-lila-core.example.'
}
if ([string]::IsNullOrWhiteSpace($Prompt) -or $Prompt.Length -gt 12000) {
    throw 'Prompt must contain 1 to 12000 characters.'
}

$script:BackendUri = Get-ValidatedBackendUri -Value $BackendBaseUrl
$script:AccessToken = Get-AccessToken
if ([string]::IsNullOrWhiteSpace($script:AccessToken)) {
    throw 'An access token is required.'
}
$script:HttpHandler = [System.Net.Http.HttpClientHandler]::new()
$script:HttpHandler.AllowAutoRedirect = $false
$script:HttpHandler.UseCookies = $false
$script:HttpClient = [System.Net.Http.HttpClient]::new($script:HttpHandler)
$script:HttpClient.Timeout = [System.Threading.Timeout]::InfiniteTimeSpan

$assetBytes = $null
try {
    $conversationId = Get-LilaConversationId -Token $script:AccessToken -Character $CharacterId

    $provider = Invoke-CoreJson -Method 'GET' -Path '/v1/lila/image-providers/comfyui'
    if (-not $provider.configured -or [string]::IsNullOrWhiteSpace([string]$provider.workflow_id)) {
        throw 'ComfyUI is not configured for this authenticated owner. Configure it in Galaxy first.'
    }
    Assert-RequiredWorkflowId -ActualWorkflowId ([string]$provider.workflow_id) -RequiredWorkflowId $RequiredWorkflowId

    if (-not [string]::IsNullOrWhiteSpace($RequiredCheckpointName)) {
        if ([string]$RequiredWorkflowId -cne 'lila.sdxl.v1') {
            throw 'Pass -RequiredWorkflowId lila.sdxl.v1 when requiring a checkpoint.'
        }
        $configuration = Invoke-CoreJson -Method 'GET' -Path '/v1/lila/image-providers/comfyui/config'
        if (-not $configuration.configured -or $null -eq $configuration.workflow) {
            throw 'Lila Core did not return the saved SDXL workflow configuration.'
        }
        Assert-RequiredSdxlWorkflow -Workflow $configuration.workflow -RequiredCheckpointName $RequiredCheckpointName
    }

    $probe = Invoke-CoreJson -Method 'POST' -Path '/v1/lila/image-providers/comfyui/test'
    if (-not $probe.connection_reachable -or $probe.workflow_id -ne $provider.workflow_id) {
        throw 'Lila Core could not confirm the configured ComfyUI workflow.'
    }
    if (-not [string]::IsNullOrWhiteSpace($RequiredCheckpointName)) {
        if (-not $probe.checkpoint_discovery_supported) {
            throw 'ComfyUI checkpoint discovery is required for this SDXL acceptance run.'
        }
        $discoveredCheckpoints = @($probe.available_checkpoints)
        if ($discoveredCheckpoints -cnotcontains $RequiredCheckpointName) {
            throw 'The required SDXL checkpoint was not discovered by the configured ComfyUI runtime; no image job was submitted.'
        }
    }

    $payload = @{
        conversation_id = $conversationId
        prompt = $Prompt
        negative_prompt = $NegativePrompt
        width = $Width
        height = $Height
    }
    if ($Seed -ge 0) { $payload.seed = $Seed }
    $idempotencyKey = [Guid]::NewGuid().ToString()

    $job = Invoke-CoreJson -Method 'POST' -Path '/v1/lila/images' -Body $payload -IdempotencyKey $idempotencyKey -MaxResponseBytes 262144
    $jobId = [string]$job.job_id
    if ($job.conversation_id -ne $conversationId -or
        [string]::IsNullOrWhiteSpace($jobId) -or
        $jobId -notmatch '^[0-9a-fA-F-]{36}$') {
        throw 'Lila Core returned an invalid image job identity.'
    }

    $deadline = [DateTime]::UtcNow.AddSeconds($MaxWaitSeconds)
    while ($true) {
        $job = Invoke-CoreJson -Method 'GET' -Path ('/v1/lila/images/' + $jobId) -MaxResponseBytes 262144
        if ($job.job_id -ne $jobId -or $job.conversation_id -ne $conversationId) {
            throw 'Lila Core returned an image job outside the selected conversation.'
        }

        $state = [string]$job.state
        if ($state -eq 'completed') { break }
        if ($state -eq 'failed') {
            $code = [string]$job.error_code
            if ([string]::IsNullOrWhiteSpace($code)) { $code = 'unspecified' }
            throw ('ComfyUI generation failed with safe error code: {0}.' -f $code)
        }
        if ($state -eq 'cancelled') { throw 'The ComfyUI job was cancelled before completion.' }
        if ([DateTime]::UtcNow -ge $deadline) {
            throw ('Timed out waiting for job {0}; it was not cancelled or resubmitted. Check the same conversation history.' -f $jobId)
        }
        Start-Sleep -Seconds $PollIntervalSeconds
    }

    $asset = $job.asset
    if ($null -eq $asset -or $asset.mime_type -ne 'image/png') {
        throw 'The completed Core job has no valid PNG asset metadata.'
    }

    $assetBytes = Invoke-CoreBinary -Path ('/v1/lila/images/' + $jobId + '/asset') -MaxResponseBytes 26214400
    if ($assetBytes.LongLength -ne [long]$asset.byte_size) {
        throw 'Downloaded PNG byte count does not match Core metadata.'
    }
    $actualSha = Get-Sha256Hex -Bytes $assetBytes
    if ($actualSha -cne [string]$asset.sha256) {
        throw 'Downloaded PNG SHA-256 does not match Core metadata.'
    }
    $dimensions = Get-PngDimensions -Bytes $assetBytes
    if ($dimensions.Width -ne [int]$asset.width -or $dimensions.Height -ne [int]$asset.height) {
        throw 'Downloaded PNG dimensions do not match Core metadata.'
    }

    $encodedConversation = [Uri]::EscapeDataString($conversationId)
    $history = Invoke-CoreJson -Method 'GET' -Path ('/v1/lila/images?conversation_id=' + $encodedConversation + '&limit=50')
    $historyJob = @($history.jobs) | Where-Object { $_.job_id -eq $jobId } | Select-Object -First 1
    if ($null -eq $historyJob -or $historyJob.state -ne 'completed') {
        throw 'The completed image job was not found in the durable Core conversation history.'
    }

    $savedPath = $null
    if (-not [string]::IsNullOrWhiteSpace($OutputPath)) {
        $fullPath = [System.IO.Path]::GetFullPath($OutputPath)
        if (Test-Path -LiteralPath $fullPath) {
            throw 'OutputPath already exists; refusing to overwrite an existing file.'
        }
        $parentPath = [System.IO.Path]::GetDirectoryName($fullPath)
        if (-not [string]::IsNullOrWhiteSpace($parentPath) -and -not (Test-Path -LiteralPath $parentPath)) {
            New-Item -ItemType Directory -Path $parentPath -Force | Out-Null
        }
        [System.IO.File]::WriteAllBytes($fullPath, $assetBytes)
        $savedPath = $fullPath
    }

    [pscustomobject]@{
        Result = 'PASS'
        Provider = 'comfyui'
        WorkflowId = [string]$provider.workflow_id
        CheckpointName = [string]$RequiredCheckpointName
        ConversationId = $conversationId
        JobId = $jobId
        State = 'completed'
        Width = $dimensions.Width
        Height = $dimensions.Height
        ByteSize = $assetBytes.LongLength
        Sha256 = $actualSha
        DurableHistoryConfirmed = $true
        OutputPath = $savedPath
    }
}
finally {
    if ($null -ne $assetBytes) { [Array]::Clear($assetBytes, 0, $assetBytes.Length) }
    if ($null -ne $script:HttpClient) { $script:HttpClient.Dispose() }
    if ($null -ne $script:HttpHandler) { $script:HttpHandler.Dispose() }
    $script:AccessToken = $null
}
