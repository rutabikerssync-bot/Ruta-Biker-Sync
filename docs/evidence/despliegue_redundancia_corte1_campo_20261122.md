# Despliegue en campo del corte 1 de redundancia BLE ↔ 4G (2026-11-22 17:05 -04:00)

## 1. Backend piloto

- El servicio del repo base (`D:\Ruta-Biker-Sync`, puerto 3000) **no se detuvo ni se modificó**. `systemctl stop` exigía contraseña y no se usó.
- Se levantó el backend desde `D:\Ruta-Biker-Sync-piloto\backend` en el puerto **3100**, con:
  - `RBS_PILOT_MODE=true`,
  - las dos claves locales existentes en `docs/evidence/piloto_keys_local.txt` (ignorado por git),
  - `ADMIN_API_KEY` generada localmente y conservada fuera del repo,
  - `DATABASE_URL` leída de la configuración local existente (PostgreSQL `127.0.0.1:5433/rbs`).
- Quick tunnel vigente: `https://section-venues-manufacturer-infinite.trycloudflare.com`.
  `GET /health` → **200**.
- Incidencia resuelta: el script `scripts/start-cloudflared-piloto.ps1` tomaba la **primera** URL `trycloudflare.com` de un log acumulado y devolvió un túnel caído. Se cambió para tomar la última URL publicada (`994cdcc`). Los APK se recompilaron con la URL correcta.

## 2. Rodada real

Creada con `POST /v1/rides`, líder y club reales, sin datos inventados:

| Campo | Valor |
|---|---|
| `rideId` | `2de260aa-3e24-493b-b9b8-0846366ba1c5` |
| `hostGroupId` | `e3988eba-26e8-425a-80ac-2a65aeda8484` (`Rutas Demo Bikers`) |
| `hostLeaderId` | `b5d0228d-8ab9-49fd-9cc7-efa452a484f2` (TECNO) |
| participante | `e54bb490-754a-4aa6-9b4c-8b7b043e9dff` (Redmi) |
| estado | `active` |

Verificación previa al despliegue móvil:

- `GET /v1/rides/:id/telemetry?maxAgeSeconds=30&limit=50` → **200** para TECNO y Redmi, con `riders: []`.
- Sin secretos ni UUIDs de sesión impresos en los comandos.

## 3. APK y configuración móvil

- Se añadió `PILOT_RIDE_ID` a `BuildConfig`, inyectado solo con `-Prbs.pilot.rideId` / `RBS_PILOT_RIDE_ID` (`e1ed18f`). No está versionado; release lo deja vacío.
- APKs `standardDebug` recompilados con la URL y el UUID reales:
  - `artifacts/pilotos/piloto-tecno-redundancia-corte1.apk` — 106 316 323 B.
  - `artifacts/pilotos/piloto-redmi-redundancia-corte1.apk` — 106 316 323 B.
- `BuildConfig` verificado en el artefacto final:
  - `BASE_URL = https://section-venues-manufacturer-infinite.trycloudflare.com`
  - `PILOT_RIDE_ID = 2de260aa-3e24-493b-b9b8-0846366ba1c5`
- Instalación con `adb install -r`: **Success** en TECNO `098813734B105395` y Redmi `b2c398bb`.
- Deep link debug reenviado a ambos para fijar la URL persistida. Verificado con `run-as`:
  - `base_url = https://section-venues-manufacturer-infinite.trycloudflare.com`
  - TECNO `user_id = b5d0228d-8ab9-49fd-9cc7-efa452a484f2`
  - Redmi `user_id = e54bb490-754a-4aa6-9b4c-8b7b043e9dff`
- La cabina cargó el roster real del backend (`PARTICIPANTES (2)`), confirmando que el UUID y la ACL funcionan en el móvil.

## 4. Verificación en vivo

Tras abrir «Abrir mapa» en ambos dispositivos:

```json
{
  "rideId": "2de260aa-3e24-493b-b9b8-0846366ba1c5",
  "sampledAt": "2026-11-22T21:05:35.728Z",
  "maxAgeSeconds": 60,
  "riders": [
    {
      "userId": "b5d0228d-8ab9-49fd-9cc7-efa452a484f2",
      "latitude": 10.349272727966309,
      "longitude": -67.03490447998047,
      "observedAt": "2026-11-22T21:05:35.084Z"
    },
    {
      "userId": "e54bb490-754a-4aa6-9b4c-8b7b043e9dff",
      "latitude": 10.349114418029785,
      "longitude": -67.03498077392578,
      "observedAt": "2026-11-22T21:05:34.357Z"
    }
  ]
}
```

- Heartbeats HTTP con `rideId` real recibidos de **ambos** móviles.
- `GET /v1/support/devices/light` → **200**, con los dos dispositivos reales y latidos recientes.
- El volcado UI del TECNO muestra explícitamente **`[4G]`** junto al avatar genérico, con `MALLA ACTIVA` y `4 riders`; BLE sigue activo y no se marcó ningún peer como perdido.
- Evidencia visual:
  - `docs/evidence/pin_4g_tecno_redundancia_corte1_20261122.png`
  - `docs/evidence/pin_4g_redmi_redundancia_corte1_20261122.png`

## 5. Regresión y alcance

- Android: `90 suites / 510 tests, 0 fallos, 0 errores` y `:app:assembleStandardDebug` exitoso.
- Backend: `tsc --noEmit` limpio y `30 archivos / 181 pruebas, 0 fallos` (verificación del corte 1).
- No se tocaron GATT, Ed25519/ChaCha20, `HeartbeatFrame`, PTT, liderazgo, `MapView`, zoom, pan, rotación, estilo ni attribution.
- Los pines HTTP siguen usando `shortId` sintético y perfil redactado: no existe mapeo `userId ↔ shortId`.
- Chat y señales tácticas por 4G siguen diferidos a la fase siguiente con R12/FCM.
- Sin `push`, sin `amend` y sin publicar commits locales.

## 6. Commits de esta entrega

1. `e1ed18f android(piloto): inyectar UUID de rodada real en el build`
2. `994cdcc fix(piloto): seleccionar la ultima URL del tunnel`
3. Commit de evidencia: este documento y las capturas.
