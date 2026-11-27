# Evidencia de Fase 4 — Google Play (2026-11-27)

Todo se ejecutó en `D:\Ruta-Biker-Sync`, sin `push` y sin secretos reales. Los archivos `.log`/`.txt` son transcripción literal de la salida; no se inventaron resultados de Play Console.

| Comando | Resultado | Archivo |
|---|---|---|
| `cd android; .\gradlew.bat :app:testStandardDebugUnitTest --rerun-tasks --no-daemon` | `BUILD SUCCESSFUL`; 130 suites / 711 tests / 0 fallos / 0 errores / 0 skipped | `android_tests.txt` |
| `cd android; .\gradlew.bat :app:processStandardReleaseManifest --no-daemon` | `BUILD SUCCESSFUL`; manifest fusionado `standardRelease` disponible | `process_standard_release_manifest.log` |
| `.\scripts\verify_release_security.ps1` | 3 controles OK; `FAIL_PLACEHOLDER` por `BASE_URL=https://rbs-pending.invalid`; `EXIT_CODE=1` | `verify_release_security.log` |
| `cd backend; npm audit --json` | 11 vulnerabilidades: 2 críticas (`vitest`, `tinypool`), 2 altas (`vite`, `zod` en runtime), 7 moderadas | `npm_audit.json` |
| `cd backend; npx vitest run --no-file-parallelism test/public_legal_resources.test.ts` | 1 archivo / 4 tests en verde; `EXIT_CODE=0` | `public_legal_resources_test.log` |

## Hallazgos verificados

- `compileSdk`/`targetSdk` siguen en 35 (`android/app/build.gradle.kts:15,46`). Solo están instalados `android-35` y `android-37.0`: **API 36 NO aplicable todavía**.
- El keystore release existe fuera del repo (`C:\rbs-secrets\rbs-release-v2.jks`), pero no hay secretos configurados ni URL productiva: **no se generó AAB**.
- Los recursos legales `privacy.html`, `terms.html` y `account-deletion.html` existen en `backend/public/` y la portada ya no deja enlaces `#` en el bloque «Seguridad & Ley». **No están desplegados en una URL HTTPS**.
- `CHECKLIST.md` dejó de marcar como completados la política publicada, Data Safety enviada y permiso de ubicación en segundo plano.

## PENDIENTE (no verificable desde el repositorio)

Pago/identidad de desarrollador, creación de la app en Console, SDK 36 y firma con secretos, `RBS_PROD_URL` real, AAB firmado y upload, formularios de Data Safety/IARC/audiencia/anuncios/sign-in, ficha y capturas, prueba cerrada de 12 testers / 14 días y publicación. Detalle ejecutable en `docs/audit/play_console_checklist_20261127.md`.

`zod` (alta, dependencia de ejecución) y el salto major de Vitest quedan registrados para Fase 5; no se cambiaron dependencias en esta fase.
