Add-Type -AssemblyName System.Drawing

# 手臂背面的真实 UV 区域（由导出 geo 的 south 面反查）：
#   右臂 south = uv[52,20] -> (52,20)-(56,32)
#   左臂 south = uv[44,52] -> (44,52)-(48,64)
# 两条手臂互相镜像补全，缺的地方依次尝试：自身水平镜像 -> 对侧水平镜像 -> 对侧中心对称
$RX = 52; $RY = 20
$LX = 44; $LY = 52

function Coverage($b, $x0, $y0) {
    $n = 0
    for ($x = 0; $x -lt 4; $x++) { for ($y = 0; $y -lt 12; $y++) { if ($b.GetPixel($x0 + $x, $y0 + $y).A -ge 128) { $n++ } } }
    return $n
}

function FillArm($b, $tx, $ty, $ox, $oy) {
    for ($i = 0; $i -lt 4; $i++) {
        for ($j = 0; $j -lt 12; $j++) {
            $dx = $tx + $i; $dy = $ty + $j
            if ($b.GetPixel($dx, $dy).A -ge 128) { continue }
            $done = $false
            $sx = $tx + 3 - $i; $sy = $ty + $j
            if ($b.GetPixel($sx, $sy).A -ge 128) { $b.SetPixel($dx, $dy, $b.GetPixel($sx, $sy)); $done = $true }
            if (-not $done) {
                $sx = $ox + 3 - $i; $sy = $oy + $j
                if ($b.GetPixel($sx, $sy).A -ge 128) { $b.SetPixel($dx, $dy, $b.GetPixel($sx, $sy)); $done = $true }
            }
            if (-not $done) {
                $sx = $ox + $i; $sy = $oy + 11 - $j
                if ($b.GetPixel($sx, $sy).A -ge 128) { $b.SetPixel($dx, $dy, $b.GetPixel($sx, $sy)) }
            }
        }
    }
}

foreach ($n in @('reizein_tohka', 'onabuta_ikuko', 'tadasugawa_rei')) {
    $src = "d:\game\mc\bossmod\blockbench\textures\${n}_armed.png"
    $tmp = [System.Drawing.Bitmap]::FromFile($src)
    $b = New-Object System.Drawing.Bitmap $tmp
    $tmp.Dispose()

    $r0 = Coverage $b $RX $RY
    $l0 = Coverage $b $LX $LY

    # 先互相对补（此时两边都可能是 50%，各自有不同的一半）
    FillArm $b $RX $RY $LX $LY
    FillArm $b $LX $LY $RX $RY
    # 再各自兜一轮（从上一步补好的对侧取）
    FillArm $b $RX $RY $LX $LY
    FillArm $b $LX $LY $RX $RY

    $r1 = Coverage $b $RX $RY
    $l1 = Coverage $b $LX $LY

    $b.Save($src, [System.Drawing.Imaging.ImageFormat]::Png)
    $b.Dispose()
    Copy-Item $src "d:\game\mc\bossmod\src\main\resources\assets\galboss\textures\entity\$n.png" -Force
    Write-Output ("  {0,-16} R {1,3}% -> {2,3}%    L {3,3}% -> {4,3}%" -f $n, [int](100 * $r0 / 48), [int](100 * $r1 / 48), [int](100 * $l0 / 48), [int](100 * $l1 / 48))
}
