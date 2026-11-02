@echo off
rem Rebuilds the app and puts the runnable exe in .\ssh-key-checker\ssh-key-checker.exe
setlocal
cd /d "%~dp0"

rem Gradle needs a full JDK (with jpackage). Android Studio's JBR lacks it, so look for another.
if not exist "%JAVA_HOME%\bin\jpackage.exe" (
  for /d %%D in ("C:\Program Files\Eclipse Adoptium\jdk-*" "C:\Program Files\Java\jdk-*") do if exist "%%~D\bin\jpackage.exe" set "JAVA_HOME=%%~D"
)
if not exist "%JAVA_HOME%\bin\jpackage.exe" (
  echo No JDK with jpackage found. Install JDK 17+ and set JAVA_HOME.
  pause
  exit /b 1
)

call "%~dp0gradlew.bat" createDistributable --console=plain "-Dorg.gradle.java.home=%JAVA_HOME%"
if errorlevel 1 (
  echo Build failed.
  pause
  exit /b 1
)

if exist ssh-key-checker rmdir /s /q ssh-key-checker
xcopy /e /i /q "build\compose\binaries\main\app\ssh-key-checker" ssh-key-checker >nul
echo.
echo Done: %~dp0ssh-key-checker\ssh-key-checker.exe
pause
