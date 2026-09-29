# Compose an atlas: character skin at (0,0) + weapon texture at (64,0).
# Pure ASCII on purpose - non-ASCII in a .ps1 gets decoded as GBK and breaks parsing.
# Character UVs need no change (they start at 0,0); weapon UVs shift by +64 on X.
Add-Type -AssemblyName System.Drawing

$root = "d:\game\mc\bossmod\src\main\resources\assets\galboss\textures\entity"

$jobs = @(
  @{ skin = "onabuta_ikuko.png"; weapon = "ikuko_katana.png"; out = "onabuta_ikuko.png" }
)

foreach ($j in $jobs) {
  $sp = Join-Path $root $j.skin
  $wp = Join-Path $root $j.weapon
  $out = Join-Path $root $j.out

  if (-not (Test-Path $sp)) { Write-Output "  skip: missing $($j.skin)"; continue }
  if (-not (Test-Path $wp)) { Write-Output "  skip: missing $($j.weapon)"; continue }

  # guard against re-running on an already merged atlas
  $skinInfo = [System.Drawing.Image]::FromFile($sp)
  $sw = $skinInfo.Width
  $sh = $skinInfo.Height
  $skinInfo.Dispose()
  if ($sw -gt 256 -or $sh -gt 256) {
    Write-Output "  skip: $($j.skin) is already an atlas ($sw x $sh). Restore the base skin first."
    continue
  }

  $skin = [System.Drawing.Image]::FromFile($sp)
  $weapon = [System.Drawing.Image]::FromFile($wp)

  $size = 2048
  $bmp = New-Object System.Drawing.Bitmap($size, $size)
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.Clear([System.Drawing.Color]::Transparent)
  $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
  $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half

  $g.DrawImage($skin, (New-Object System.Drawing.Rectangle(0, 0, $skin.Width, $skin.Height)))

  $wx = 64
  $g.DrawImage($weapon, (New-Object System.Drawing.Rectangle($wx, 0, $weapon.Width, $weapon.Height)))

  $g.Dispose()
  $bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
  $bmp.Dispose()

  Write-Output "  $($j.out) written: ${size}x${size}"
  Write-Output "    skin   $($skin.Width)x$($skin.Height) at (0,0)"
  Write-Output "    weapon $($weapon.Width)x$($weapon.Height) at ($wx,0)  -> weapon UV shift +$wx"

  $skin.Dispose()
  $weapon.Dispose()
}
