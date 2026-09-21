# 从用户提供的 PNG 生成 Android 图标资源（仅依赖 Windows 自带 .NET System.Drawing）
param(
    [string]$Source = "D:\0Downloads\0b8797cae104cac103cc0b5b5cf5d9fa.png",
    [string]$Res = "D:\CodexProjects\2026-09-19\app\src\main\res"
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

$Argb = [System.Drawing.Imaging.PixelFormat]::Format32bppArgb
$Rgb24 = [System.Drawing.Imaging.PixelFormat]::Format24bppRgb
$Png = [System.Drawing.Imaging.ImageFormat]::Png

function New-Bmp([int]$w, [int]$h, [bool]$alpha) {
    $fmt = if ($alpha) { $script:Argb } else { $script:Rgb24 }
    return (New-Object System.Drawing.Bitmap($w, $h, $fmt))
}

function New-Gfx([System.Drawing.Image]$img) {
    $g = [System.Drawing.Graphics]::FromImage($img)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $g.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
    return $g
}

# 渐进式缩放：GDI+ 一次性缩小 20 倍会有锯齿
function Resize-Img([System.Drawing.Image]$src, [int]$tw, [int]$th, [bool]$alpha) {
    $cur = $src
    while (($cur.Width -gt $tw * 2) -and ($cur.Height -gt $th * 2)) {
        $nw = [Math]::Max($tw, [int][Math]::Round($cur.Width / 2))
        $nh = [Math]::Max($th, [int][Math]::Round($cur.Height / 2))
        $tmp = New-Bmp $nw $nh $alpha
        $g = New-Gfx $tmp
        $g.DrawImage($cur, 0, 0, $nw, $nh)
        $g.Dispose()
        if (-not [Object]::ReferenceEquals($cur, $src)) { $cur.Dispose() }
        $cur = $tmp
    }
    $out = New-Bmp $tw $th $alpha
    $g = New-Gfx $out
    $g.DrawImage($cur, 0, 0, $tw, $th)
    $g.Dispose()
    if (-not [Object]::ReferenceEquals($cur, $src)) { $cur.Dispose() }
    return $out
}

$src = New-Object System.Drawing.Bitmap($Source)
$w = $src.Width
$h = $src.Height

# --- 1. 读像素，定位主体（排除最外圈那圈浅色描边）---
$data = $src.LockBits((New-Object System.Drawing.Rectangle(0, 0, $w, $h)),
    [System.Drawing.Imaging.ImageLockMode]::ReadOnly, $Argb)
$stride = $data.Stride
$px = New-Object byte[] ($stride * $h)
[System.Runtime.InteropServices.Marshal]::Copy($data.Scan0, $px, 0, $px.Length)
$src.UnlockBits($data)

# 用中心行/列扫描定位主体：圆角那圈淡蓝描边只贴在图像最外侧，中心线不会碰到
$cy = [int]($h / 2)
$cx = [int]($w / 2)
$limit = 248.0
$rowFirst = -1; $rowLast = -1; $colFirst = -1; $colLast = -1
for ($x = 10; $x -lt $w - 10; $x++) {
    $i = $cy * $stride + $x * 4
    if ((($px[$i] + $px[$i + 1] + $px[$i + 2]) / 3.0) -lt $limit) {
        if ($rowFirst -lt 0) { $rowFirst = $x }
        $rowLast = $x
    }
}
for ($y = 10; $y -lt $h - 10; $y++) {
    $i = $y * $stride + $cx * 4
    if ((($px[$i] + $px[$i + 1] + $px[$i + 2]) / 3.0) -lt $limit) {
        if ($colFirst -lt 0) { $colFirst = $y }
        $colLast = $y
    }
}
if ($rowFirst -lt 0 -or $colFirst -lt 0) { throw "没能定位到图标主体" }
Write-Host "subject: x $rowFirst..$rowLast  y $colFirst..$colLast"

# --- 2. 背景色采样（主体上方纯背景区）---
$sr = 0L; $sg = 0L; $sb = 0L; $n = 0
for ($y = 40; $y -lt 140; $y += 3) {
    for ($x = 200; $x -lt 1000; $x += 3) {
        $i = $y * $stride + $x * 4
        $sr += $px[$i + 2]; $sg += $px[$i + 1]; $sb += $px[$i]; $n++
    }
}
$bgR = [int][Math]::Round($sr / $n); $bgG = [int][Math]::Round($sg / $n); $bgB = [int][Math]::Round($sb / $n)
$bgHex = "#{0:X2}{1:X2}{2:X2}" -f $bgB, $bgG, $bgR
Write-Host "background color: $bgHex  (r=$bgR g=$bgG b=$bgB)"

# --- 3. 裁剪框：主体外扩 margin，用来做自适应图标前景 ---
$margin = 12
$cropX = [Math]::Max(0, $rowFirst - $margin)
$cropY = [Math]::Max(0, $colFirst - $margin)
$cropW = [Math]::Min($w, $rowLast + $margin + 1) - $cropX
$cropH = [Math]::Min($h, $colLast + $margin + 1) - $cropY
if ($cropW -gt $w) { $cropW = $w }
if ($cropH -gt $h) { $cropH = $h }
Write-Host "crop: $cropX,$cropY ${cropW}x${cropH}"

$crop = $src.Clone((New-Object System.Drawing.Rectangle($cropX, $cropY, $cropW, $cropH)), $Argb)

# --- 4. 自适应图标：108dp 画布（4x = 432px），主体占 68dp 宽，居中 ---
$canvas = 432
$contentW = 272
$contentH = [int][Math]::Round($contentW * $cropH / $cropW)
$dx = [int](($canvas - $contentW) / 2)
$dy = [int](($canvas - $contentH) / 2)

$fg = New-Bmp $canvas $canvas $true
$scaled = Resize-Img $crop $contentW $contentH $true
$g = New-Gfx $fg
$g.DrawImage($scaled, $dx, $dy, $contentW, $contentH)
$g.Dispose()
$scaled.Dispose()

$fgDir = Join-Path $Res "drawable-xxxhdpi"
if (-not (Test-Path $fgDir)) { New-Item -ItemType Directory -Path $fgDir | Out-Null }
$fg.Save((Join-Path $fgDir "ic_launcher_foreground.png"), $Png)
Write-Host "wrote drawable-xxxhdpi/ic_launcher_foreground.png (content ${contentW}x${contentH} at $dx,$dy)"

# --- 5. monochrome（主题图标）：按亮度转成白色剪影，白色圆盘自然变成镂空 ---
$mono = New-Bmp $cropW $cropH $true
$g = New-Gfx $mono
$g.DrawImage($crop, 0, 0, $cropW, $cropH)
$g.Dispose()
$md = $mono.LockBits((New-Object System.Drawing.Rectangle(0, 0, $cropW, $cropH)),
    [System.Drawing.Imaging.ImageLockMode]::ReadWrite, $Argb)
$ms = $md.Stride
$mb = New-Object byte[] ($ms * $cropH)
[System.Runtime.InteropServices.Marshal]::Copy($md.Scan0, $mb, 0, $mb.Length)
$full = 205.0
$zero = 248.0
for ($y = 0; $y -lt $cropH; $y++) {
    for ($x = 0; $x -lt $cropW; $x++) {
        $i = $y * $ms + $x * 4
        $l = ($mb[$i] + $mb[$i + 1] + $mb[$i + 2]) / 3.0
        if ($l -le $full) { $a = 255 }
        elseif ($l -ge $zero) { $a = 0 }
        else { $a = [int][Math]::Round(255.0 * ($zero - $l) / ($zero - $full)) }
        $mb[$i] = 255; $mb[$i + 1] = 255; $mb[$i + 2] = 255; $mb[$i + 3] = $a
    }
}
[System.Runtime.InteropServices.Marshal]::Copy($mb, 0, $md.Scan0, $mb.Length)
$mono.UnlockBits($md)

$monoCanvas = New-Bmp $canvas $canvas $true
$monoScaled = Resize-Img $mono $contentW $contentH $true
$g = New-Gfx $monoCanvas
$g.DrawImage($monoScaled, $dx, $dy, $contentW, $contentH)
$g.Dispose()
$monoScaled.Dispose()
$mono.Dispose()
$monoCanvas.Save((Join-Path $fgDir "ic_launcher_monochrome.png"), $Png)
Write-Host "wrote drawable-xxxhdpi/ic_launcher_monochrome.png"

# --- 6. 传统图标（API 25 及以下 / 部分启动器）：整图缩放 ---
$legacy = @{ "mdpi" = 48; "hdpi" = 72; "xhdpi" = 96; "xxhdpi" = 144; "xxxhdpi" = 192 }
foreach ($k in $legacy.Keys) {
    $size = $legacy[$k]
    $dir = Join-Path $Res "mipmap-$k"
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }

    $square = Resize-Img $src $size $size $false
    $square.Save((Join-Path $dir "ic_launcher.png"), $Png)
    $square.Dispose()

    $round = New-Bmp $size $size $true
    $g = New-Gfx $round
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $path.AddEllipse(0, 0, $size, $size)
    $g.SetClip($path)
    $g.DrawImage($src, 0, 0, $size, $size)
    $g.Dispose()
    $path.Dispose()
    $round.Save((Join-Path $dir "ic_launcher_round.png"), $Png)
    $round.Dispose()
}
Write-Host "wrote legacy mipmaps: $($legacy.Keys -join ', ')"

$src.Dispose()
Write-Host "DONE"
