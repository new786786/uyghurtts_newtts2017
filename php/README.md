# Uyghur TTS — PHP Web Edition

PHP port of the Uyghur syllable-concatenation TTS engine, with a browser-based GUI.

## Requirements

- PHP 7.4+ with SQLite3 extension enabled
- Data files in the parent directory: `dada.db`, `Lab.dat`, `assets/UKIJTuT.ttf`, `assets/avatar.png`

## Quick Start

```bat
start.bat
```

Then open **http://localhost:8080** in your browser.

## Files

| File | Description |
|------|-------------|
| `UighurTTS.php` | TTS engine class — syllable segmentation, DB lookup, Hanning crossfade, WAV generation |
| `index.php` | Web GUI — RTL textarea, profile selector, AJAX playback |
| `api.php` | POST API endpoint — accepts `{text, profile}`, returns WAV |
| `assets.php` | Serves font/avatar from parent `assets/` directory |
| `start.bat` | Launches PHP built-in server on port 8080 |

## API Usage

```bash
curl -X POST http://localhost:8080/api.php \
  -H "Content-Type: application/json" \
  -d '{"text":"ئۇيغۇرچە","profile":"smooth"}' \
  -o output.wav
```

Profiles: `raw`, `smooth`, `smart`, `prosody`, `hifi`
