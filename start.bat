@echo off
chcp 65001 > nul
cd /d "%~dp0"
rem 使い方: start.bat [フォルダ]  (省略時は media フォルダ)
rem フォルダをこのファイルにドラッグ＆ドロップしても起動できます
set "FOLDER=%~1"
if "%FOLDER%"=="" set "FOLDER=%~dp0media"
python signage.py "%FOLDER%" --duration 10 --open
pause
