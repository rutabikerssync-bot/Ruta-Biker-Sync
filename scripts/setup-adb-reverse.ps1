# Ruta Biker Sync - Script de configuracion de tunel ADB Reverse
# Mapea el puerto 3000 (Backend Fastify) a todos los dispositivos Android conectados por USB

$adb = "C:\Users\ozmar\AppData\Local\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) {
    $adb = "adb"
}

Write-Host "[INFO] Detectando dispositivos Android conectados..." -ForegroundColor Cyan
$rawDevices = & $adb devices
$devices = $rawDevices | Select-String "device$" | ForEach-Object { ($_ -split "`t")[0] }

if (-not $devices) {
    Write-Host "[WARN] No se detectaron dispositivos conectados por ADB." -ForegroundColor Yellow
    exit 0
}

foreach ($dev in $devices) {
    Write-Host "[CONFIG] Configurando tunel 'adb reverse tcp:3000 tcp:3000' en dispositivo $dev..." -ForegroundColor Green
    & $adb -s $dev reverse tcp:3000 tcp:3000
    if ($LASTEXITCODE -eq 0) {
        Write-Host "  [OK] Puerto 3000 enrutado con exito a $dev" -ForegroundColor Green
    } else {
        Write-Host "  [ERROR] Error enrutando puerto en $dev" -ForegroundColor Red
    }
}
Write-Host "[SUCCESS] Enrutamiento ADB Reverse completado." -ForegroundColor Cyan
