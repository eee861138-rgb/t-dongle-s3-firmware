Set-StrictMode -Version Latest

if (-not (Get-Command gradle -ErrorAction SilentlyContinue)) {
    Write-Host "Gradle is not installed or not in PATH. Open the android folder in Android Studio and build the APK there."
    exit 1
}

Push-Location "$PSScriptRoot\..\android"
try {
    gradle assembleDebug
} finally {
    Pop-Location
}

