$c = [IO.File]::ReadAllText('C:\Users\Branden\.local\share\opencode\tool-output\tool_dd53fadba001I2C7SbRgYcgUNJ')

# Search for multiple patterns and extract context
$patterns = @(
    '__emval_get_global',
    '__emval_get_property',
    'embind',
    'registerType',
    'UTF8ToString',
    '_emscripten_asm_const_int',
    'navigator',
    'screen',
    'crypto',
    'performance',
    'Math.random',
    'Date',
    'canvas',
    'webgl',
    'WebGL',
    'ssignature',
    'stime',
    'addEventListener',
    'dispatchEvent',
    'wasmExports',
    'window.location',
    'location',
    'WebAssembly',
    'createWasm',
    'wasmImports',
    'on_window_event',
    'onRuntimeInitialized',
    'emval_run_destructors',
    'emval_new_cstring',
    'emval_invoke',
    'emval_decref',
    'emval_incref',
    'emval_create_invoker',
    'embind_register',
    '___getTypeName',
    '_malloc',
    '_free',
    '_main',
    'Module[',
    'HEAPU8',
    'HEAPU32',
    'HEAP8',
    'Memory',
    'Buffer',
    'wasmBinary',
    'base64Decode',
    'instantiateWasm',
    'wasm'
)

foreach ($p in $patterns) {
    Write-Output "`n========== $($p) =========="
    $i = 0
    $count = 0
    while (($i = $c.IndexOf($p, $i)) -ge 0 -and $count -lt 5) {
        $s = [Math]::Max(0, $i - 80)
        $e = [Math]::Min($c.Length, $i + 300)
        Write-Output "--- at offset $i ---"
        Write-Output $c.Substring($s, $e - $s)
        $i += $p.Length
        $count++
    }
    if ($count -eq 0) { Write-Output "NOT FOUND" }
}
