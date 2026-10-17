@echo off
chcp 936 >nul
setlocal EnableExtensions
cd /d "%~dp0"

rem ============================================================
rem  L1J GM Tool - REMOTE mode (tunnel agent)
rem  DB   : 127.0.0.1:13306  --ssh tunnel-->  <host>:3306
rem  GM   : 127.0.0.1:17980  --ssh tunnel-->  <host>:17980 (GMAgent)
rem ============================================================

rem ---------- 1. 需要管理员：仅用于结束可能残留的提权实例 ----------
net session >nul 2>&1
if errorlevel 1 (
    echo [*] requesting administrator privileges ...
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b 0
)

rem ---------- 2. 定位 Java 11+ ----------
set "GM_JAVA="
if defined GMT_JAVA set "GM_JAVA=%GMT_JAVA%"
if not defined GM_JAVA if exist "%~dp0..\..\JDK11\bin\javaw.exe" set "GM_JAVA=%~dp0..\..\JDK11\bin\javaw.exe"
if not defined GM_JAVA if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javaw.exe" set "GM_JAVA=%JAVA_HOME%\bin\javaw.exe"
if not defined GM_JAVA for /f "delims=" %%j in ('where javaw 2^>nul') do if not defined GM_JAVA set "GM_JAVA=%%j"
if not defined GM_JAVA (
    echo [ERROR] JDK 11+ not found.
    echo         Install JDK 11+ , or set GMT_JAVA=^<path to javaw.exe^> , or put JDK11 next to the repo ^(..\..\JDK11^).
    pause & exit /b 1
)

rem ---------- 3. 定位 SSH 私钥 ----------
set "PEM="
if defined L1J_PEM set "PEM=%L1J_PEM%"
if not defined PEM if exist "%~dp0keys\L1J_CN.pem" set "PEM=%~dp0keys\L1J_CN.pem"
if not defined PEM if exist "%~dp0..\..\_cloud\L1J_CN.pem" set "PEM=%~dp0..\..\_cloud\L1J_CN.pem"
if not defined PEM (
    echo [ERROR] SSH private key not found.
    echo         Put it at "%~dp0keys\L1J_CN.pem"  ^(git-ignored^), or set L1J_PEM=^<path^>.
    pause & exit /b 1
)

set "SSHHOST=%L1J_SSH_HOST%"
if not defined SSHHOST set "SSHHOST=ubuntu@124.222.172.24"
set "LPORT=13306"
set "APORT=17980"
set "CFGDIR=%~dp0config"

if not exist "%CFGDIR%\sql.properties" (
    if exist "%CFGDIR%\sql.properties.remote" (
        copy /Y "%CFGDIR%\sql.properties.remote" "%CFGDIR%\sql.properties" >nul
        echo [i] config\sql.properties created from sql.properties.remote
    ) else (
        echo [ERROR] missing %CFGDIR%\sql.properties
        pause & exit /b 1
    )
)

echo ============================================================
echo   L1J GM Tool - REMOTE mode
echo   DB   : 127.0.0.1:%LPORT%  --ssh tunnel--^>  %SSHHOST%:3306
echo   AGENT: 127.0.0.1:%APORT%  --ssh tunnel--^>  %SSHHOST%:17980
echo ============================================================
echo.

echo [0/4] fixing private key ACL ^(Windows ssh rejects open permissions^) ...
icacls "%PEM%" /inheritance:r /grant:r "%USERNAME%:R" >nul 2>&1

rem ---------- 4. 结束旧实例（按端口） ----------
for /f "tokens=5" %%p in ('netstat -ano ^| findstr /C:":17880" ^| findstr /C:"LISTENING"') do taskkill /F /PID %%p >nul 2>&1
timeout /t 1 /nobreak >nul

echo [1/4] preparing SSH tunnel 127.0.0.1:%LPORT% + %APORT% ...
set "TUNNEL_UP="
netstat -ano | findstr /C:":%LPORT%" | findstr /C:"LISTENING" >nul
if not errorlevel 1 (
    netstat -ano | findstr /C:":%APORT%" | findstr /C:"LISTENING" >nul
    if not errorlevel 1 set "TUNNEL_UP=1"
)
if defined TUNNEL_UP (
    echo       tunnel already listening, reusing
) else (
    start "L1J-DB-Tunnel" /min ssh -N -L %LPORT%:127.0.0.1:3306 -L %APORT%:127.0.0.1:17980 -i "%PEM%" -o StrictHostKeyChecking=no -o UserKnownHostsFile=NUL -o ServerAliveInterval=30 -o ExitOnForwardFailure=yes %SSHHOST%
    timeout /t 6 /nobreak >nul
)

echo [2/4] checking tunnel ...
netstat -ano | findstr /C:":%LPORT%" | findstr /C:"LISTENING" >nul
if errorlevel 1 (
    echo [ERROR] database tunnel not listening on %LPORT%.
    echo         Check the "L1J-DB-Tunnel" window for the ssh error.
    pause & exit /b 1
)
netstat -ano | findstr /C:":%APORT%" | findstr /C:"LISTENING" >nul
if errorlevel 1 (
    echo [ERROR] agent tunnel not listening on %APORT%.
    echo         Check the "L1J-DB-Tunnel" window for the ssh error.
    pause & exit /b 1
)
echo       tunnel OK

echo [3/4] fetching GMAgent token ...
if not exist "%~dp0data" mkdir "%~dp0data"
ssh -i "%PEM%" -o StrictHostKeyChecking=no -o UserKnownHostsFile=NUL %SSHHOST% "sudo cat /opt/l1j/deploy/agent.token" > "%~dp0data\agent.token" 2>nul
for %%A in ("%~dp0data\agent.token") do if %%~zA LSS 16 (
    echo [ERROR] cannot fetch agent token from server
    echo         expected: /opt/l1j/deploy/agent.token on the server
    pause & exit /b 1
)
echo       token OK

echo [4/4] starting GM Tool (REMOTE / tunnel-agent) ...
start "" "%GM_JAVA%" -Dfile.encoding=UTF-8 -Dgmtool.remote=true -Dgmtool.agent=tunnel -Dgmtool.home="%~dp0." -cp "%~dp0GMTool.jar;%~dp0lib\*" local.longtan.gmtool.Main

echo.
echo [OK] http://127.0.0.1:17880   (tunnel-agent mode)
echo      Keep the "L1J-DB-Tunnel" window open; closing it drops DB + agent link.
timeout /t 5 /nobreak >nul
endlocal
exit /b 0
