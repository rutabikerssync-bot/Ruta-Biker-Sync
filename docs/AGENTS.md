# Ruta Biker Sync — Invariantes del Proyecto

## Reglas absolutas (nunca violar)

- No modificar GATT, cifrado (ChaCha20/Ed25519), HeartbeatFrame ni PTT sin autorización explícita.
- No tocar la lógica de liderazgo (`group_leaders`, `canPublishClubRoute`).
- No inventar datos de peers (nombre, avatar, rol, posición GPS). Usar solo datos reales del backend o fallbacks honestos.
- No hacer `amend` ni `push` sin instrucción explícita.
- No mezclar cambios de fases anteriores en commits nuevos.

## Stack y comandos

- Android: `.\gradlew.bat :app:testStandardDebugUnitTest` (nunca en paralelo con PostgreSQL).
- Backend: `PG_TEST_URL=postgres://... npm test` (base aislada `rbs_test`).
- MapLibre 11.5.2 (no añadir Mapbox SDK externo).

## Ubicación de la documentación

- Plan de trabajo detallado: `docs/NEXT_AGENT_WORKPLAN.md`
- Historial de decisiones: `docs/HISTORY_NOTE.md`
- Evidencia de pruebas: `docs/evidence/`
