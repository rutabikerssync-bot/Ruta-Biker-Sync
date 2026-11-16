#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { echo "OK: $*"; }

# 5 condiciones: cleartext, networkSecurityConfig, BleMeshService, BASE_URL https, seeds
# Artefacto requerido (merged manifest o APK/AAB release)
MERGED=""
for cand in \
  "android/app/build/intermediates/merged_manifests/standardRelease/AndroidManifest.xml" \
  "android/app/build/intermediates/merged_manifest/standardRelease/AndroidManifest.xml" \
  "android/app/build/intermediates/merged_manifests/standardRelease/processStandardReleaseManifest/AndroidManifest.xml"; do
  [ -f "$cand" ] && MERGED="$cand" && break
done
if [ -z "$MERGED" ]; then
  MERGED=$(find android/app/build -type f -path "*standardRelease*merged*AndroidManifest.xml" 2>/dev/null | head -n1 || true)
fi
ARTIFACT=""
ARTIFACT=$(find android/app/build/outputs -type f \( -name "*standardRelease*.apk" -o -name "*standardRelease*.aab" \) 2>/dev/null | head -n1 || true)
[ -n "$MERGED" ] || [ -n "$ARTIFACT" ] || fail "falta artefacto de standardRelease (merged manifest o APK/AAB). Genera el build primero."

# cleartext + networkSecurityConfig
MANIFEST_SRC="android/app/src/main/AndroidManifest.xml"
CHECK_MANIFEST="$MERGED"
[ -z "$CHECK_MANIFEST" ] && CHECK_MANIFEST="$MANIFEST_SRC"
grep -q 'android:usesCleartextTraffic="true"' "$CHECK_MANIFEST" && fail "cleartext habilitado en $CHECK_MANIFEST"
grep -q 'networkSecurityConfig' "$CHECK_MANIFEST" || fail "networkSecurityConfig ausente en $CHECK_MANIFEST"
ok "sin cleartext y networkSecurityConfig presente ($CHECK_MANIFEST)"

# BleMeshService
grep -q 'android:name=".core.mesh.BleMeshService"' "$CHECK_MANIFEST" || fail "BleMeshService no encontrado en $CHECK_MANIFEST"
if grep -q 'BleMeshService' "$CHECK_MANIFEST"; then
  awk '/BleMeshService/{f=1} f&&/android:exported="false"/{ok=1} f&&/<\/service>/{exit} END{exit ok?0:1}' "$CHECK_MANIFEST" || fail "BleMeshService no está exported=\"false\""
fi
ok "BleMeshService exported=false"

# BASE_URL https
BC="android/app/build/generated/source/buildConfig/standard/release/com/rutabikersync/app/BuildConfig.java"
if [ -f "$BC" ]; then
  grep -q 'String BASE_URL = "https://' "$BC" || fail "BuildConfig standardRelease BASE_URL no es https"
  ok "BASE_URL https en BuildConfig generado"
else
  grep -q 'TODO_EXTERNAL_URL_PROD' android/app/build.gradle.kts && grep -q 'buildConfigField("String", "BASE_URL", "https://' android/app/build.gradle.kts || fail "BASE_URL release no configurado como https (y BuildConfig generado ausente)"
  ok "BASE_URL release configurado como https (placeholder TODO_EXTERNAL:URL_PROD)"
fi

# seeds
if grep -RIn --exclude-dir=build "StrongLeaderPass!2026\|StrongMemberPass!2026" android/app/src/main >/dev/null 2>&1; then
  fail "credenciales seed encontradas en android/app/src/main"
fi
ok "sin credenciales seed en src/main"

echo "verify_release_security: PASS"