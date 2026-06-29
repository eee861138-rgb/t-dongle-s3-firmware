# Macro Controller for LilyGO T-Dongle S3

Firmware and Android client for sending Ducky Script macros to a LilyGO T-Dongle S3 over Wi-Fi.

## Network

- SSID: `DONGL`
- Password: `dongl1234`
- Device IP: `172.0.0.1`
- Web UI: `http://172.0.0.1`
- Macro endpoint: `POST http://172.0.0.1/macro`
- Status endpoint: `GET http://172.0.0.1/status`

## Supported Commands

- `REM comment`
- `DELAY 1000`
- `DEFAULT_DELAY 80`
- `DEFAULTDELAY 80`
- `DEFAULT_CHAR_DELAY 20`
- `DEFAULTCHARDELAY 20`
- `STRING text`
- `TYPE "text"`
- `REPEAT 3`
- `ENTER`, `TAB`, `ESC`, `BACKSPACE`, `DELETE`, `INSERT`
- `HOME`, `END`, `PAGEUP`, `PAGEDOWN`
- `UP`, `DOWN`, `LEFT`, `RIGHT`
- `CAPSLOCK`, `NUMLOCK`, `PRINTSCREEN`, `SCROLLLOCK`, `PAUSE`
- `F1` through `F24`
- `CTRL`, `ALT`, `SHIFT`, `GUI`, `WIN`, `WINDOWS`, `COMMAND`
- `RCTRL`, `RALT`, `RSHIFT`, `RGUI`
- Numpad keys: `KP_0` through `KP_9`, `KP_ENTER`, `KP_PLUS`, `KP_MINUS`, `KP_SLASH`, `KP_ASTERISK`, `KP_DOT`

## Structure

- `firmware/` - PlatformIO firmware for ESP32-S3.
- `android/` - Android client.
- `scripts/` - Build and upload helper scripts.

## Build Firmware

From the `firmware` directory:

```powershell
pio run
```

Upload to a connected device:

```powershell
pio run --target upload
```

## Build APK

From the `android` directory:

```powershell
gradle assembleDebug
```

The APK is generated at:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

## Example

```text
DELAY 1000
GUI r
DELAY 500
STRING notepad
DELAY 200
ENTER
DELAY 1000
STRING Hello World
```
