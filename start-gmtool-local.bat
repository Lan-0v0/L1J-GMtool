@echo off
chcp 936 >nul
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

set "PS_CHECK=$p=@(Get-CimInstance Win32_Process | Where-Object { ($_.Name -eq 'javaw.exe' -or $_.Name -eq 'java.exe') -and ($_.CommandLine -like '*local.longtan.gmtool.Main*') }); if ($p.Count -gt 0) { exit 0 } else { exit 1 }"
set "PS_KILL=$p=@(Get-CimInstance Win32_Process | Where-Object { ($_.Name -eq 'javaw.exe' -or $_.Name -eq 'java.exe') -and ($_.CommandLine -like '*local.longtan.gmtool.Main*') }); $p | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }"

echo [INFO] Checking if GM Tool is running...

powershell -NoProfile -ExecutionPolicy Bypass -Command "%PS_CHECK%"
set "IS_RUNNING=!ERRORLEVEL!"

if "!IS_RUNNING!"=="0" (
    echo [INFO] GM Tool is running. Closing it...

    if exist "%~dp0close-gmtool.ps1" (
        powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0close-gmtool.ps1"
        echo [INFO] Close script exit code: !ERRORLEVEL!
    ) else (
        echo [WARN] close-gmtool.ps1 not found. Will kill process directly.
    )

    timeout /t 1 /nobreak >nul

    powershell -NoProfile -ExecutionPolicy Bypass -Command "%PS_CHECK%"
    set "STILL_RUNNING=!ERRORLEVEL!"

    if "!STILL_RUNNING!"=="0" (
        echo [WARN] GM Tool still running. Force killing...
        powershell -NoProfile -ExecutionPolicy Bypass -Command "%PS_KILL%"
        timeout /t 2 /nobreak >nul
    )
) else (
    echo [INFO] GM Tool is not running.
)

echo [INFO] Starting GM Tool...

set "GM_JAVA=%~dp0..\..\JDK11\bin\javaw.exe"
if not exist "!GM_JAVA!" set "GM_JAVA=%~dp0..\..\JDK11\bin\java.exe"
if not exist "!GM_JAVA!" (
    echo [ERROR] JDK11 not found: %~dp0..\..\JDK11
    pause
    exit /b 1
)

if not exist "%~dp0GMTool.jar" (
    echo [ERROR] missing GMTool.jar, run Брвы.bat first
    pause
    exit /b 1
)

start "" "!GM_JAVA!" -Dfile.encoding=UTF-8 -Dgmtool.home="%~dp0." -cp "%~dp0GMTool.jar;%~dp0lib\*" local.longtan.gmtool.Main

echo [OK] GM Tool started.
endlocal
exit /b 0