@echo off
chcp 65001 > nul
rem Windows 版をビルドして ..\dist\Signage.exe に出力します
cd /d "%~dp0"
if not exist .venv (
  python -m venv .venv || (echo Python 3.10 以降をインストールしてください & pause & exit /b 1)
)
call .venv\Scripts\python -m pip install -q -r requirements.txt || (pause & exit /b 1)
rem 管理画面は Android 版と共通のものを使う
copy /y ..\android\app\src\main\assets\admin.html web\admin.html > nul
call .venv\Scripts\pyinstaller --noconfirm --clean --onefile --windowed --name Signage --icon icon.ico ^
  --add-data "web;web" --hidden-import pystray._win32 app.py || (pause & exit /b 1)
if not exist ..\dist mkdir ..\dist
copy /y dist\Signage.exe ..\dist\Signage.exe > nul
echo.
echo 完成: %~dp0..\dist\Signage.exe
pause
