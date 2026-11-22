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
  `x-admin-key` además de la clave de piloto.
- `GET /v1/support/devices/:fp/events`: historial de un dispositivo.

La tabla `pilot_devices` conserva el último error hasta que llega uno nuevo;
los latidos solo actualizan estado y `last_seen_at`.
