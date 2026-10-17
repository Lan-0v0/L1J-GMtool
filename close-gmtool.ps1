$ErrorActionPreference = "SilentlyContinue"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$killed = New-Object System.Collections.Generic.List[int]

function Kill-Pid([int]$id) {
    if ($id -le 4) { return }
    if ($killed.Contains($id)) { return }
    Stop-Process -Id $id -Force
    if (-not $?) { return }
    $killed.Add($id) | Out-Null
}

Get-CimInstance Win32_Process | ForEach-Object {
    $cl = $_.CommandLine
    $exe = $_.ExecutablePath
    if ($cl -match "Server_Game\.jar" -or $cl -match "com\.lineage\.Server") { return }
    $hit = $false
    if ($cl -and ($cl -match "GMTool\.jar" -or $cl -match "local\.longtan\.gmtool" -or $cl -match "gmtool\.home")) {
        $hit = $true
    }
    if (-not $hit -and $exe -and ($exe -like "*L1J-GMtool*") -and ($_.Name -match "^java")) {
        $hit = $true
    }
    if ($hit) { Kill-Pid $_.ProcessId }
}

$portFile = Join-Path $here "data\gmtool.port"
if (Test-Path -LiteralPath $portFile) {
    $raw = (Get-Content -LiteralPath $portFile -Raw).Trim()
    $port = 0
    [void][int]::TryParse($raw, [ref]$port)
    if ($port -ge 17880 -and $port -le 17899) {
        Get-NetTCPConnection -LocalAddress 127.0.0.1 -LocalPort $port -State Listen |
            ForEach-Object { Kill-Pid $_.OwningProcess }
    }
}

if ($killed.Count -eq 0) { exit 2 }
Write-Output ("killed=" + ($killed -join ","))
exit 0
