$c = [IO.File]::ReadAllText('C:\Users\Branden\.local\share\opencode\tool-output\tool_dd53fadba001I2C7SbRgYcgUNJ')

# 1. Find the createWasm function in full
Write-Output "========== CREATE WASM (FULL) =========="
$i = $c.IndexOf('async function createWasm')
if ($i -ge 0) {
    $e = $c.IndexOf('}', $i + 500)
    Write-Output $c.Substring($i, $e - $i + 1)
}

# 2. Find the complete run/callMain flow
Write-Output "`n========== CALLMAIN FUNCTION =========="
$i = $c.IndexOf('function callMain')
if ($i -ge 0) {
    $e = $c.IndexOf('}', $i + 300)
    Write-Output $c.Substring($i, $e - $i + 1)
}

# 3. Find emval symbols and string registrations - look for emval_symbols assignments
Write-Output "`n========== EMVAL SYMBOLS =========="
$i = 0
while (($i = $c.IndexOf('emval_symbols', $i)) -ge 0) {
    $s = [Math]::Max(0, $i - 30)
    $e = [Math]::Min($c.Length, $i + 300)
    Write-Output "--- at offset $i ---"
    Write-Output $c.Substring($s, $e - $s)
    $i += 14
    if ($i -gt 195000) { break }
}

# 4. Look for the entire Emval class definition
Write-Output "`n========== EMVAL CLASS =========="
$i = $c.IndexOf('var Emval={')
if ($i -ge 0) {
    $e = $c.IndexOf('};', $i + 10)
    Write-Output $c.Substring($i, $e - $i + 2)
}

# 5. Find the base64Decode function itself
Write-Output "`n========== BASE64 DECODE FUNCTION =========="
$i = $c.IndexOf('var base64Decode=')
if ($i -ge 0) {
    $e = $c.IndexOf('};', $i + 500)
    Write-Output $c.Substring($i, $e - $i + 2)
}

# 6. Find all string literals that look like JS property names the WASM might access
Write-Output "`n========== POTENTIAL PROPERTY NAME STRINGS =========="
$propPatterns = @('userAgent', 'platform', 'vendor', 'language', 'languages', 'hardwareConcurrency', 'deviceMemory', 'maxTouchPoints', 'cookieEnabled', 'webdriver', 'connection', 'plugins', 'mimeTypes', 'appVersion', 'appName', 'product', 'productSub', 'oscpu', 'buildID', 'getBattery', 'sendBeacon', 'fetch', 'XMLHttpRequest', 'WebSocket', 'Worker', 'SharedArrayBuffer', 'Atomics', 'BigInt', 'Proxy', 'Reflect', 'Symbol', 'Map', 'Set', 'WeakMap', 'WeakSet', 'Promise', 'ArrayBuffer', 'DataView', 'Float32Array', 'Float64Array', 'Int8Array', 'Uint8Array', 'Int16Array', 'Uint16Array', 'Int32Array', 'Uint32Array')
foreach ($p in $propPatterns) {
    $idx = $c.IndexOf($p)
    if ($idx -ge 0) {
        $s = [Math]::Max(0, $idx - 40)
        $e = [Math]::Min($c.Length, $idx + 100)
        Write-Output "--- $($p) FOUND at offset $idx ---"
        Write-Output $c.Substring($s, $e - $s)
    }
}

# 7. Look for any remaining JS function names the WASM calls via emval
Write-Output "`n========== CCALL / CWRAP USAGE =========="
$ccallPatterns = @('ccall', 'cwrap', 'setValue', 'getValue', 'ALLOC_NORMAL', 'ALLOC_STACK')
foreach ($p in $ccallPatterns) {
    $i = 0
    $count = 0
    while (($i = $c.IndexOf($p, $i)) -ge 0 -and $count -lt 3) {
        $s = [Math]::Max(0, $i - 40)
        $e = [Math]::Min($c.Length, $i + 200)
        Write-Output "--- $($p) at offset $i ---"
        Write-Output $c.Substring($s, $e - $s)
        $i += $p.Length
        $count++
    }
}

# 8. Look for any remaining __emscripten or internal function names
Write-Output "`n========== EMSRIPTEN INTERNALS =========="
$esmPatterns = @('__emscripten_stack', '_emscripten_stack', 'stackSave', 'stackRestore', 'getHigh32', 'setHigh32')
foreach ($p in $esmPatterns) {
    $i = 0
    $count = 0
    while (($i = $c.IndexOf($p, $i)) -ge 0 -and $count -lt 2) {
        $s = [Math]::Max(0, $i - 30)
        $e = [Math]::Min($c.Length, $i + 200)
        Write-Output "--- $($p) at offset $i ---"
        Write-Output $c.Substring($s, $e - $s)
        $i += $p.Length
        $count++
    }
}

# 9. Find the ENV object and what's in it
Write-Output "`n========== ENV OBJECT =========="
$i = $c.IndexOf('var ENV={')
if ($i -ge 0) {
    $s = $i
    $e = [Math]::Min($c.Length, $i + 500)
    Write-Output $c.Substring($s, $e - $s)
}

# 10. Find exception handling / ___cxa_throw
Write-Output "`n========== EXCEPTION HANDLING =========="
$i = $c.IndexOf('___cxa_throw')
if ($i -ge 0) {
    $s = [Math]::Max(0, $i - 50)
    $e = [Math]::Min($c.Length, $i + 500)
    Write-Output $c.Substring($s, $e - $s)
}
