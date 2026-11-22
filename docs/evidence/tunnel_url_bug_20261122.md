# Incidente `Fastify offline` y corrección del script de túnel (2026-11-22)

Rama `fix/tunnel-url-vigente`, creada desde `238a04d`. Sin push.

## 1. Síntoma

Los móviles mostraban:

```
🔴 Fastify offline (Unable to resolve host
"den-accessory-africa-beijing.trycloudflare.com":
No address associated with hostname)
```

## 2. Causa raíz

Dos capas, ambas verificadas:

1. **Configuración persistida obsoleta en los dispositivos.** `rbs_pilot_prefs.xml`
   contenía `base_url=https://den-accessory-africa-beijing.trycloudflare.com`
   en TECNO `098813734B105395` y Redmi `b2c398bb`. Es un quick tunnel de una
   sesión anterior: el hostname ya no existe en DNS. `PilotTelemetry.install()`
   restaura ese valor en cada arranque, así que el banner de
   `Phase1ViewModel.kt` mostraba el error de resolución.
2. **Bug en `scripts/start-cloudflared-piloto.ps1`.** El log de cloudflared es
   acumulativo y el script tomaba la **primera** coincidencia del regex
   `https://[a-z0-9-]+\.trycloudflare\.com`. En el arranque de las 14:12 el log
   contenía dos URLs: la vigente `coding-earl-derby-windsor...` y la antigua
   `favor-aaron-founded-scene...` de las 06:54. El script guardó la antigua en
   `docs/evidence/piloto_tunnel_url.txt`; esa URL tampoco resuelve. Cualquier
   deep link armado desde ese archivo dejaba al móvil en `Fastify offline`.

## 3. Corrección aplicada

- El script trunca los tres logs del túnel antes de lanzar `cloudflared`.
- El regex ahora toma la **última** coincidencia (`$matches[$matches.Count-1]`).
- Ambos cambios son defensivos: con el log limpio y tomando la última URL, una
   ejecución nueva no puede reutilizar un hostname de una sesión previa.

## 4. Acciones operativas

- Nuevo quick tunnel vigente: `https://coding-earl-derby-windsor.trycloudflare.com`
  (guardado en `piloto_tunnel_url.txt`, ignorado por git).
- `/health` → 200 `{"status":"ok"}`, `/soporte` → 200, gate sin clave → 401.
- Deep link `rbs://debug/set-base-url` enviado a ambos móviles con su clave y tag;
  `base_url` verificado en `SharedPreferences` de los dos equipos.
- Redmi: banner confirmado en UI como
  `🟢 Fastify online (status: ok) · PostgreSQL 16` (`docs/evidence/logo_paso1_redmi_20261122/ui_club.xml`).
- Telemetría real recibida: `/v1/support/devices/light` devuelve
  `pilot_build=PILOTO-TECNO-2026`, `device_model=TECNO KI5k`, batería 100 %.
- El backend iniciado por el script desde Windows tuvo 500 intermitentes por
  pérdida de conexiones del pool (`Connection terminated unexpectedly` contra
  PostgreSQL en WSL `127.0.0.1:5433`). Se detuvo ese proceso y se levantó el
  backend dentro de WSL, en el mismo puerto 3100, conservando el túnel. Tras
  eso, **5/5** consultas a `/v1/support/devices/light` devolvieron 200.

## 5. Pendiente

- Publicar esta corrección solo si se decide: el script arreglado evita que el
  archivo de URL vuelva a quedar desactualizado en el próximo arranque.
- El hostname del quick tunnel es efímero: cada reinicio exige reenviar el deep
  link a los dos móviles.
