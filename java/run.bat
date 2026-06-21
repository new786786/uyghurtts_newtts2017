@echo off
chcp 65001 >nul 2>&1
cd /d "%~dp0"
java -cp ".;sqlite-jdbc-3.46.0.0.jar;slf4j-api-2.0.9.jar;slf4j-nop-2.0.9.jar" -Dfile.encoding=UTF-8 UighurTTS
