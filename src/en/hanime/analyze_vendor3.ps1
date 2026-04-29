$c = [IO.File]::ReadAllText('C:\Users\Branden\.local\share\opencode\tool-output\tool_dd53fadba001I2C7SbRgYcgUNJ')

# 1. Extract the WASM base64 blob
Write-Output "========== WASM BASE64 BLOB =========="
$marker = 'base64Decode("'
$idx = $c.IndexOf($marker)
if ($idx -ge 0) {
    $start = $idx + $marker.Length
    $endQuote = $c.IndexOf('")', $start)
    if ($endQuote -ge 0) {
        $blob = $c.Substring($start, $endQuote - $start)
        Write-Output "WASM base64 length: $($blob.Length) characters"
        Write-Output "First 200 chars: $($blob.Substring(0, [Math]::Min(200, $blob.Length)))"
        Write-Output "Last 200 chars: $($blob.Substring([Math]::Max(0, $blob.Length - 200)))"
    }
}

# 2. Find all embind_register function definitions with full signatures
Write-Output "`n========== EMBIND REGISTER FUNCTIONS =========="
$embindPatterns = @('__embind_register_void', '__embind_register_bool', '__embind_register_integer', '__embind_register_bigint', '__embind_register_float', '__embind_register_std_string', '__embind_register_std_wstring', '__embind_register_emval', '__embind_register_memory_view')
foreach ($p in $embindPatterns) {
    $i = $c.IndexOf($p)
    if ($i -ge 0) {
        $s = [Math]::Max(0, $i - 20)
        $e = [Math]::Min($c.Length, $i + 500)
        Write-Output "--- $($p) ---"
        Write-Output $c.Substring($s, $e - $s)
    } else {
        Write-Output "--- $($p) --- NOT FOUND"
    }
}

# 3. Search for globalThis or global name lookups
Write-Output "`n========== GLOBALTHIS / GLOBAL ACCESS =========="
$globals = @('globalThis', 'self.', 'window.', 'document.', 'navigator.', 'screen.', 'crypto.', 'performance.', 'localStorage')
foreach ($p in $globals) {
    $i = 0
    $count = 0
    while (($i = $c.IndexOf($p, $i)) -ge 0 -and $count -lt 3) {
        $s = [Math]::Max(0, $i - 30)
        $e = [Math]::Min($c.Length, $i + 200)
        Write-Output "--- $($p) at offset $i ---"
        Write-Output $c.Substring($s, $e - $s)
        $i += $p.Length
        $count++
    }
    if ($count -eq 0) { Write-Output "--- $($p) --- NOT FOUND" }
}

# 4. Find the readEmAsmArgs function  
Write-Output "`n========== readEmAsmArgs =========="
$i = $c.IndexOf('readEmAsmArgs')
if ($i -ge 0) {
    $s = [Math]::Max(0, $i - 50)
    $e = [Math]::Min($c.Length, $i + 800)
    Write-Output $c.Substring($s, $e - $s)
}

# 5. Find the complete wasmImports table
Write-Output "`n========== WASM IMPORTS TABLE (FULL) =========="
$impMarker = 'var wasmImports={'
$i = $c.IndexOf($impMarker)
if ($i -ge 0) {
    $s = $i
    $e = $c.IndexOf('};', $i + $impMarker.Length)
    if ($e -ge 0) {
        Write-Output $c.Substring($s, $e - $s + 2)
    }
}

# 6. Find the complete assignWasmExports function
Write-Output "`n========== ASSIGN WASM EXPORTS (FULL) =========="
$expMarker = 'function assignWasmExports'
$i = $c.IndexOf($expMarker)
if ($i -ge 0) {
    $s = $i
    $e = $c.IndexOf('}', $i + 200)
    if ($e -ge 0) {
        Write-Output $c.Substring($s, $e - $s + 1)
    }
}

# 7. Find emval_methodCallers
Write-Output "`n========== EMVAL METHOD CALLERS =========="
$i = $c.IndexOf('emval_methodCallers')
if ($i -ge 0) {
    $s = [Math]::Max(0, $i - 50)
    $e = [Math]::Min($c.Length, $i + 500)
    Write-Output $c.Substring($s, $e - $s)
}

# 8. Find getStringOrSymbol
Write-Output "`n========== getStringOrSymbol =========="
$i = $c.IndexOf('getStringOrSymbol')
if ($i -ge 0) {
    $s = [Math]::Max(0, $i - 30)
    $e = [Math]::Min($c.Length, $i + 500)
    Write-Output $c.Substring($s, $e - $s)
}

# 9. Find the window_on function in full
Write-Output "`n========== WINDOW_ON FUNCTION (FULL) =========="
$i = $c.IndexOf('function window_on')
if ($i -ge 0) {
    $s = $i
    $e = $c.IndexOf('}', $i + 300)
    if ($e -ge 0) {
        Write-Output $c.Substring($s, $e - $s + 1)
    }
}

# 10. Search for any custom event names or string constants
Write-Output "`n========== STRING CONSTANTS / EVENT NAMES =========="
$eventPatterns = @('CustomEvent', 'dispatchEvent', 'detail', 'on_window_event')
foreach ($p in $eventPatterns) {
    $i = 0
    $count = 0
    while (($i = $c.IndexOf($p, $i)) -ge 0 -and $count -lt 3) {
        $s = [Math]::Max(0, $i - 60)
        $e = [Math]::Min($c.Length, $i + 200)
        Write-Output "--- $($p) at offset $i ---"
        Write-Output $c.Substring($s, $e - $s)
        $i += $p.Length
        $count++
    }
    if ($count -eq 0) { Write-Output "--- $($p) --- NOT FOUND" }
}
