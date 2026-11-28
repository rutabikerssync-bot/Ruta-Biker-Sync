# Evidencia — Requisitos de Google Play (2026-11-27)

Los resultados se registrarán aquí con evidencia verificable. `NO ENCONTRADO`/`PENDIENTE` significa que no hay resultado comprobable; no se inventan resultados de Play Console.

| Verificación | Comando | Resultado | Archivo |
|---|---|---|---|
| URL productiva | `Invoke-WebRequest https://ruta-biker-sync.onrender.com/health` | HTTP 200, `{"status":"ok","db":"ok"}` | `backend_endpoints.txt` |
| Páginas legales en Render | `Invoke-WebRequest .../privacy.html`, `.../terms.html`, `.../account-deletion.html` | HTTP 404 antes del despliegue | `backend_endpoints.txt` |
| Build backend con recursos legales | `cd backend; npm run build` | `EXIT_CODE=0`; `dist/public/privacy.html` existe | `backend_build.txt` |
| Tests legales Node | `cd backend; npx vitest run --no-file-parallelism test/public_legal_resources.test.ts` | 1 archivo / 5 tests en verde, EXIT=0 | `node_tests.txt` |
| Typecheck backend | `cd backend; npm run typecheck` | `EXIT_CODE=0` | `backend_typecheck.txt` |
| SDK 36 e imagen API 36 | `sdkmanager --install platforms;android-36 system-images;android-36;google_apis;x86_64 emulator platform-tools` | `SDKM_EXIT=0`; AVD creado (no arranca tras 25 min); dispositivo físico API 36 usable | `sdk_install.txt`, `emulator_api36.txt` |
| Tests Android con target 36 | `cd android; .\gradlew.bat :app:testStandardDebugUnitTest :app:bundleStandardRelease --no-daemon` | `BUILD SUCCESSFUL`; 131 suites / 714 tests, 0 fallos | `android_tests_summary.txt`, `final_android_build.log` |
| Gate de release | `.\scripts\verify_release_security.ps1` con `RBS_PROD_URL=https://ruta-biker-sync.onrender.com` | `verify_release_security: PASS`, sin `FAIL_PLACEHOLDER`, EXIT=0 | `verify_release_security.txt` |
| AAB firmado | `cd android; .\gradlew.bat :app:bundleStandardRelease --no-daemon` | `BUILD SUCCESSFUL`, EXIT=0 (reconstruido tras corregir texto legal) | `bundle_release.log`, `final_android_build.log` |
| Certificado del AAB | `keytool -printcert -jarfile ...` / `jarsigner -verify` | SHA256 B5:19…4F:36 coincide; `jar verified` | `aab_signature.txt` |
| APK instalable desde AAB | `bundletool build-apks --mode=universal` + `apksigner verify` | Universal generado (100,52 MB), firma release verificada, targetSdk=36 | `aab_install.txt`, `bundletool_universal.log` |
| Instalación release en dispositivo | `adb install` en `b2c398bb` | `INSTALL_FAILED_UPDATE_INCOMPATIBLE`: la app instalada tiene firma debug; **no se desinstaló** | `aab_install.txt` |
| Emulador API 36 | Arranque y `getprop ro.build.version.sdk` | `RBS_API36` creado pero `sys.boot_completed` nunca=1; se usó dispositivo físico SDK 36 | `emulator_api36.txt` |
| Política desde Perfil | UI en dispositivo API 36: Perfil → Ajustes → «Privacidad y términos» | `CUMPLE`: pestaña Privacidad, encabezado de política y texto coherente con el manifest; 0 afirmaciones de permiso en segundo plano | `privacy_ui.txt`, `privacy_ui.png` |
