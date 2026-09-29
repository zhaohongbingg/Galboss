Add-Type -AssemblyName System.Drawing

# 路径从脚本自身位置推导：$PSScriptRoot = tools/blockbench，不再写死盘符。
$root = Join-Path $PSScriptRoot 'textures'

function C($hex) {
    $r = [Convert]::ToInt32($hex.Substring(0, 2), 16)
    $g = [Convert]::ToInt32($hex.Substring(2, 2), 16)
    $b = [Convert]::ToInt32($hex.Substring(4, 2), 16)
    return [System.Drawing.Color]::FromArgb(255, $r, $g, $b)
}

# 武器色板：下半张，每块 8x8 纯色。UV 在色块内取子矩形，被拉伸也不影响观感。
#
# 配色取自两个参考模组的实际贴图：
#   枪 <- TACZ glock_17 的 uv 贴图（1024x1024，#2A2A2A 占 82.9%，钢色高光 #4F5156）
#   刀 <- TinkersKatanas 的 top_blade.png（16x16 灰度模板，#666666/#D8D8D8/#FFFFFF）
# 两排都是中性灰、不带蓝调，和原版武器观感一致。
$palette = @(
    @(0, 64, '2A2A2A'),    # gun_body   枪身 / 套筒（参考主色）
    @(8, 64, '212225'),    # gun_dark   凹槽、弹匣、扳机
    @(16, 64, '4F5156'),   # gun_steel  枪管、套筒导轨
    @(24, 64, '37393F'),   # gun_mid    握把
    @(32, 64, '6E737C'),   # gun_sight  准星
    @(0, 72, '666666'),    # blade_mid  刀身（参考主色）
    @(8, 72, '3F3F3F'),    # blade_dark 刀脊
    @(16, 72, 'D8D8D8'),   # blade_light 刀面亮部
    @(24, 72, 'FFFFFF'),   # blade_edge 刀刃
    @(32, 72, '1B1A28'),   # tsuka      刀柄缠绳
    @(40, 72, '2E2A33'),   # tsuka_wrap 缠绳亮部 / 柄头
    @(48, 72, '4A4038')    # tsuba      护手
)

foreach ($name in @('reizein_tohka', 'onabuta_ikuko', 'tadasugawa_rei')) {
    $src = Join-Path $root "$name.png"
    $bmp = [System.Drawing.Bitmap]::FromFile($src)
    $new = New-Object System.Drawing.Bitmap 64, 128
    $g = [System.Drawing.Graphics]::FromImage($new)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::None
    $g.DrawImage($bmp,
        (New-Object System.Drawing.Rectangle(0, 0, 64, 64)),
        (New-Object System.Drawing.Rectangle(0, 0, 64, 64)),
        [System.Drawing.GraphicsUnit]::Pixel)
    foreach ($p in $palette) {
        $brush = New-Object System.Drawing.SolidBrush (C $p[2])
        $g.FillRectangle($brush, $p[0], $p[1], 8, 8)
        $brush.Dispose()
    }

    # 手臂背面（south）在原始皮肤上只画了一半，另一半是透明的 —— 模型上就是手臂背面缺一块。
    # 把有像素的那一半按水平/垂直/中心三种镜像依次补齐（第一个找到的不透明源像素为准）。
    $regions = @(
        @(48, 8, 4, 12),   # right_arm south
        @(40, 52, 4, 12)   # left_arm south
    )
    foreach ($r in $regions) {
        $x1 = $r[0]; $y1 = $r[1]; $w = $r[2]; $h = $r[3]
        $snapshot = New-Object 'System.Drawing.Color[,]' $w, $h
        for ($i = 0; $i -lt $w; $i++) {
            for ($j = 0; $j -lt $h; $j++) { $snapshot[$i, $j] = $new.GetPixel($x1 + $i, $y1 + $j) }
        }
        for ($i = 0; $i -lt $w; $i++) {
            for ($j = 0; $j -lt $h; $j++) {
                if ($new.GetPixel($x1 + $i, $y1 + $j).A -ge 128) { continue }
                $candidates = @(@(($w - 1 - $i), $j), @($i, ($h - 1 - $j)), @(($w - 1 - $i), ($h - 1 - $j)))
                foreach ($cnd in $candidates) {
                    $src = $snapshot[$cnd[0], $cnd[1]]
                    if ($src.A -ge 128) { $new.SetPixel($x1 + $i, $y1 + $j, $src); break }
                }
            }
        }
    }

    $g.Dispose()
    $bmp.Dispose()
    $dst = Join-Path $root "$($name)_armed.png"
    $new.Save($dst, [System.Drawing.Imaging.ImageFormat]::Png)
    $new.Dispose()
    Write-Output "WROTE $($name)_armed.png  64x128"
}
