@echo off
chcp 65001 > nul
rem APK をビルドして ..\dist\signage.apk に出力します
cd /d "%~dp0"
set "JAVA_HOME=%LOCALAPPDATA%\android-build-tools\jdk-17.0.20.1+1"
call gradlew.bat assembleDirectDebug || (pause & exit /b 1)
if not exist ..\dist mkdir ..\dist
copy /y app\build\outputs\apk\direct\debug\app-direct-debug.apk ..\dist\signage.apk
echo.
echo 完成: %~dp0..\dist\signage.apk
pause
