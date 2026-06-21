@echo off
echo ===================================
echo  Uyghur TTS - C# Build
echo ===================================
echo.

where dotnet >nul 2>&1
if %errorlevel% neq 0 (
    echo ERROR: dotnet CLI not found. Install .NET 6 SDK or later.
    echo https://dotnet.microsoft.com/download
    pause
    exit /b 1
)

echo Restoring NuGet packages...
dotnet restore
if %errorlevel% neq 0 (
    echo ERROR: Package restore failed.
    pause
    exit /b 1
)

echo.
echo Building Release...
dotnet build -c Release
if %errorlevel% neq 0 (
    echo ERROR: Build failed.
    pause
    exit /b 1
)

echo.
echo Build succeeded.
echo Output: bin\Release\net10.0-windows\UighurTTS.exe
echo.
echo To run: dotnet run
echo Or:     bin\Release\net10.0-windows\UighurTTS.exe
pause
