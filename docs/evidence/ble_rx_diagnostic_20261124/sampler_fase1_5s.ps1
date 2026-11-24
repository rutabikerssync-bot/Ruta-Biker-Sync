$ErrorActionPreference = 'Continue'
$dir = 'D:\Ruta-Biker-Sync\docs\evidence\ble_rx_diagnostic_20261124'
$serials = @{ TECNO = '098813734B105395'; REDMI = 'b2c398bb' }
$deadline = (Get-Date).AddMinutes(8)

while ((Get-Date) -lt $deadline) {
  $ts = Get-Date -Format 'HH:mm:ss'
  foreach ($name in @('TECNO','REDMI')) {
    $serial = $serials[$name]
    $out = Join-Path $dir "fase1_${name}_ui_20261124.txt"
    $tmp = "C:\Users\ozmar\AppData\Local\Temp\opencode\fase1_ui_${name}.xml"
    $ok = $true
    try {
      & adb -s $serial shell uiautomator dump /sdcard/rbs_ui.xml 2>$null | Out-Null
      & adb -s $serial pull /sdcard/rbs_ui.xml $tmp 2>$null | Out-Null
      if (-not (Test-Path $tmp)) { $ok = $false }
    } catch { $ok = $false }
    if ($ok) {
      $raw = Get-Content -Raw $tmp
      $texts = @([regex]::Matches($raw,'text="([^"]+)"') | ForEach-Object { $_.Groups[1].Value } |
        Where-Object { $_ -match 'rider|riders|MALLA|señal|Sin señal|En línea|Fuera de malla' } | Select-Object -Unique)
      "$ts $($texts -join ' | ')" | Out-File $out -Append -Encoding utf8
    } else {
      "$ts UI_DUMP_ERROR" | Out-File $out -Append -Encoding utf8
    }
  }
  Start-Sleep -Seconds 3
}
