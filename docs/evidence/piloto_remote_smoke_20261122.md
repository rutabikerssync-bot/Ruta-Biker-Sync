# Smoke test del piloto remoto (2026-11-22)

Entorno: worktree `D:\Ruta-Biker-Sync-piloto`, rama `feat/piloto-seguridad`,
commit `cfb44be`. Backend ejecutándose desde WSL Ubuntu contra PostgreSQL 16 en
`127.0.0.1:5433` (base `rbs`) y Cloudflare quick tunnel hacia el puerto 3100.

URL efímera: `https://groundwater-invited-third-originally.trycloudflare.com`
(guardada también en `docs/evidence/piloto_tunnel_url.txt`, ignorada por git).
Clave de piloto de prueba: huella `ceb3f3d3a2b1` (el valor secreto no se registra).

## Resultados

| Verificación | Resultado |
|---|---|
| `GET /health` | 200 `{"status":"ok"}` |
| `GET /soporte` (local y túnel) | 200, formulario + FAQ + tabla de dispositivos |
| `GET /admin` (túnel) | 200, panel con pestaña de dispositivos |
| `GET /v1/support/devices/light` sin clave | 401 `PILOT_KEY_REQUIRED` |
| `POST /v1/support/device-telemetry` con clave y build | 201, huella `ceb3f3d3a2b1` |
| `GET /v1/support/devices/light` con clave | 200, 1 dispositivo, sin stack ni logs |
| `GET /v1/support/devices` con clave + admin | 200, 1 dispositivo y 2 eventos |
| `GET /v1/support/devices` con clave + admin inválida | 401 `UNAUTHORIZED_ADMIN` |
| `POST /v1/support/report` anónimo | 201 con `reportId` |
| Redacción en almacenamiento | `Fallo [email-redacted] en [coord],[coord] token=[redacted]` |

Conclusión: el servicio `/soporte` queda accesible desde el laptop por HTTPS sin
Tailscale; el gate de piloto, la clave admin fail-closed, la telemetría y la
redacción funcionan de extremo a extremo con base de datos real.

Además, con el mismo PostgreSQL 16 de WSL se ejecutó la suite backend completa:

```
npx vitest run --no-file-parallelism
Test Files  29 passed (29)
     Tests  176 passed (176)
```

Nota: la URL del quick tunnel cambia en cada arranque. Para reutilizarla sin
recompilar, actualizar los móviles con el deep link debug descrito en
`docs/PILOTO_REMOTO.md`.
