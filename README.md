# Macro Controller for LilyGO T-Dongle S3

Firmware, Android client, local web UI, and server admin panel for sending a small allowlisted macro to a LilyGO T-Dongle S3.

## Allowed Commands

Only these commands are accepted:

- `DELAY 1000` - wait 0-5000 ms.
- `GUI r` - open Run.
- `STRING text` - type text up to 160 characters.
- `ENTER` - press Enter.

Example:

```text
DELAY 1000
GUI r
DELAY 500
STRING notepad
DELAY 200
ENTER
DELAY 1000
```

## Blocked Commands

Everything outside the allowlist is blocked, including:

- `HOTKEY`, `TYPE`, `REPEAT`.
- `TAB`, `ESC`, `BACKSPACE`, `DELETE`, `INSERT`.
- `CTRL`, `ALT`, `SHIFT`, `WIN`, `WINDOWS`, `META`, `COMMAND`.
- `DEFAULT_DELAY`, `DEFAULT_CHAR_DELAY`.
- Arrow keys, `HOME`, `END`, `F1-F24`, numpad keys.
- Text containing `cmd.exe`, `powershell`, `terminal`, `curl`, `wget`, URLs, or similar commands.

## Endpoints

- Local macro endpoint: `POST http://172.0.0.1/macro`
- Cloud queue endpoint: `POST /api/macro/run` with JSON body `{"macro":"...","device_id":"optional"}`
- Dongle polling endpoint: `GET /api/dongle/poll?device_id=...`
- Admin page: `/admin`

## Build Firmware

```powershell
cd firmware
pio run
```

Upload:

```powershell
pio run --target upload
```

## Build APK

```powershell
cd android
gradle assembleDebug
```
