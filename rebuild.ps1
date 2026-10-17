$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here
$jdk11 = Join-Path $here "..\..\JDK11\bin"
$jdk8 = Join-Path $here "..\JDK8\bin"
if (-not (Test-Path (Join-Path $jdk8 "javac.exe"))) { $jdk8 = Join-Path $here "..\..\JDK8\bin" }
$out = Join-Path $here "build\classes"
$agentOut = Join-Path $here "build\agent-classes"
$src = Join-Path $here "src\main\java\local\longtan\gmtool"
# Wipe output dirs first. A deleted source (e.g. ServerAgent3.java) would otherwise survive as a
# stale .class in build\ and get packed into GMAgent.jar / GMTool.jar.
# Keep this file pure ASCII: powershell.exe 5.1 reads BOM-less files as ANSI.
Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force $agentOut -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $out | Out-Null
New-Item -ItemType Directory -Force -Path $agentOut | Out-Null

$toolFiles = Get-ChildItem $src -Filter *.java | Where-Object {
  $_.Name -ne "ProbeCodec.java" -and $_.Name -notlike "Dump*.java" -and $_.Name -notlike "Check*.java" -and $_.Name -notlike "Insert*.java"
} | ForEach-Object { $_.FullName }
& (Join-Path $jdk11 "javac.exe") --release 11 -encoding UTF-8 -cp "lib\mysql-connector-java-5.1.49.jar" -d $out $toolFiles
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$agentSrc = Join-Path $src "agent\ServerAgent.java"
$agentJavac = Join-Path $jdk8 "javac.exe"
if (Test-Path $agentJavac) {
  & $agentJavac -encoding UTF-8 -d $agentOut $agentSrc
} else {
  & (Join-Path $jdk11 "javac.exe") --release 8 -encoding UTF-8 -d $agentOut $agentSrc
}
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Copy-Item -Recurse -Force (Join-Path $here "src\main\resources\*") $out
$mf = Join-Path $here "build\agent.mf"
if (-not (Test-Path $mf)) {
  # agent.mf is a required INPUT (jar manifest for the agent). Regenerate if a cleanup wiped build\.
  @(
    "Manifest-Version: 1.0",
    "Agent-Class: local.longtan.gmtool.agent.ServerAgent",
    "Can-Redefine-Classes: true",
    "Can-Retransform-Classes: true"
  ) | Set-Content -Encoding ASCII $mf
}
& (Join-Path $jdk11 "jar.exe") cfm GMAgent.jar $mf -C $agentOut .
if ($LASTEXITCODE -ne 0) { Write-Error "GMAgent.jar packaging failed"; exit 1 }
& (Join-Path $jdk11 "jar.exe") cfe GMTool.jar local.longtan.gmtool.Main -C $out .
if ($LASTEXITCODE -ne 0) {
  Write-Error "GMTool.jar packaging failed: the jar is probably locked by a running GMtool. Run close-gmtool.ps1 first."
  exit 1
}
Write-Output "jar ok"
