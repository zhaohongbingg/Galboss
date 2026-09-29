Add-Type -AssemblyName System.Drawing
Add-Type -TypeDefinition @'
using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;

public class IconMaker
{
    private static int Dist(int r1, int g1, int b1, int r2, int g2, int b2)
    {
        return Math.Max(Math.Abs(r1 - r2), Math.Max(Math.Abs(g1 - g2), Math.Abs(b1 - b2)));
    }

    public static string Convert(string src, string dst, int size)
    {
        using (Bitmap img = new Bitmap(src))
        {
            int w = img.Width, h = img.Height;
            BitmapData data = img.LockBits(new Rectangle(0, 0, w, h), ImageLockMode.ReadOnly, PixelFormat.Format32bppArgb);
            int stride = data.Stride;
            byte[] buf = new byte[stride * h];
            Marshal.Copy(data.Scan0, buf, 0, buf.Length);
            img.UnlockBits(data);

            // 用四角平均值当作背景色（每张图的品红底深浅并不一致）
            int br = 0, bg = 0, bb = 0;
            int[][] corners = new int[][] { new int[]{6,6}, new int[]{w-7,6}, new int[]{6,h-7}, new int[]{w-7,h-7} };
            foreach (int[] c in corners)
            {
                int i = c[1] * stride + c[0] * 4;
                br += buf[i + 2]; bg += buf[i + 1]; bb += buf[i];
            }
            br /= 4; bg /= 4; bb /= 4;

            const int BG_TOL = 45;      // 认定为背景
            const int BLEND_TOL = 80;   // 认定为与背景混色的边缘像素，不参与取色

            // 在中央区域找物品包围盒，避开角落水印
            int x0 = (int)(w * 0.08), x1 = (int)(w * 0.92);
            int y0 = (int)(h * 0.03), y1 = (int)(h * 0.90);
            int minX = int.MaxValue, minY = int.MaxValue, maxX = -1, maxY = -1;
            for (int y = y0; y < y1; y++)
            {
                int row = y * stride;
                for (int x = x0; x < x1; x++)
                {
                    int i = row + x * 4;
                    if (Dist(buf[i + 2], buf[i + 1], buf[i], br, bg, bb) <= BG_TOL) continue;
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
            if (maxX < 0) return "no item pixels found in " + src;

            int bw = maxX - minX + 1, bh = maxY - minY + 1;
            int side = (int)(Math.Max(bw, bh) * 1.12);
            int cx = (minX + maxX) / 2, cy = (minY + maxY) / 2;
            int sx = cx - side / 2, sy = cy - side / 2;
            double block = (double)side / size;

            using (Bitmap outBmp = new Bitmap(size, size, PixelFormat.Format32bppArgb))
            {
                for (int oy = 0; oy < size; oy++)
                {
                    for (int ox = 0; ox < size; ox++)
                    {
                        int pxa = sx + (int)Math.Floor(ox * block);
                        int pxb = sx + (int)Math.Floor((ox + 1) * block);
                        int pya = sy + (int)Math.Floor(oy * block);
                        int pyb = sy + (int)Math.Floor((oy + 1) * block);
                        if (pxb <= pxa) pxb = pxa + 1;
                        if (pyb <= pya) pyb = pya + 1;

                        long sr = 0, sg = 0, sb = 0;
                        int taken = 0, total = 0;
                        for (int y = pya; y < pyb; y++)
                        {
                            if (y < 0 || y >= h) continue;
                            int row = y * stride;
                            for (int x = pxa; x < pxb; x++)
                            {
                                if (x < 0 || x >= w) continue;
                                total++;
                                int i = row + x * 4;
                                int r = buf[i + 2], g = buf[i + 1], b = buf[i];
                                if (Dist(r, g, b, br, bg, bb) <= BLEND_TOL) continue;
                                sr += r; sg += g; sb += b; taken++;
                            }
                        }

                        // 该格子里实心像素占比不够高就留空，边缘因此干净
                        if (total == 0 || taken * 100 < total * 40)
                        {
                            outBmp.SetPixel(ox, oy, Color.Transparent);
                        }
                        else
                        {
                            outBmp.SetPixel(ox, oy, Color.FromArgb(255,
                                (int)(sr / taken), (int)(sg / taken), (int)(sb / taken)));
                        }
                    }
                }
                outBmp.Save(dst, ImageFormat.Png);
            }
            return string.Format("{0} -> {1} ({2}px, bbox {3}x{4}, bg {5},{6},{7})",
                System.IO.Path.GetFileName(src), System.IO.Path.GetFileName(dst), size, bw, bh, br, bg, bb);
        }
    }
}
'@ -ReferencedAssemblies System.Drawing

$srcDir = "d:\game\mc\bossmod\_tex_src"
$itemDir = "d:\game\mc\bossmod\src\main\resources\assets\reizenmod\textures\item"
$fxDir = "d:\game\mc\bossmod\src\main\resources\assets\reizenmod\textures\mob_effect"
New-Item -ItemType Directory -Force -Path $itemDir | Out-Null
New-Item -ItemType Directory -Force -Path $fxDir | Out-Null

$jobs = @(
    @("A_single_small_pixel_art_game__2026-09-27T09-09-18.png", "$itemDir\tohka_badge.png", 16),
    @("A_single_small_pixel_art_game__2026-09-27T09-10-31.png", "$itemDir\rei_armband.png", 16),
    @("A_single_small_pixel_art_game__2026-09-27T09-09-49.png", "$itemDir\ikuko_medal.png", 16),
    @("A_single_small_pixel_art_game__2026-09-27T09-09-47.png", "$itemDir\fs_big_three_core.png", 16),
    @("A_single_small_pixel_art_game__2026-09-27T09-10-45.png", "$itemDir\fs_badge.png", 16),
    @("A_single_small_pixel_art_game__2026-09-27T09-11-00.png", "$fxDir\bleeding.png", 18)
)

foreach ($j in $jobs) {
    $src = Join-Path $srcDir $j[0]
    if (-not (Test-Path $src)) { Write-Output "MISSING $src"; continue }
    try { Write-Output ([IconMaker]::Convert($src, $j[1], [int]$j[2])) }
    catch { Write-Output "FAILED $($j[1]): $_" }
}
