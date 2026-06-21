# Uyghur TTS — C# Implementation

C# / WinForms port of the Python Uyghur syllable-concatenation TTS system.

## Prerequisites

- **.NET 6 SDK** (or later) — [download](https://dotnet.microsoft.com/download)
- Data files in the parent directory (`G:\dbuy_asr\tts\newtts2017\`):
  - `dada.db` — SQLite syllable database
  - `Lab.dat` — raw PCM audio corpus
  - `assets\avatar.png` — header avatar image
  - `assets\UKIJTuT.ttf` — UKIJ Tuz Tom Uyghur font

## Build & Run

```batch
cd csharp
build.bat
```

Or manually:

```batch
dotnet restore
dotnet run
```

## Synthesis Profiles

| Profile | Description |
|---------|-------------|
| 原始 (raw) | 30ms crossfade, first-match unit |
| 平滑增强 (smooth) | 50ms crossfade + RMS energy normalization |
| 智能选音 (smart) | Multi-candidate join-cost unit selection |
| 韵律自然 (prosody) | Punctuation pauses + sentence-final declination |
| 高保真 (hifi) | TD-PSOLA pitch smoothing (most natural) |

## Project Structure

- `TTSEngine.cs` — Core TTS engine (syllable segmentation, DB lookup, crossfade, PSOLA)
- `UighurTTSApp.cs` — WinForms GUI
- `Program.cs` — Entry point
- `UighurTTS.csproj` — Project file
- `build.bat` — Build script
