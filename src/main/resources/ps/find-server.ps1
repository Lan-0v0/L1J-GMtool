Get-CimInstance Win32_Process |
  Where-Object {
    $_.Name -match '^java' -and
    $_.CommandLine -and
    ($_.CommandLine -match 'Server_Game\.jar' -or $_.CommandLine -match 'com\.lineage\.Server') -and
    $_.CommandLine -notmatch 'l1jserver\.jar' -and
    $_.CommandLine -notmatch 'GMTool'
  } |
  Select-Object -ExpandProperty ProcessId
