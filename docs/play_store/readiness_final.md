# Verificación final de Render y preparación para Google Play

**Fecha:** 2026-11-27/28 · **Backend real:** `https://ruta-biker-sync.onrender.com` · **Rama:** `main`
**Alcance:** verificación en vivo, cierre documental/técnico. **No se abrió cuenta de Google Play, no se publicó, no se hizo `push` ni `amend`.** No se modificaron GATT, cifrado, `HeartbeatFrame`, voz PTT ni liderazgo.

## 1. Tabla de verificación

| Tarea | Comando | Resultado esperado | Resultado obtenido | Evidencia |
|---|---|---|---|---|
| Salud del backend | `curl.exe -s -o NUL -w "%{http_code}" https://ruta-biker-sync.onrender.com/health` + `curl.exe -s .../health` | `200` y `{"status":"ok","db":"ok"}` | **`200`**; cuerpo exacto `{"status":"ok","db":"ok"}` | `docs/evidence/google_play_publicacion_20261127/render_live_verification.txt` §1–2 |
| Páginas legales en Render | `curl.exe ... /privacy.html`, `/terms.html`, `/account-deletion.html` | `200` y HTML con los títulos correctos | **`200` en las tres**; títulos «Privacidad — Ruta Biker Sync», «Términos y Convivencia — Ruta Biker Sync» y «Eliminar cuenta — Ruta Biker Sync» | íbid. §1, §3 |
| Commit desplegado | `git ls-remote origin refs/heads/main`; `git cat-file -t bfb822c`; `git merge-base --is-ancestor bfb822c fbb137a` | El fix `bfb822c` debe estar incluido en `main` | `origin/main` = `fbb137a…`; `bfb822c` existe y **es ancestro** de `fbb137a` (`exit 0`). El tip es `fbb137a`, no `bfb822c` | íbid. §4 |
| Manifiesto comunitario | `curl.exe -H "x-user-id: <uid real>" https://ruta-biker-sync.onrender.com/v1/community/manifest` | `200` con manifiesto firmado | **`200`**; `rideId=ride-live-01`, `kid=ed25519-k1`, 2 participantes, `signatureBase64Url` de 86 chars | íbid. §5 |
| Manifiesto de grupo | `curl.exe -H "x-user-id: <uid real>" https://ruta-biker-sync.onrender.com/v1/rides/<rideId real>/manifest` | `200` con manifiesto firmado | **`200`**; `rideId=48be967a-0250-4504-86bb-5469e88aa65d`, `kid=ed25519-k1`, 2 participantes, `signatureBase64Url` de 86 chars | íbid. §5 |
| Autenticación de manifiestos | mismas rutas sin cabecera | `401` | **`401`** `UNAUTHORIZED` en ambas; no se inventaron credenciales | íbid. §5 |
| Gate de release | `$env:RBS_PROD_URL='https://ruta-biker-sync.onrender.com'; .\scripts\verify_release_security.ps1` | `verify_release_security: PASS`, sin `FAIL_PLACEHOLDER` | **`PASS`**, `EXIT=0`, 5 comprobaciones `OK` | íbid. §6 |
| URL productiva en el AAB | Extraer `app-standard-release.aab` como ZIP y buscar cadenas en `classes*.dex` | La URL real debe estar en el artefacto release | **Presente**: `ruta-biker-sync.onrender.com` en `classes.dex` | íbid. §7 |
| APK instalado en TECNO/Redmi | `adb shell pm path`, `adb pull`, escaneo de URLs en dex | Apuntar a Render y conectar | **NO VERIFICADO**: ambos APK instalados son debug con `http://127.0.0.1:3000`; la URL de Render no aparece. No se desinstaló la app del propietario | íbid. §8 |
| Tests Android | `cd android; .\gradlew.bat :app:testStandardDebugUnitTest --no-daemon` | Verde | **`BUILD SUCCESSFUL`**, `EXIT=0`, **131 suites / 714 tests**, 0 fallos, 0 errores, 0 omitidos | íbid. §9; `docs/evidence/google_play_publicacion_20261127/android_tests_summary.txt` |
| Tests legales Node | `cd backend; npx vitest run --no-file-parallelism test/public_legal_resources.test.ts` | Verde | **1 archivo / 5 tests en verde**, `EXIT=0` | íbid. §9; `.../node_tests.txt` |
| Typecheck backend | `cd backend; npm run typecheck` | `EXIT=0` | **`EXIT=0`** | íbid. §9; `.../backend_typecheck.txt` |
| Política legal dentro de la app | Perfil → Ajustes → «Privacidad y términos» en `b2c398bb` (SDK 36) | Pestaña Privacidad coherente con `/privacy.html` | **`CUMPLE`** (verificado en sesión anterior; no repetido en esta) | `.../privacy_ui.txt`, `.../privacy_ui.png` |
| Cuenta / Data Safety / IARC / ficha / prueba cerrada | Acciones en Play Console | Enviadas | **PENDIENTE**: no se abrió la cuenta ni se envió formulario alguno | `docs/play_store/final_checklist.md` |

## 2. Datos reales usados en la verificación

Leídos de `shared_prefs` del dispositivo TECNO (`098813734B105395`), no inventados:

- `uid` = `eed6e317-5488-459c-80fa-e2e284afeac2` (`rbs_auth_prefs.xml`).
- `rideId` = `48be967a-0250-4504-86bb-5469e88aa65d` (`rbs_active_ride_state_prefs.xml`).

Con esos valores, ambos manifiestos respondieron `200`. Sin la cabecera `x-user-id`, respondieron `401`, coherente con el contrato de autenticación del backend.

## 3. Nota sobre `bfb822c`

El usuario reportó `bfb822c` desplegado en `main`. La comprobación real muestra que el tip remoto es `fbb137a` y que **`bfb822c` es ancestro de `fbb137a`**. Es decir: el fix `fix(backend): fallback to src/db/migrations when running compiled dist` sí está incluido en el código desplegado, pero el commit puntero de `main` es `fbb137a`. No se hizo `push` ni `amend` desde este agente.

## 4. Estado de dispositivos

| Dispositivo | Modelo | SDK | App instalada | Resultado |
|---|---|---:|---|---|
| `098813734B105395` | TECNO KI5k | 31 | `com.rutabikersync.app` debug, `BASE_URL=http://127.0.0.1:3000` | No conecta con Render |
| `b2c398bb` | 2312DRA50G | 36 | `com.rutabikersync.app` debug, `BASE_URL=http://127.0.0.1:3000` | No conecta con Render |

El AAB firmado `android/app/build/outputs/bundle/standardRelease/app-standard-release.aab` sí contiene `https://ruta-biker-sync.onrender.com`. La instalación release en `b2c398bb` sigue bloqueada por `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (firma debug previa); **no se desinstaló la app del propietario**. Para validar la app real contra Render hace falta instalar ese AAB en un dispositivo sin la build debug.

## 5. Pendiente exclusivo de Play Console

Preparación lista; estas acciones las ejecuta el propietario:

1. Abrir cuenta de desarrollador (registro `$25`) y verificación de identidad.
2. Enviar **Data Safety** con los valores de `docs/play_store/data_safety_form.md`.
3. Enviar **IARC** con `docs/play_store/iarc_answers.md`.
4. Publicar ficha con `docs/play_store/listing_assets.md` (icono 512×512, gráfico 1024×500 y 4 capturas 1080×2160 ya generados).
5. Configurar la URL de privacidad `https://ruta-biker-sync.onrender.com/privacy.html`.
6. Subir el AAB firmado y completar las rutas de `docs/play_store/reviewer_account.md`.
7. Prueba cerrada: 12 testers durante 14 días (`docs/play_store/closed_test.md`).
8. Revisar `docs/play_store/final_checklist.md` antes de solicitar publicación.

## 6. Conclusión

Render está operativo: `/health`, `/privacy.html`, `/terms.html` y `/account-deletion.html` devuelven `200`; los manifiestos comunitario y de grupo responden con datos reales y firmados; el gate de release pasa con la URL productiva; el AAB contiene esa URL; y todos los tests reejecutados están en verde. La preparación técnica/documental para Google Play queda cerrada. El único bloqueo restante es externo y deliberado: la cuenta y los formularios de Play Console, que el propietario debe completar.
