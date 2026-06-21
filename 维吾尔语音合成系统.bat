@echo off
chcp 65001 >nul
cd /d "%~dp0"
set "PYTHONPATH=%~dp0lib;%PYTHONPATH%"
set "PYW=pythonw.exe"
where pythonw.exe >nul 2>nul || set "PYW=C:\Program Files\python\pythonw.exe"
start "" "%PYW%" "%~dp0tts_gui.py"
