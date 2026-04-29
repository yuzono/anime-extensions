$c = [IO.File]::ReadAllText('C:\Users\Branden\.local\share\opencode\tool-output\tool_dd53fadba001I2C7SbRgYcgUNJ')
Write-Output "File length: $($c.Length)"

# 1. ASM_CONSTS / asmConsts
Write-Output "`n========== ASM_CONSTS =========="
$pattern = 'ASM_CONSTS'
$i = 0
while (($i = $c.IndexOf($pattern, $i)) -ge 0) {
    $s = [Math]::Max(0, $i - 50)
    $e = [Math]::Min($c.Length, $i + 3000)
    Write-Output "--- at offset $i ---"
    Write-Output $c.Substring($s, $e - $s)
    $i += $pattern.Length
}

# 2. asmConsts (lowercase)
Write-Output "`n========== asmConsts =========="
$pattern = 'asmConsts'
$i = 0
while (($i = $c.IndexOf($pattern, $i)) -ge 0) {
    $s = [Math]::Max(0, $i - 50)
    $e = [Math]::Min($c.Length, $i + 1000)
    Write-Output "--- at offset $i ---"
    Write-Output $c.Substring($s, $e - $s)
    $i += $pattern.Length
}
