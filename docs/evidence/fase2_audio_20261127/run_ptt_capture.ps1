# FASE 2 - Captura controlada de arranque + PTT por deep-link en ambos dispositivos.
# Solo dispara PTT via rbs://ptt; NO sustituye la audicion humana ni la rodada real.
# adb escribe parte de su salida en stderr de forma habitual: se conserva Continue y
# se comprueba LASTEXITCODE explicitamente en cada paso critico.
$ErrorActionPreference = 'Continue'

$repo = 'D:\Ruta-Biker-Sync'
$out = $PSScriptRoot
$apk = "$repo\android\app\build\outputs\apk\standard\debug\app-standard-debug.apk"
if (-not (Test-Path $apk)) { throw "No se encontro $apk; ejecuta assembleStandardDebug primero." }

$devices = @(
    @{ serial = '098813734B105395'; label = 'tecno' },
    @{ serial = 'b2c398bb'; label = 'redmi' }
)

$logPattern = 'AppAudioBootstrap|ConvoySignalAudioHub|BleMeshService|MainActivity|PttAudioEngine|VoicePacket|AudioTrack|AudioRecord|SosToneHub|RBS_FCM'

function Invoke-AdbChecked {
    param([string[]]$Arguments, [string]$LogFile, [string]$Label)
    & adb @Arguments 2>&1 | Out-File -FilePath $LogFile -Append -Encoding utf8
    if ($LASTEXITCODE -ne 0) { throw "[$Label] adb termino con codigo $LASTEXITCODE" }
}

function Wait-Device {
    param([string]$Serial)
    for ($i = 0; $i -lt 20; $i++) {
        $lines = & adb devices 2>&1
        if ($lines | Where-Object { $_ -match "^$Serial\s+device\s*$" }) { return }
        Start-Sleep -Seconds 2
    }
    throw "El dispositivo $Serial no quedo en estado device"
}

function Save-FilteredLog {
    param([string]$InputFile, [string]$OutputFile)
    Select-String -Path $InputFile -Pattern $logPattern | Out-File $OutputFile -Encoding utf8
}

"APK: $apk"
"SHA256: $((Get-FileHash $apk -Algorithm SHA256).Hash)"

foreach ($d in $devices) {
    $serial = $d.serial
    $label = $d.label
    Wait-Device -Serial $serial

    # Limpieza del APK de tests que una seleccion amplia del script anterior instalo.
    & adb -s $serial uninstall com.rutabikersync.app.test 2>&1 |
        Out-File "$out\$label-uninstall-testapk.txt" -Encoding utf8

    Invoke-AdbChecked -Arguments @('-s', $serial, 'install', '-r', $apk) `
        -LogFile "$out\$label-install.txt" -Label "$label install"
    Invoke-AdbChecked -Arguments @('-s', $serial, 'shell', 'am', 'force-stop', 'com.rutabikersync.app') `
        -LogFile "$out\$label-install.txt" -Label "$label force-stop"
    Invoke-AdbChecked -Arguments @('-s', $serial, 'logcat', '-c') `
        -LogFile "$out\$label-install.txt" -Label "$label logcat -c"
    Wait-Device -Serial $serial

    Invoke-AdbChecked -Arguments @('-s', $serial, 'shell', 'am', 'start', '-n', 'com.rutabikersync.app/.MainActivity') `
        -LogFile "$out\$label-launch.txt" -Label "$label launch"
    Start-Sleep -Seconds 5
    Invoke-AdbChecked -Arguments @('-s', $serial, 'logcat', '-d') `
        -LogFile "$out\$label-boot-logcat-raw.txt" -Label "$label boot logcat"
    Save-FilteredLog "$out\$label-boot-logcat-raw.txt" "$out\$label-boot-logcat.txt"

    Invoke-AdbChecked -Arguments @('-s', $serial, 'shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-d', 'rbs://ptt?action=start', '-n', 'com.rutabikersync.app/.MainActivity') `
        -LogFile "$out\$label-ptt-start-intent.txt" -Label "$label PTT start"
    Start-Sleep -Seconds 5
    Invoke-AdbChecked -Arguments @('-s', $serial, 'logcat', '-d') `
        -LogFile "$out\$label-ptt-start-logcat-raw.txt" -Label "$label PTT start logcat"
    Save-FilteredLog "$out\$label-ptt-start-logcat-raw.txt" "$out\$label-ptt-start-logcat.txt"

    Invoke-AdbChecked -Arguments @('-s', $serial, 'shell', 'screencap', '-p', "/sdcard/rbs-$label-ptt-start.png") `
        -LogFile "$out\$label-screencap.txt" -Label "$label screencap start"
    Invoke-AdbChecked -Arguments @('-s', $serial, 'pull', "/sdcard/rbs-$label-ptt-start.png", "$out\$label-ptt-start.png") `
        -LogFile "$out\$label-screencap.txt" -Label "$label pull start"
    Invoke-AdbChecked -Arguments @('-s', $serial, 'shell', 'rm', "/sdcard/rbs-$label-ptt-start.png") `
        -LogFile "$out\$label-screencap.txt" -Label "$label rm start"

    Invoke-AdbChecked -Arguments @('-s', $serial, 'shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-d', 'rbs://ptt?action=stop', '-n', 'com.rutabikersync.app/.MainActivity') `
        -LogFile "$out\$label-ptt-stop-intent.txt" -Label "$label PTT stop"
    Start-Sleep -Seconds 3
    Invoke-AdbChecked -Arguments @('-s', $serial, 'logcat', '-d') `
        -LogFile "$out\$label-ptt-stop-logcat-raw.txt" -Label "$label PTT stop logcat"
    Save-FilteredLog "$out\$label-ptt-stop-logcat-raw.txt" "$out\$label-ptt-stop-logcat.txt"

    Invoke-AdbChecked -Arguments @('-s', $serial, 'shell', 'screencap', '-p', "/sdcard/rbs-$label-ptt-stop.png") `
        -LogFile "$out\$label-screencap.txt" -Label "$label screencap stop"
    Invoke-AdbChecked -Arguments @('-s', $serial, 'pull', "/sdcard/rbs-$label-ptt-stop.png", "$out\$label-ptt-stop.png") `
        -LogFile "$out\$label-screencap.txt" -Label "$label pull stop"
    Invoke-AdbChecked -Arguments @('-s', $serial, 'shell', 'rm', "/sdcard/rbs-$label-ptt-stop.png") `
        -LogFile "$out\$label-screencap.txt" -Label "$label rm stop"

    Invoke-AdbChecked -Arguments @('-s', $serial, 'shell', 'dumpsys', 'package', 'com.rutabikersync.app') `
        -LogFile "$out\$label-package-dumpsys.txt" -Label "$label dumpsys"
    Select-String -Path "$out\$label-package-dumpsys.txt" -Pattern 'versionCode=|versionName=' |
        Out-File "$out\$label-package-version.txt" -Encoding utf8

    Invoke-AdbChecked -Arguments @('-s', $serial, 'shell', 'am', 'force-stop', 'com.rutabikersync.app') `
        -LogFile "$out\$label-install.txt" -Label "$label force-stop final"
    Write-Host "${label}: captura completada"
}
