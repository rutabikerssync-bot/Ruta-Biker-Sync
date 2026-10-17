# Ruta Biker Sync

App Android para grupos de moteros con mesh Bluetooth, mapa offline, voz por clips y suscripciones.

- `android/`  — app Kotlin + Jetpack Compose
- `backend/`  — API de licencias y cobros (Node + TypeScript + PostgreSQL)
- `infra/`    — docker-compose y despliegue
- `docs/`     — documentos de gobernanza (RULES.md es la fuente de verdad)
- `field-tests/` — guion y resultados de pruebas de campo BLE (Fase 0)
- `tools/`    — utilidades (BRouter opcional)

## Primeros pasos
1. `cd infra && docker compose up -d` (PostgreSQL local)
2. `cd backend && cp .env.example .env && npm run dev`
3. Abrir `android/` en Android Studio, o `cd android && .\gradlew.bat assembleDebug testDebugUnitTest`

Licencia: código cerrado, todos los derechos reservados.
