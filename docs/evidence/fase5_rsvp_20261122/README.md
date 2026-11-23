# Fase 5 · Evidencia hardware RSVP offline

Fecha: 22–23 nov 2026. Rama: `feat/logo-oficial-escala`. Build verificado: `PILOTO-TECNO-2026` / `PILOTO-REDMI-2026` (APK `standardDebug`, `app-standard-debug.apk`, SHA-256 `293394554D70E5AF2E3523F194FA1BF6287F3330F3149067A90A7C8A47E9A3AF`).

## Dispositivos

| Dispositivo | Serial | base_url | Usuario de club | Rol observado |
|---|---|---|---|---|
| TECNO | `098813734B105395` | `http://127.0.0.1:3000` | `b5d0228d-…484f2` | Rutero Bikers (Presidente) |
| Redmi | `b2c398bb` | `http://127.0.0.1:3000` | `e54bb490-…9dff` | The Developer (Miembro) |

Backend: Fastify en `http://127.0.0.1:3000`, `/health` → `{"status":"ok"}`. Túnel: `adb -s <serial> reverse tcp:3000 tcp:3000`.

## Hitos capturados

| Hito | Archivo | Resultado |
|---|---|---|
| APK instalada y deep link aplicado | `tecno_00_installed.png`, `tecno_00_installed.xml` | Preferencias `base_url`, `pilot_key` y `pilot_build` correctas |
| Roster pendiente antes de RSVP | `tecno_01_pending_roster.xml`, `tecno_02_participants_pending.png` | Botones `Asistiré` / `No asistiré` en la fila propia; fila ajena solo lectura |
| RSVP con red | `tecno_03_rsvp_online.png`, `tecno_03_rsvp_online.xml` | UI pasa a `En el sitio ✓`; backend registra `confirmed` |
| RSVP sin red | `tecno_04_offline_banner.png`, `tecno_04_offline_banner.xml` | Banner `⏳ Pendiente de sincronizar (1 RSVP)`; backend sigue `pending` |
| Red restaurada sin túnel | `tecno_05_network_back_pending.xml` | Worker reintenta y conserva el banner |
| Reconexión completa | `tecno_06_resynced.png`, `tecno_06_resynced.xml`, `tecno_07_final_online.xml` | Outbox vacío, banner ausente, backend `no_go` |
| Worker | `tecno_worker_resync.log` | `RETRY`, `RETRY`, `SUCCESS` para el mismo work id |
| Outbox | `tecno_outbox_states.txt` | `synced=0`, `attempts=2`, `lastError=Failed to connect to /127.0.0.1:3000` → sin filas |
| Backend | `backend_roster_checks.txt` | `/v1/routes/:id/roster` confirma cada transición |
| Redmi con red | `redmi_00_initial_roster.xml`, `redmi_01_routes.xml`, `redmi_02_rsvp_online.png`, `redmi_02_rsvp_online.xml`, `redmi_logcat_rsvp.txt` | RSVP `Asistiré` en `26678aee-…0289e`; worker `SUCCESS`; backend `confirmed` para `e54bb490-…9dff` |

Ruta usada en TECNO: `6935451a-86e7-40c8-b245-5a298cf74500` (`starts_at=2026-11-23T20:11:00.000Z`, fuera de la ventana de bloqueo de 1 h). Ruta usada en Redmi: `26678aee-73df-402c-9493-1e663f70289e`.

## Comandos de verificación

```powershell
# Estado y deep link
adb -s 098813734B105395 shell run-as com.rutabikersync.app cat shared_prefs/rbs_pilot_prefs.xml
adb -s 098813734B105395 shell "am start -a android.intent.action.VIEW -d 'rbs://debug/set-base-url?url=http%3A%2F%2F127.0.0.1%3A3000&key=<clave>&build=<etiqueta>' -n com.rutabikersync.app/.MainActivity"

# Caída de red y captura
adb -s 098813734B105395 reverse --remove tcp:3000
adb -s 098813734B105395 shell cmd connectivity airplane-mode enable
adb -s 098813734B105395 shell uiautomator dump /sdcard/ui.xml
adb -s 098813734B105395 exec-out screencap -p > captura.png

# Outbox (copiar DB + WAL + SHM para consistencia)
adb -s 098813734B105395 exec-out run-as com.rutabikersync.app cat databases/ruta_biker_sync.db > db/ruta_biker_sync.db
sqlite3 db/ruta_biker_sync.db "SELECT routeId,targetStatus,synced,attempts,lastError FROM rsvp_outbox;"

# Reconexión y backend
adb -s 098813734B105395 shell cmd connectivity airplane-mode disable
adb -s 098813734B105395 reverse tcp:3000 tcp:3000
adb -s 098813734B105395 logcat -d -v time | Select-String RsvpOutboxWorker
Invoke-RestMethod http://127.0.0.1:3000/v1/routes/6935451a-86e7-40c8-b245-5a298cf74500/roster -Headers @{ 'x-user-id'='b5d0228d-8ab9-49fd-9cc7-efa452a484f2' }
```

La UI Compose no expone `clickable=true` en `uiautomator dump`; la navegación se hizo por coordenadas. El túnel `adb reverse` es la conectividad del emulador/dispositivo con el backend local; al retirarlo, `nc 127.0.0.1 3000` devuelve `Connection refused`, lo que confirma el escenario offline sin depender de la cobertura móvil.
