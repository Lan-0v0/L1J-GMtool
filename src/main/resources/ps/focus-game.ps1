$ErrorActionPreference = 'Stop'
$p = Get-Process | Where-Object {
  $_.ProcessName -match '^(Lin|TW13032701)' -or
  $_.MainWindowTitle -match '13032701' -or
  ($_.Path -and ($_.Path -match 'Lin\.bin$' -or $_.Path -match '13032701\.bin$'))
} | Where-Object { [int64]$_.MainWindowHandle -ne 0 } | Select-Object -First 1
if (-not $p) { Write-Output 'NO_WINDOW'; exit 2 }

Add-Type -Name W -Namespace N -MemberDefinition @'
[DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
[DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int n);
[DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr h);
[DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
[DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, IntPtr pid);
[DllImport("user32.dll")] public static extern bool AttachThreadInput(uint a, uint b, bool f);
[DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
'@

$h = $p.MainWindowHandle
[N.W]::ShowWindow($h, 9) | Out-Null
[N.W]::BringWindowToTop($h) | Out-Null
$cur = [N.W]::GetCurrentThreadId()
$fgTid = [N.W]::GetWindowThreadProcessId([N.W]::GetForegroundWindow(), [IntPtr]::Zero)
$tgtTid = [N.W]::GetWindowThreadProcessId($h, [IntPtr]::Zero)
[N.W]::AttachThreadInput($cur, $fgTid, $true) | Out-Null
[N.W]::AttachThreadInput($cur, $tgtTid, $true) | Out-Null
[N.W]::SetForegroundWindow($h) | Out-Null
[N.W]::AttachThreadInput($cur, $fgTid, $false) | Out-Null
[N.W]::AttachThreadInput($cur, $tgtTid, $false) | Out-Null

$shell = New-Object -ComObject WScript.Shell
$activated = $false
try {
  Add-Type -AssemblyName Microsoft.VisualBasic
  [Microsoft.VisualBasic.Interaction]::AppActivate($p.Id)
  $activated = $true
} catch {}
if (-not $activated) {
  $activated = [bool]$shell.AppActivate($p.Id)
}
Start-Sleep -Milliseconds 280
if ([int64][N.W]::GetForegroundWindow() -ne [int64]$h -and -not $activated) {
  Write-Output ('FOCUS_FAIL hwnd=' + [int64]$h + ' fg=' + [int64][N.W]::GetForegroundWindow())
  exit 1
}
$shell.SendKeys('~')
Start-Sleep -Milliseconds 140
$shell.SendKeys('^v')
Start-Sleep -Milliseconds 120
$shell.SendKeys('~')
exit 0
