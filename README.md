# Macro Controller for LilyGO T-Dongle S3

Firmware, Android client, local web UI, and server admin panel for sending a small allowlisted macro to a LilyGO T-Dongle S3.



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
