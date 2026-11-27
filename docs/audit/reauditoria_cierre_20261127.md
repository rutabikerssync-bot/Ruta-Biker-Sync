# Re-auditoría de cierre — 2026-11-27

**Línea base:** `docs/audit/auditoria_integral_20261127.md` (avance 79,85 % ≈ 79,9 %; seguridad 68/100; Google Play `NO LISTO`).
**Commits incorporados:** `a56ace1` (Fase 1), `86c1635` + `daae6e1` (Fase 2), `46b1805` (Fase 3), `1813c52` (Fase 4). Sin `amend` ni `push`.
**Método:** idéntico a la auditoría integral §4.1 (`G = (I + T + H)/3`; *Cifrado y seguridad* usa la puntuación MASVS de §6.1 con sus pesos publicados; `Avance = Σ(G × peso)`). No se cambia la rúbrica para inflar el resultado.

## 1. Avance ponderado actualizado

| Categoría | I | T | H | G | Peso | Aporte | Cambio frente a la línea base |
|---|---:|---:|---:|---:|---:|---:|---|
| Malla BLE/GATT | 98 | 98 | 65 | **87,0** | 25 % | 21,75 | Antes 85 / 21,25. Límite dinámico 8/4/2 implementado y testeado (Fase 3); sin Prueba D post-fix, H intacto. |
| Cifrado y seguridad | — | — | — | **74** | 20 % | 14,80 | Antes 68 / 13,60. Detalle en §2: C1/C2/C3 cerrados en código, sin ataques BLE en hardware. |
| UI/UX | 95 | 95 | 80 | **90,0** | 15 % | 13,50 | Sin cambios. |
| Backend/API | 95 | 70 | 90 | **85,0** | 15 % | 12,75 | Sin cambios: `tsc --noEmit` limpio, pero la suite completa sigue bloqueada por PostgreSQL en `:5433`. |
| Fase 5 RSVP offline | 95 | 95 | 80 | **90,0** | 10 % | 9,00 | Sin cambios. |
| Audio/PTT | 92 | 75 | 45 | **70,7** | 10 % | 7,07 | Antes 60 / 6,00. Bootstrap global y foco en cabina cableados; arranque y ciclo PTT observados en TECNO y REDMI; sin audición humana ni voz extremo a extremo. |
| Documentación | 90 | 85 | 65 | **80,0** | 5 % | 4,00 | Antes 75 / 3,75. `HISTORY_NOTE` al día, checklist de Console y recursos legales; publicación/Console siguen pendientes. |
| **TOTAL** | | | | | **100 %** | **82,87** | **Avance = 82,9 %** |

**Resultado:** 79,9 % → **82,9 %**. La mejora es documental y de código verificable; no equivale a validación de campo.

## 2. Seguridad actualizada (MASVS, mismos pesos)

| Dominio | Peso | Antes | Ahora | Justificación del cambio |
|---|---:|---:|---:|---|
| Almacenamiento / claves | 15 % | 78 | 78 | Sin cambios. KEK sin `setUserAuthenticationRequired`; hardware keystore no verificado en dispositivo. |
| Tránsito / TLS | 10 % | 82 | 82 | Sin cambios. HTTPS + `networkSecurityConfig`; sin certificate pinning. |
| Autenticación / autorización | 20 % | 58 | **80** | Fase 1: `requireAuthorization=true` en producción, clave de 32 ceros rechazada, ACK REST antes de BLE, identidad Ed25519 por participante. Descuento: sin pruebas de adversario activo en hardware. |
| Criptografía / integridad | 15 % | 66 | **72** | Sobre firmado `KIND_SIGNED_CONTROL = 0x05` y firma por participante. Permanecen HMAC de 4 B y `shortId` de 15 bits. |
| Protección de código / build | 10 % | 80 | 80 | Sin cambios; R8 y gates, sin anti-tamper/root detection. |
| Anti-replay / anti-tampering | 15 % | 62 | 62 | Sin cambios: guards en memoria y sin timestamp firmado. |
| Privacidad / logs | 15 % | 64 | 64 | Sin cambios relevantes: el log nuevo de Fase 3 no incluye `shortId`s, pero el inventario de redacción sigue desactualizado. |

Cálculo: `0,15×78 + 0,10×82 + 0,20×80 + 0,15×72 + 0,10×80 + 0,15×62 + 0,15×64 = 73,6` → **74/100** (antes 68/100, +6).

**Conclusión:** la malla ya no usa la clave conocida ni el gate REST→BLE documentado solo en tests, y la suplantación por clave simétrica compartida quedó mitigada en código. No se puede afirmar resistencia frente a adversario activo: faltan ataques BLE en hardware, anti-replay persistente y pinning.

## 3. Estado por área

| Área | Estado | Evidencia |
|---|---|---|
| Avance | **82,9 %** | Tabla §1; tests Android 130 suites / 711 tests en verde. |
| Seguridad | **74/100** | Tabla §2; golden de Fase 1 (20 B, HMAC 4 B, clave de ceros rechazada, sin manifiesto falla cerrado). |
| Google Play | **NO LISTO** | `compileSdk`/`targetSdk`=35, sin API 36; `FAIL_PLACEHOLDER`; sin AAB; recursos legales preparados pero no desplegados; Console `PENDIENTE`. |
| Audio | **PARCIAL / VERIFICADO EN ARRANQUE** | `AppAudioBootstrap` en ambos móviles; `rbs://ptt?action=start|stop` produce `PttAudioEngine` start/stop; `VoicePacketTx descartado: no hay relayEngine activo`. Audición humana y Prueba D `PENDIENTE`. |
| Peers | **MITIGADO EN CÓDIGO / SIN VALIDACIÓN DE CAMPO** | 8/4/2 por perfil, roster completo preservado, 13 tests nuevos; sin recaptura de pérdida/recuperación BLE real. |

## 4. NO ENCONTRADO / PENDIENTE al cierre

- **NO ENCONTRADO:** API 36 instalada, AAB firmado, política desplegada en HTTPS, formularios enviados en Console, cuenta/sign-in de revisor, 12 testers/14 días, evidencia de ataque BLE en hardware, persistencia de anti-replay, certificate pinning, root/tamper detection, fallback de peers por HTTP 4G, chat híbrido malla→FCM, causa funcional definitiva de la pérdida de audio, audición humana del PTT.
- **PENDIENTE:** Prueba D en carretera (20-30 min, 30-60 km/h, pérdida/recuperación BLE y SOS en marcha), reinstalación de ambos APK en lockstep antes de campo, `RBS_PROD_URL` real, secretos de firma, Data Safety/IARC/audiencia/anuncios/ficha/capturas, suite backend completa (PostgreSQL `:5433`), actualización de `zod` (alta en runtime) y salto major de Vitest con revalidación.
- **NO se modificó en las cinco fases:** GATT/UUIDs, `HeartbeatFrame`, cifrado más allá del sobre firmado autorizado, PTT más allá del bootstrap/foco autorizado y lógica de liderazgo.

## 5. Reproducción

```powershell
# Android (tests verdes)
cd android; .\gradlew.bat :app:testStandardDebugUnitTest --rerun-tasks --no-daemon

# Recursos legales (sin PostgreSQL)
cd backend; npx vitest run --no-file-parallelism test/public_legal_resources.test.ts
cd backend; npm run typecheck

# Gate de release (bloqueante intencional)
.\scripts\verify_release_security.ps1

# Estado de fases
git --no-pager log --oneline -5
```
