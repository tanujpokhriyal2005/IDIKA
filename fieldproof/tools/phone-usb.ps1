# Connects a USB phone to the local Fieldproof backend and installs the app.
#   powershell -ExecutionPolicy Bypass -File tools\phone-usb.ps1
# Run it again whenever you re-plug the phone (the port link is lost on unplug).
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$apk = Join-Path $root "android\app\build\outputs\apk\debug\app-debug.apk"

Write-Host "1/4 Backend (Docker)..."
Push-Location $root
docker compose up -d | Out-Null
Pop-Location
for ($i = 0; $i -lt 30; $i++) {
    try { Invoke-RestMethod http://localhost:8080/healthz -TimeoutSec 2 | Out-Null; break } catch { Start-Sleep 2 }
}
Write-Host "    backend is up at http://localhost:8080 (admin: admin / fieldproof)"

Write-Host "2/4 Phone..."
$phones = & $adb devices | Select-String "`tdevice\s*$" | Where-Object { $_ -notmatch "emulator" }
if (-not $phones) { throw "No phone found. Plug in USB, unlock it, and allow USB debugging." }
$serial = ($phones[0].Line -split "`t")[0].Trim()
Write-Host "    using $serial"

Write-Host "3/4 Linking phone port 8080 to this laptop (adb reverse)..."
& $adb -s $serial reverse tcp:8080 tcp:8080 | Out-Null

Write-Host "4/4 Installing app..."
if (-not (Test-Path $apk)) {
    Push-Location (Join-Path $root "android")
    $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
    .\gradlew.bat :app:assembleDebug
    Pop-Location
}
& $adb -s $serial install -r -t $apk
& $adb -s $serial shell am start -n org.fieldproof/.MainActivity | Out-Null
Write-Host "Done. Log in with 98765 43210; the OTP fills in by itself."
