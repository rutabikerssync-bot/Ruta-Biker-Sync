# Estado de requisitos de Google Play — 2026-11-27

**Conclusión operativa:** la app **NO está lista para publicar** al cierre de esta sesión. Quedan acciones que solo puede ejecutar el propietario en Play Console/Render. Esta tabla se actualiza con evidencia verificable; no se inventan resultados de Console.

| Requisito | Estado | Bloqueante | Acción |
|---|---|---|---|
| 1. Target SDK 36 | `CUMPLE` | Ninguno para el código (el AVD no arranca en este entorno; se validó en dispositivo físico API 36) | `compileSdk`/`targetSdk`=36; 131 suites / 714 tests Android en verde; `b2c398bb` reporta SDK 36 |
| 2. AAB firmado | `CUMPLE` | Ninguno (instalación en dispositivo físico bloqueada por firma debug previa; no se desinstaló) | `bundleStandardRelease` exitoso; certificado B5:19…4F:36 verificado en AAB y APK universal |
| 3. Gate de release | `CUMPLE` | Ninguno | `verify_release_security.ps1` con `RBS_PROD_URL=https://ruta-biker-sync.onrender.com` → `verify_release_security: PASS`, sin `FAIL_PLACEHOLDER` |
| 4. Privacidad y Data Safety | `PARCIAL` | `/privacy.html` devuelve 404 en Render; Data Safety no enviada; cuenta de revisor `NO ENCONTRADO` | Build copia `public` a `dist/public`; texto in-app corregido y ruta Perfil → Ajustes → «Privacidad y términos» validada en API 36; tras push/redeploy verificar URL y cargar `data_safety.md` |
| 5. IARC | `BORRADOR` | La clasificación la emite Play Console | Enviar `iarc.md` y documentar el resultado real |
| 6. Ficha y capturas | `BORRADOR` | Faltan icono 512×512 y gráfico 1024×500; upload en Console | Usar `listing.md` y revisar capturas existentes |
| 7. Prueba cerrada | `NO INICIADA` | Se requieren 12 testers y 14 días continuos; acceso a Console del propietario | Seguir `closed_test.md` |
| 8. Documentación y commit | `CUMPLE` | Ninguno | `HISTORY_NOTE.md` e índice de evidencia cerrados; commit atómico sin `push` |

## Evidencias

- `docs/evidence/google_play_publicacion_20261127/`: salud del backend, páginas legales, gate, firma y capturas.
- `docs/play_store/data_safety.md`, `iarc.md`, `listing.md`, `closed_test.md`: borradores exactos para copiar en Console.
- Fuentes oficiales: [Target API](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en), [testing de cuentas personales](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en).
