@echo off
chcp 65001 >nul 2>&1
setlocal

set SQLITE_VERSION=3.46.0.0
set SQLITE_JAR=sqlite-jdbc-%SQLITE_VERSION%.jar
set SQLITE_URL=https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/%SQLITE_VERSION%/%SQLITE_JAR%

if not exist "%SQLITE_JAR%" (
    echo [1/3] Downloading SQLite JDBC driver v%SQLITE_VERSION% ...
    curl.exe -L -o "%SQLITE_JAR%" "%SQLITE_URL%"
    if errorlevel 1 (
        echo curl.exe failed, trying PowerShell ...
        powershell -Command "Invoke-WebRequest -Uri '%SQLITE_URL%' -OutFile '%SQLITE_JAR%'"
    )
    if not exist "%SQLITE_JAR%" (
        echo ERROR: Failed to download %SQLITE_JAR%
        echo Please download manually from: %SQLITE_URL%
        pause
        exit /b 1
    )
    echo Done.
) else (
    echo [1/3] SQLite JDBC driver found.
)

echo [2/3] Compiling UighurTTS.java ...
javac -encoding UTF-8 -cp ".;%SQLITE_JAR%;slf4j-api-2.0.9.jar;slf4j-nop-2.0.9.jar" UighurTTS.java
if errorlevel 1 (
    echo ERROR: Compilation failed.
    pause
    exit /b 1
)
echo Done.

echo [3/3] Running UighurTTS ...
java -cp ".;%SQLITE_JAR%;slf4j-api-2.0.9.jar;slf4j-nop-2.0.9.jar" -Dfile.encoding=UTF-8 UighurTTS
