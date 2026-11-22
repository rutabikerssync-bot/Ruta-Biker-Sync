# URL del túnel vigente, banner Fastify y error 403 (2026-11-22)

## 1. El run #39 del CI **no** impide crear el club

El run `#39` de `fix/tunnel-url-vigente` (PR #4) falló con:

```
Phase1RulesTest > Hito 2 optimistic expulsion rolls back when backend rejects or is unreachable FAILED
java.lang.AssertionError at Phase1RulesTest.kt:484
491 tests completed, 1 failed
```

Es el mismo flake de corrutinas documentado en
`docs/evidence/estabilidad_ci_tests_20261122.md`: el mismo SHA tiene runs verdes
y el fallo es una aserción de tiempo en un **test**, no una respuesta del backend.
El arreglo (`7c6970e`, sólo tests) ya pasó 3 corridas locales de 498/498 con el
aislamiento del CI, pero **no está pusheado**; por eso GitHub sigue mostrando el
fallo. No guarda relación con `POST /v1/groups`.

## 2. Causa real del «Fastify offline: Unable to resolve host …

El móvil tenía guardada la URL efímera vencida
`https://coding-earl-derby-windsor.trycloudflare.com` en `rbs_pilot_prefs.xml`.
Los quick tunnels de Cloudflare cambian de hostname en cada arranque; el mensaje
`Unable to resolve host` es DNS, no una caída del backend.

Comprobaciones del servicio:

- `rbs-backend.service` activo en WSL; escucha en `0.0.0.0:3000` (no en `3100`).
- Windows: `GET http://127.0.0.1:3000/health` → `200 {"status":"ok"}`.
- `cloudflared` se ejecuta dentro de WSL contra `127.0.0.1:3000` con proxies
  desactivados: el proceso de Windows no podía alcanzar el puerto reenviado.
- URL vigente: `https://reputation-improved-definition-absence.trycloudflare.com`
  → `GET /health` → `200 {"status":"ok"}` (guardada en
  `docs/evidence/piloto_tunnel_url.txt`, ignorado por git).

Deep link reenviado a ambos dispositivos con su clave y etiqueta locales:

```
rbs://debug/set-base-url?url=<URL vigente>&key=<clave local>&build=<etiqueta local>
```

`base_url` verificado en `rbs_pilot_prefs.xml` de TECNO `098813734B105395` y
Redmi `b2c398bb`: ambos apuntan a la URL vigente. El Redmi muestra
`🟢 Fastify online (status: ok) · PostgreSQL 16` en `Mis Grupos y Clubes`
(`docs/evidence/cabina_paso4_redmi_20261122/uiOnline.xml`).

## 3. El 403 al fundar club sigue siendo sesión huérfana

El TECNO muestra `❌ Error del servidor (403). Creación de club revertida.`
(`docs/evidence/cabina_paso4_tecno_20261122/uiOnlineMiClub.xml`).

Estado real consultado en PostgreSQL:

| Concepto | Valor |
|---|---|
| `users` | 10 |
| `groups` | 5 |
| Sesión TECNO `1ec5e592-5b2d-3737-9820-1107fa43bcf8` | **0 filas** en `users` |
| Sesión Redmi `f2850920-3014-3990-8d87-106a245c2365` | **0 filas** en `users` |

La app conserva `account_type=LEADER` en TECNO y `MEMBER` en Redmi, pero el
backend no conoce esos `uid`; `POST /v1/groups` responde `FORBIDDEN`. No es un
fallo de red, de túnel ni de la regla de liderazgo. No se promovió al Redmi ni se
insertaron usuarios, grupos ni datos demo de forma manual.

Acción correcta, pendiente de ejecutar en el dispositivo: cerrar sesión en el
TECNO y volver a entrar con la cuenta del piloto para que `/v1/auth/login`
registre ese usuario real con `activateLeaderDemo=true`, y reintentar la
creación. No se ha simulado ni fabricado ese alta.

## 4. Estado

- Backend y túnel: operativos.
- Banner Fastify online: confirmado en Redmi; en TECNO el último banner es el
  error 403 porque fue el mensaje más reciente.
- Creación de club real: **PENDIENTE** de re-login del líder.
- Validación física 5–50 m: **NO EJECUTADA / PENDIENTE**.
- Sin push de los commits locales: `e647a7c`, `9cb1c00`, `7c6970e`, `cf22141`.
