param(
  [Parameter(Mandatory = $true)][string]$OutFile
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class CapGame {
  public const uint PW_RENDERFULLCONTENT = 2;
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool ClientToScreen(IntPtr h, ref POINT p);
  [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr hdc, uint flags);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr h);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L,T,R,B; }
  [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X,Y; }
}
'@

$p = Get-Process | Where-Object {
  $_.ProcessName -match '^(Lin|TW13032701)' -or
  $_.MainWindowTitle -match '13032701'
} | Where-Object { [int64]$_.MainWindowHandle -ne 0 } | Select-Object -First 1
if (-not $p) {
  Write-Output 'NO_WINDOW'
  exit 2
}
$h = $p.MainWindowHandle
if ([CapGame]::IsIconic($h)) {
  Write-Output 'MINIMIZED'
  exit 4
}
$wr = New-Object CapGame+RECT
$cr = New-Object CapGame+RECT
[void][CapGame]::GetWindowRect($h, [ref]$wr)
[void][CapGame]::GetClientRect($h, [ref]$cr)
$pt = New-Object CapGame+POINT
$pt.X = 0; $pt.Y = 0
[void][CapGame]::ClientToScreen($h, [ref]$pt)
$ww = $wr.R - $wr.L
$wh = $wr.B - $wr.T
$cw = $cr.R - $cr.L
$ch = $cr.B - $cr.T
if ($ww -lt 64 -or $wh -lt 64) {
  Write-Output ('BAD_SIZE ' + $ww + 'x' + $wh)
  exit 5
}
$bmp = New-Object System.Drawing.Bitmap $ww, $wh
$g = [System.Drawing.Graphics]::FromImage($bmp)
$dc = $g.GetHdc()
$ok = [CapGame]::PrintWindow($h, $dc, [CapGame]::PW_RENDERFULLCONTENT)
$g.ReleaseHdc($dc)
$g.Dispose()
if (-not $ok) {
  $bmp.Dispose()
  Write-Output 'PRINTWINDOW_FAIL'
  exit 6
}
$cx = [Math]::Max(0, $pt.X - $wr.L)
$cy = [Math]::Max(0, $pt.Y - $wr.T)
if ($cx + $cw > $ww) { $cw = $ww - $cx }
if ($cy + $ch > $wh) { $ch = $wh - $cy }
if ($cw -ge 64 -and $ch -ge 64) {
  $rect = New-Object System.Drawing.Rectangle $cx, $cy, $cw, $ch
  $client = $bmp.Clone($rect, $bmp.PixelFormat)
  $bmp.Dispose()
  $bmp = $client
}
$dir = Split-Path -Parent $OutFile
if ($dir -and -not (Test-Path -LiteralPath $dir)) {
  New-Item -ItemType Directory -Force -Path $dir | Out-Null
}
if (Test-Path -LiteralPath $OutFile) { Remove-Item -LiteralPath $OutFile -Force }
$bmp.Save($OutFile, [System.Drawing.Imaging.ImageFormat]::Png)
$w = $bmp.Width
$ht = $bmp.Height
$bmp.Dispose()
$item = Get-Item -LiteralPath $OutFile
Write-Output ('OK w=' + $w + ' h=' + $ht + ' bytes=' + $item.Length + ' title=' + $p.MainWindowTitle + ' visible=' + [CapGame]::IsWindowVisible($h))
exit 0
