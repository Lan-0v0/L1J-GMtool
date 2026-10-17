@echo off
chcp 936 >nul
setlocal
cd /d "%~dp0"
set "JAVA_HOME=%~dp0..\..\JDK11"
if not exist "%JAVA_HOME%\bin\javac.exe" (
  echo JDK11 javac not found
  pause
  exit /b 1
)
if not exist "build\classes" mkdir "build\classes"
"%JAVA_HOME%\bin\javac.exe" --release 11 -encoding UTF-8 -cp "lib\mysql-connector-java-5.1.49.jar" -d build\classes src\main\java\local\longtan\gmtool\*.java
if errorlevel 1 (
  echo compile failed
  pause
  exit /b 1
)
xcopy /e /y /q src\main\resources\* build\classes\ >nul
"%JAVA_HOME%\bin\jar.exe" cfe GMTool.jar local.longtan.gmtool.Main -C build\classes .
echo GMTool.jar ok
endlocal