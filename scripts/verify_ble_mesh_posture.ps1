param()
$manifest = "android/app/src/main/AndroidManifest.xml"
if (!(Test-Path $manifest)) { Write-Error "FAIL: falta $manifest"; exit 1 }
$text = Get-Content $manifest -Raw
if ($text -notmatch '\.core\.mesh\.BleMeshService') { Write-Error "FAIL: BleMeshService no encontrado"; exit 1 }
# simple block check
$blockMatch = [regex]::Match($text, '(?s)<service[^>]*android:name="\.core\.mesh\.BleMeshService"[^>]*>.*?</service>')
if (!$blockMatch.Success) { Write-Error "FAIL: bloque BleMeshService no encontrado"; exit 1 }
$block = $blockMatch.Value
if ($block -match 'android:exported="false"') {
  Write-Host "OK: BleMeshService exported=false"
  exit 0
} else {
  Write-Error "FAIL: BleMeshService debe estar exported=`"false`""
  exit 1
}