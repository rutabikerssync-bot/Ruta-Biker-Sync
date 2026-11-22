# Piloto remoto sin Tailscale

Este documento describe el acceso seguro al backend durante el piloto con
`túnel HTTPS de Cloudflare (quick tunnel)`. No sustituye al manifiesto firmado
de rodada ni a la autorización Ed25519 de la malla.

## 1. Gate de acceso al API

El backend acepta una lista blanca de claves, una por probador/dispositivo:

```bash
RBS_PILOT_MODE=true
RBS_PILOT_ALLOWED_KEYS=TECNO-01-<valor-aleatorio>,REDMI-01-<valor-aleatorio>
RBS_PILOT_REVOKED_KEYS=
ADMIN_API_KEY=<clave-admin-larga-y-aleatoria>
RBS_PILOT_PUBLIC_ORIGINS=
RBS_PILOT_ALLOW_TRYCLOUDFLARE=true
```

- Cada clave debe tener entre 16 y 256 caracteres, sin espacios.
- Toda petición `/v1/*` debe llevar `x-rbs-pilot-key: <clave>`.
- Están exentos: `/health`, los archivos estáticos, `/` y
  `POST /v1/support/report` (formulario público de soporte).
- Con `RBS_PILOT_MODE=true` y lista vacía el backend **no arranca** (fail-closed).
- Revocar: mover la clave a `RBS_PILOT_REVOKED_KEYS` (403 inmediato) o
  retirarla de `RBS_PILOT_ALLOWED_KEYS` (401) y reiniciar el backend.
- En modo piloto, el backoffice exige `ADMIN_API_KEY` real: si falta, responde
  `503 ADMIN_KEY_NOT_CONFIGURED` en vez de usar la clave de desarrollo.
- El limitador de velocidad se agrupa por huella de clave de piloto, para que
  cambiar `x-user-id` no permita evadirlo.
- CORS solo acepta orígenes `https://*.trycloudflare.com`, los declarados en
  `RBS_PILOT_PUBLIC_ORIGINS` y localhost. Los clientes nativos no envían
  `Origin`, por lo que no se ven afectados.

Las claves nunca se guardan en el repositorio. En los phones se entregan
mediante un build de piloto o un deep link local, nunca por el formulario
público de soporte.

## 2. Estados de error

| Código | Error | Significado |
|---|---|---|
| 401 | `PILOT_KEY_REQUIRED` | Falta la cabecera o la clave no está en la lista blanca |
| 403 | `PILOT_KEY_REVOKED` | La clave fue revocada explícitamente |
| 503 | `ADMIN_KEY_NOT_CONFIGURED` | Falta `ADMIN_API_KEY` en modo piloto |

## 3. Monitoreo por dispositivo

Cada APK de piloto envía a `POST /v1/support/device-telemetry` su etiqueta de
build (cabecera `x-rbs-pilot-build`), modelo, versión, batería, número de peers
y, cuando ocurre, excepciones con stack trace y las últimas líneas de log.
La identidad del dispositivo es la huella SHA-256 truncada de su clave de
piloto; no se envían UIDs, coordenadas GPS ni datos médicos. El backend vuelve
a redactar correos, tokens, UUIDs y coordenadas antes de almacenar.

- `GET /v1/support/devices/light`: vista resumida para la página pública,
  protegida con la clave de piloto; no incluye stacks ni logs.
- `GET /v1/support/devices`: vista completa del backoffice; exige
  `x-admin-key` (no hay clave admin por defecto: sin `ADMIN_API_KEY` responde
  503).
- `GET /v1/support/devices/:fp/events`: historial de un dispositivo.

La tabla `pilot_devices` conserva el último error hasta que llega uno nuevo;
los latidos solo actualizan estado y `last_seen_at`.

## 4. Configuración del móvil (build debug)

El APK de piloto lee de `BuildConfig` la clave y la etiqueta de build, que se
inyectan al compilar con `-Prbs.pilot.key=... -Prbs.pilot.build=TECNO-01` o las
variables `RBS_PILOT_KEY` / `RBS_PILOT_BUILD`. Los builds release dejan ambos
campos vacíos: no se distribuye ninguna clave en production.

La URL del túnel cambia en cada arranque de `cloudflared`, así que se puede
actualizar sin recompilar mediante un deep link local (solo debug):

```bash
adb shell am start -a android.intent.action.VIEW \
  -d 'rbs://debug/set-base-url?url=https://abc-123.trycloudflare.com&key=<clave>&build=TECNO-01' \
  com.rutabikersync.app/.MainActivity
```

El deep link guarda la configuración en `SharedPreferences`, la aplica al
cliente HTTP y reinicia los latidos. El reportero envía un latido por minuto,
captura excepciones no manejadas (y las reenvía en el próximo arranque si el
proceso muere) y adjunta las últimas líneas del logcat de ese PID con correos,
tokens, UUIDs y coordenadas redactados en el propio móvil.

## 5. Servicio web de soporte

`/soporte` está servido por el propio backend, así que funciona igual en
`http://localhost:3000/soporte` (laptop) que en la URL HTTPS del túnel. Incluye:

- formulario de reporte anónimo (asunto, mensaje y captura ≤2 MB) que escribe
  en `support_reports` y aparece en el backoffice;
- FAQ y contacto `soporte@rutabikersync.com`;
- vista ligera de dispositivos: pide la clave de piloto en el navegador y
  muestra build, dispositivo, batería, peers, último latido y último error;
  nunca muestra stacks ni logs.

El backoffice `/admin` ya no contiene claves ni datos de demostración: la clave
admin se introduce en la pestaña Ajustes y se conserva solo en `sessionStorage`.
Si el backend no responde, los paneles muestran el error en claro en vez de
datos falsos. La pestaña «Dispositivos del Piloto» muestra el detalle completo
de dispositivos y eventos (solo con `x-admin-key`).

## 6. Arranque operativo desde la laptop

```powershell
$env:RBS_PILOT_MODE='true'
$env:RBS_PILOT_ALLOWED_KEYS='TECNO-01-<secreto>,REDMI-01-<secreto>'
$env:ADMIN_API_KEY='<clave-admin-larga-y-aleatoria>'
$env:AUTH_TOKEN_SECRET='<secreto-hmac-largo>'
$env:DATABASE_URL='postgres://rbs:rbs_dev_password@localhost:5432/rbs'

.\\scripts\\start-cloudflared-piloto.ps1
```

El script valida que no falten secretos, levanta el backend, espera a `/health`,
abre el quick tunnel y guarda la URL efímera en
`docs/evidence/piloto_tunnel_url.txt` (ignorado por git). Los procesos quedan
en segundo plano y se detienen con `Stop-Process -Id …`.

Smoke test mínimo desde la laptop:

```bash
curl http://127.0.0.1:3000/health
curl http://127.0.0.1:3000/soporte
curl -H "x-rbs-pilot-key: <clave>" https://<url>/v1/support/devices/light
```

Para revocar un probador, mueve su clave a `RBS_PILOT_REVOKED_KEYS` y reinicia
el backend: las peticiones de ese móvil pasan a `403 PILOT_KEY_REVOKED`.
