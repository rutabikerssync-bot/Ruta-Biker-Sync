# Informe final de las cinco fases — 2026-11-27

**Repositorio:** `D:\Ruta-Biker-Sync` · **Rama:** `main` · **Push:** NO realizado (el responsable lo ejecuta desde su consola).

## 1. Avance actualizado

| Métrica | Línea base (2026-11-27) | Cierre | Evidencia |
|---|---:|---:|---|
| Avance ponderado | 79,9 % | **82,9 %** | `docs/audit/reauditoria_cierre_20261127.md` §1 |
| Seguridad MASVS | 68/100 | **74/100** | `docs/audit/reauditoria_cierre_20261127.md` §2 |
| Tests Android | 126 suites / 693 tests | **130 suites / 711 tests / 0 fallos / 0 errores / 0 skipped** | `docs/evidence/fase5_cierre_20261127/android_tests.txt` |
| Tests legales Node | NO ENCONTRADO | **4/4 tests** | `docs/evidence/google_play_fase4_20261127/public_legal_resources_test.log` |

**Commits atómicos (sin `amend`):**

| Commit | Fase | Contenido |
|---|---|---|
| `a56ace1` | 1 | Malla fail-closed, ACK REST antes de BLE e identidad Ed25519. |
| `86c1635` | 2 | Bootstrap global de audio y foco en la cabina. |
| `daae6e1` | 2 | Evidencia adb de arranque y PTT en TECNO/REDMI. |
| `46b1805` | 3 | Tope de enlaces GATT cliente 8/4/2 por perfil de energía. |
| `1813c52` | 4 | Recursos legales públicos y checklist ejecutable de Play Console. |
| *(este cierre)* | 5 | Re-auditoría, informe final y evidencia de verificación. |

## 2. Seguridad actualizada

- Cerrados en código: clave legacy de 32 ceros y `requireAuthorization=false` en producción; el evento de ciclo ahora espera ACK REST antes de BLE; suplantación de líder mitigada con Ed25519 por participante y sobre firmado `KIND_SIGNED_CONTROL = 0x05`.
- Intactos por decisión/documento: HMAC de 4 bytes, `shortId` de 15 bits, guards anti-replay en memoria, sin certificate pinning, sin root/tamper detection.
- **Puntuación: 74/100** (68 → 74). No se afirma resistencia frente a adversario activo: no hay pruebas de ataque BLE en hardware ni anti-replay persistente.
- `npm audit` sigue con 11 vulnerabilidades (2 críticas, 2 altas — `zod` en runtime — y 7 moderadas). No se aplicaron cambios de dependencias porque la suite backend completa sigue bloqueada por PostgreSQL `:5433`; queda como `PENDIENTE` con su comando de validación.

## 3. Estado de Google Play

**NO LISTO.** Bloqueantes verificados:

1. `compileSdk`/`targetSdk` = 35 y solo `android-35`/`android-37.0` instalados; API 36 exigible desde 2026-08-31 **NO aplicada**.
2. `verify_release_security.ps1` → 3 `OK` y `FAIL_PLACEHOLDER` (`https://rbs-pending.invalid`). Sin `RBS_PROD_URL` real, sin secretos de firma configurados y sin AAB en disco.
3. Existen `backend/public/privacy.html`, `terms.html` y `account-deletion.html` con enlaces reales desde la portada, pero **no están desplegados en HTTPS**; no hay evidencia de Data Safety enviada, IARC, anuncios, audiencia, sign-in de revisor, ficha/capturas ni prueba cerrada de 12 testers / 14 días.

Orden ejecutable y estado de cada requisito: `docs/audit/play_console_checklist_20261127.md`. Evidencia: `docs/evidence/google_play_fase4_20261127/README.md`.

## 4. Estado del audio

- **Verificado:** `AppAudioBootstrap` presente en TECNO (`098813734B105395`) y REDMI (`b2c398bb`); logs `I AppAudioBootstrap: hub de audio inicializado con alcance de aplicacion`.
- **Verificado:** ciclo `rbs://ptt?action=start|stop` produce `ACTION_PTT_START`/`ACTION_PTT_STOP`, `PttAudioEngine: PTT: Iniciando transmisión de audio...` y `Transmisión finalizada por el usuario.` Capturas y logs en `docs/evidence/fase2_audio_20261127/`.
- **NO verificado:** audición humana por casco, botones físicos, inteligibilidad y recepción en el otro móvil con rodada real. Durante el PTT aparece `VoicePacketTx descartado: no hay relayEngine activo en RideService`: no se validó voz extremo a extremo.
- **PENDIENTE:** Prueba D en carretera y reinstalación de ambos APK en lockstep (el wire/policy cambió en Fase 1).

## 5. Estado de estabilidad de peers

- **Mitigado en código:** `PowerProfile.maxGattPeerLinks` = 8 / 4 / 2 y `GattPeerBudgetPolicy.cap()` recorta el roster autorizado antes de `planFor()`; el roster completo permanece en el estado y el relay. No se tocaron GATT, UUIDs ni `HeartbeatFrame`.
- **Verificado:** 8 tests de política en `GattPeerBudgetPolicyTest.kt` y 5 de integración en `RideServiceGattPeerBudgetTest.kt` (130 suites / 711 tests globales en verde). Log `GattPeerBudget profile=… autorizados=… objetivosGatt=…` sin `shortId`s.
- **NO verificado:** pérdida y recuperación BLE real, RX asimétrico de REDMI, latencia por salto y SOS en marcha. Sigue `PENDIENTE` la Prueba D post-fix; por ello la estabilidad en campo se califica como **mitigada en código, sin validación de carretera**.

## 6. Cierre

Todo lo ejecutable sin hardware, PostgreSQL ni Play Console está implementado, testeado y documentado. Lo que depende de esos entornos queda marcado `PENDIENTE`/`NO VERIFICABLE` con comandos o pasos concretos; no se inventaron resultados. No se realizó `push`.
