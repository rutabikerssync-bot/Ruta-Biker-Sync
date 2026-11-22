# Despliegue del piloto en dispositivos reales (2026-11-22)

Rama `feat/piloto-seguridad`. Backend en WSL (puerto 3100, PostgreSQL 16 en
`127.0.0.1:5433`) y Cloudflare quick tunnel.

## Claves y artefactos

- Dos claves aleatorias de 41 caracteres generadas localmente, con prefijos
  `TECNO-01-` y `REDMI-01-`. No se versionan: están en
  `docs/evidence/piloto_keys_local.txt` (ignorado por git).
- Etiquetas de build: `PILOTO-TECNO-2026` y `PILOTO-REDMI-2026`.
- APKs `standardDebug` compilados con `RBS_PILOT_KEY` / `RBS_PILOT_BUILD`:
  - `artifacts/pilotos/piloto-tecno.apk` (95.5 MB)
  - `artifacts/pilotos/piloto-redmi.apk` (95.7 MB)
  - `artifacts/` está ignorado por git.
- Instalados con `adb install -r` en TECNO `098813734B105395` y Redmi
  `b2c398bb`.

## Configuración remota

Deep link debug enviado a cada móvil:

```
adb -s <serial> shell am force-stop com.rutabikersync.app
adb -s <serial> shell "am start -a android.intent.action.VIEW \
  -d 'rbs://debug/set-base-url?url=<URL codificada>&key=<clave>&build=<etiqueta>' \
  -n com.rutabikersync.app/.MainActivity"
```

Logcat confirmó `Deep Link rbs://debug/set-base-url aplicado=true build=…` en
ambos dispositivos.

## Verificación

`GET /v1/support/devices/light` con clave de piloto y
`GET /v1/support/devices` con `x-admin-key` muestran los dos dispositivos reales:

| Build | Marca | Modelo | Peers | Último latido (UTC) |
|---|---|---|---|---|
| `PILOTO-TECNO-2026` | TECNO | KI5k-GL | 2 | 2026-11-22T07:18:15Z |
| `PILOTO-REDMI-2026` | Redmi | 2312DRA50G | 1 | 2026-11-22T07:22:44Z |

Huellas de clave: `b8fa43db3a8c` (TECNO) y `0fcd792e3158` (Redmi). Las claves
secretas no aparecen en esta evidencia.

También se verificó `POST /v1/support/device-telemetry` a través del túnel con
respuesta 201 y `GET /health` con respuesta 200.

## Correcciones posteriores (misma fecha)

1. **Chat táctico ausente.** La rama piloto partía de `bceab59` y no incluía los
   4 commits de `feat/paso-a-ui`. Se integraron mediante merge limpio
   (`cfa6b05`): textura `biker_chat_pattern.png`, Dark Elite, avatares reales,
   dedup, botonera auto-ocultable, 3A/3B y paneles Club/Individual. Los APKs
   finales (105 MB) incluyen estos cambios.
2. **Banner «Fastify offline» en el club.** Los móviles tenían guardada la URL
   antigua del túnel (`groundwater-invited-third-originally…`) en
   `rbs_pilot_prefs.xml`. El deep link se procesaba en `onCreate` antes de
   `PilotTelemetry.install()`, por lo que `appContext` era null y
   `applyRemoteConfig()` actualizaba la configuración en memoria pero no la
   persistía; al reiniciar se restauraba la URL caída. Se invirtió el orden
   (`3e031dd`) y se reenvió el deep link. Verificado con `run-as`:
   ```
   base_url = https://represents-runs-qualifications-direction.trycloudflare.com
   ```
   en ambos dispositivos.

## Notas operativas

- La URL del quick tunnel cambia en cada arranque; al cambiar, basta reenviar el
  deep link (no hace falta recompilar).
- El backend debe arrancarse con ambas claves en `RBS_PILOT_ALLOWED_KEYS`.
- La validación de separación real 5–50 m queda planificada: requiere mover los
  móviles físicamente.
- Los logs temporales de diagnóstico se revirtieron; los APKs finales están
  compilados desde el código limpio con Paso A y el fix de persistencia.
