#!/usr/bin/env bash
set -euo pipefail
# T-04/B2-4: verificación estática de postura de BleMeshService en manifest main.
MANIFEST="android/app/src/main/AndroidManifest.xml"
if [ ! -f "$MANIFEST" ]; then echo "FAIL: falta $MANIFEST"; exit 1; fi
if grep -q 'android:name=".core.mesh.BleMeshService"' "$MANIFEST" && \
   awk '/BleMeshService/{found=1} found && /android:exported=\"false\"/{ok=1; exit} found && /<\/service>/{exit} END{exit !ok}' "$MANIFEST"; then
  echo "OK: BleMeshService exported=false"
  exit 0
else
  echo "FAIL: BleMeshService debe estar exported=\"false\""
  exit 1
fi