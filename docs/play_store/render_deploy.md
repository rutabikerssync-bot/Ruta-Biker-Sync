# Render — push y verificación de páginas legales

**Estado:** PROCEDIMIENTO PREPARADO. **NO ejecutado por el agente**: el push y el redeploy los hace el propietario.
**Fecha:** 2026-11-27 · **Commit local a publicar:** `6650eb6` · **Rama:** `main` · **Backend real:** `https://ruta-biker-sync.onrender.com`

## 1. Push exacto

```powershell
git push origin main
```

El repositorio ya tiene `origin` configurado (`https://github.com/rutabikerssync-bot/Ruta-Biker-Sync.git`). No se hizo `amend` ni `push` desde este agente.

## 2. Verificación del deploy

Render suele desplegar automáticamente después del push en **~1–2 minutos**. Pasado ese tiempo, ejecutar:

```powershell
curl.exe -s -o NUL -w "HTTP=%{http_code}`n" https://ruta-biker-sync.onrender.com/privacy.html
curl.exe -s https://ruta-biker-sync.onrender.com/privacy.html
```

Repetir para las otras dos páginas:

```powershell
curl.exe -s -o NUL -w "HTTP=%{http_code}`n" https://ruta-biker-sync.onrender.com/terms.html
curl.exe -s -o NUL -w "HTTP=%{http_code}`n" https://ruta-biker-sync.onrender.com/account-deletion.html
```

**Resultado esperado:** `HTTP=200` y el HTML de la política. La URL que debe pegarse en Play Console es `https://ruta-biker-sync.onrender.com/privacy.html`.

### Estado medido antes de este documento (2026-11-27 20:33)

| URL | HTTP Observado |
|---|---:|
| `/health` | `200` (`{"status":"ok","db":"ok"}`) |
| `/privacy.html` | `404` |
| `/terms.html` | `404` |
| `/account-deletion.html` | `404` |

El `404` es esperado hasta que el Push/redeploy incluya el commit `6650eb6`.

## 3. Si sigue devolviendo 404

1. En Render Dashboard, abrir el servicio `ruta-biker-sync`.
2. Confirmar que apunta a la rama `main` y al directorio `backend`.
3. Confirmar el Build Command: `npm run build`.
4. **Manual Deploy → Deploy latest commit** (o *Clear build cache & deploy* si el build quedó stale).
5. Revisar los logs de build: debe terminar con `EXIT_CODE=0` y `dist/public/privacy.html` presente.
6. Repetir los `curl.exe` anteriores. Si persiste el 404, revisar que `backend/scripts/copy-public.mjs` esté incluido en el commit desplegado; ese script copia `backend/public/` a `dist/public/` porque `tsc` no lo hace.
7. No publicar ni desplegar desde el agente: hacerlo desde la consola del propietario.

## 4. Tiempo estimado

- Push a GitHub: inmediato.
- Auto-deploy de Render: **~1–2 min** (puede extenderse si hay cola o build cache).
- Verificación `curl`: inmediata tras el deploy.
