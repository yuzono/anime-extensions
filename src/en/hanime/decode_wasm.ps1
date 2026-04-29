$c = [IO.File]::ReadAllText('C:\Users\Branden\.local\share\opencode\tool-output\tool_dd53fadba001I2C7SbRgYcgUNJ')

# Extract the base64 blob
$marker = 'base64Decode("'
$idx = $c.IndexOf($marker)
$start = $idx + $marker.Length
$endQuote = $c.IndexOf('")', $start)
$blob = $c.Substring($start, $endQuote - $start)

# Decode to binary
$bytes = [Convert]::FromBase64String($blob)
Write-Output "WASM binary size: $($bytes.Length) bytes"

# Save to temp file
$outPath = 'C:\Users\Branden\IdeaProjects\anime-extensions\src\en\hanime\vendor.wasm'
[IO.File]::WriteAllBytes($outPath, $bytes)
Write-Output "Saved WASM binary to: $outPath"

# Try to find exported function names in the binary by looking for known strings
$decodedText = [System.Text.Encoding]::ASCII.GetString($bytes)
Write-Output "`n========== WASM BINARY STRING SEARCH =========="

# Search for known export names in the WASM binary
$searchTerms = @('on_window_event', 'main', '__getTypeName', 'malloc', 'free', 'emscripten_stack', 'memory', 'table', 'ssignature', 'stime', 'signature', 'timestamp', 'Navigator', 'navigator', 'document', 'window', 'location', 'href', 'userAgent', 'platform', 'screen', 'crypto', 'performance', 'Date', 'Math', 'random', 'canvas', 'webgl', 'emval', 'embind', 'string', 'std::', 'class', 'SignatureGenerator', 'Signature', 'generate', 'compute', 'hash', 'hmac', 'sha', 'md5', 'crc32', 'base64', 'encode', 'decode', 'token', 'auth', 'sign', 'verify', 'key', 'secret')
foreach ($term in $searchTerms) {
    $ti = $decodedText.IndexOf($term)
    if ($ti -ge 0) {
        $ctx = [Math]::Max(0, $ti - 20)
        $ctxEnd = [Math]::Min($decodedText.Length, $ti + 60)
        $snippet = $decodedText.Substring($ctx, $ctxEnd - $ctx) -replace '[^\x20-\x7E]', '.'
        Write-Output "--- $($term) at byte offset $ti ---"
        Write-Output $snippet
    }
}
