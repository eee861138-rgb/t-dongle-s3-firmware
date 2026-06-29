Set-StrictMode -Version Latest

if (-not (Get-Command pio -ErrorAction SilentlyContinue)) {
    Write-Host "PlatformIO is not installed. Install it first: https://platformio.org/install"
    exit 1
}

Push-Location "$PSScriptRoot\..\firmware"
try {
    pio run
} finally {
    Pop-Location
}

