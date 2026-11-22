# Diagnóstico: ruta no dibujable ni guardable en prueba de campo (2026-11-22)

## 1. Reporte y confirmación del usuario

Tras la prueba de campo de ~35 min y la reconexión USB de ambos móviles, el usuario reportó:

> «no puede terminar de crear una ruta por que dio un error que no permitia dijur la ruta por ende no podia guardarla»

Confirmaciones posteriores del propio usuario:

| Pregunta | Respuesta |
|---|---|
| ¿Fue el Toast exacto `⚠️ No se pudo calcular la ruta sobre vías reales. Verifica tu conexión a internet o descarga los mapas offline en PMTiles.`? | **Sí, ese fue** |
| ¿Qué puntos se usaron? | **Los puntos 5/25/50 m** de la prueba de separación |
| ¿Cómo estaba el internet/cobertura? | **Sin internet** |
| ¿Tomó captura del momento del error? | **No** |

## 2. Evidencia verificada después del regreso

### 2.1 Estado de la base de datos

Consultas ejecutadas contra la BD real del piloto (`rbs` en `127.0.0.1:5433`) después de la prueba:

```sql
SELECT count(*) AS total_routes FROM club_routes;
-- total_routes | 0

SELECT id,group_id,created_by,title,status,created_at
FROM club_routes
WHERE group_id='e3988eba-26e8-425a-80ac-2a65aeda8484'
ORDER BY created_at DESC;
-- (0 filas)

SELECT id,host_group,host_leader,title,state,starts_at,offline_minutes
FROM rides
ORDER BY starts_at DESC NULLS LAST
LIMIT 10;
-- (0 filas)
```

**Conclusión:** no se creó ninguna `club_route` ni ninguna `ride`. El fallo ocurrió antes de cualquier escritura; no hay datos parciales que limpiar.

### 2.2 Estado actual de los dispositivos

Capturas tomadas **después** de reconectar por USB (no son del instante del error):

| Dispositivo | Serie | Archivo | SHA-256 |
|---|---|---|---|
| TECNO KI5k (líder) | `098813734B105395` | `rbs_post_campo_TECNO.png` | `8A0474D7C03AC1D042D4A35B764989118689055FAED4C142E44C9411696EB298` |
| Redmi Note 13 Pro (miembro) | `b2c398bb` | `rbs_post_campo_REDMI.png` | `AA5033DBB442D9BF0086795C4D6992D428CECF234E0382FFD2507A7C04A1536A` |

Los `uiautomator dump` correspondientes muestran en ambos terminales la pantalla `Mis Grupos y Clubes`, con:

- `🟢 Fastify online (status: ok) · PostgreSQL 16`
- Club `RUTAS DEMO BIKERS`
- `PRESIDENTE · Rutero Bikers`
- En el Redmi: `👥 Miembros (2)` y `📋 Invitaciones (0)`

No se observa pantalla de error persistente ni estado corrupto. La identidadBackend y la membresía siguen visibles en la UI.

### 2.3 Logs crudos de campo

| Archivo | Líneas | Bytes | SHA-256 | Último registro |
|---|---:|---:|---|---|
| `rbs_campo_tecno_lider_20261122.log` | 14.689 | 1.463.720 | `00F9E673A5F72CF3475F6E365FC2B4759BD8BE9F1BBBEA7A482CE886690F4CFE` | `11-22 13:42:52.880` |
| `rbs_campo_redmi_miembro_20261122.log` | 160.383 | 26.644.480 | `865F058A2392E780D1412B21BDFA9C934C67A824DB730B6A457EC8F0797D0946` | `11-22 13:42:52.897` |

Limitación: los grabadores internos murieron a las `13:42:52/53` pese a `setsid`; no hay logcat de dispositivo para el resto de la prueba. No se encontraron líneas propias de la app (`RoadRouting`, `RouteRepository`, `sanitized`) en el fragmento capturado, solo eventos del sistema y detección de `com.rutabikersync.app` en primer plano. Por ello, la confirmación del Toast exacto proviene del usuario, no de logcat.

## 3. Causa técnica

El flujo es determinista y explica exactamente el síntoma:

1. `ScheduleRideMapScreen.kt:548-554` valida que origen y destino estén en Venezuela (`lat 0.5..13.0`, `lon -74.0..-59.0`). Los puntos de prueba pasan esta validación; si no, la función retorna sin mensaje.
2. `ScheduleRideMapScreen.kt:558-565` llama a `RoadRoutingEngine.fetchThreeRouteAlternatives(...)`.
3. `RoadRoutingEngine.kt:216-223` intenta `api.mapbox.com/directions/v5/mapbox/driving-traffic` y luego `driving`. **Sin internet, ambas consultas fallan silenciosamente** (`queryDirectionsUrl` devuelve `emptyList()` en `RoadRoutingEngine.kt:170-198`).
4. `RoadRoutingEngine.kt:264-312` intenta variantes por waypoints, pero todas vuelven a consultar Mapbox por HTTP; sin red tampoco producen geometría.
5. `RoadRoutingEngine.kt:315-329` acude a `DefaultRouteCatalog.getRoutesFor(...)`.
6. `RideScheduleDomain.kt:99-120` solo tiene fallback offline para tres zonas:

   | Zona | Asset | Rango |
   |---|---|---|
   | Valencia / Ciudad Chávez | `valencia_routes.json` | `lon -68.25..-67.75`, `lat 10.05..10.30` |
   | El Jarillo | `jarillo_routes.json` | `lon -67.24..-67.12`, `lat 10.30..10.38` |
   | Colonia Tovar | `tovar_routes.json` | `lon -67.35..-67.24`, `lat 10.38..10.45` |

   No existen más assets en `android/app/src/main/assets/routes/`. Los puntos 5/25/50 m de la prueba están fuera de esos rangos, así que `getRoutesFor()` devuelve `emptyList()` (comentario explícito en `RideScheduleDomain.kt:118-120`: «NUNCA se inventan cuerdas rectas ni trazados artificiales»).
7. `ScheduleRideMapScreen.kt:568-575` filtra waypoints y exige `waypoints.size >= 2`. Con cero alternativas, `sanitized` queda vacío.
8. `ScheduleRideMapScreen.kt:587-595` muestra el Toast confirmado por el usuario y **no ejecuta** la transición `currentStep = RoutePlanningStep.ROUTES_SELECTION` de la línea 597. Sin ese paso no hay selección de ruta y, por tanto, no se puede guardar.

## 4. Conclusión

No fue un fallo de backend, autenticación, club, membresía ni base de datos. Fue el comportamiento previsto del código ante **sin internet + puntos fuera de las tres zonas con geometría offline**: el motor se niega a dibujar una línea inventada, muestra el único mensaje de error existente y bloquea el avance al paso de selección/guardado.

El mensaje era técnicamente correcto, pero la UX no ofrecía salida cuando el usuario estaba en campo sin cobertura: no permitía dibujar ni guardar, y no explicaba que el destino específico no estaba cubierto por los assets offline.

## 5. Limitaciones de esta evidencia

- No hay captura del instante exacto del error; solo confirmación verbal del Toast.
- No se conocen las coordenadas exactas de los tres puntos 5/25/50 m; se sabe que son los marcadores de la prueba de separación, no los destinos del catálogo offline.
- No hay logs de app después de `13:42:53` por la muerte de los grabadores internos.
- No se replicó el intento con internet para comparar, para no alterar el estado de la prueba de campo.

## 6. Investigación del trabajo previo y fix aplicado

El usuario observó que el dibujo de polilíneas estilo Google Maps con varias alternativas «ya estaba hecho». Efectivamente:

- `BRouterDomain.kt:261-315` contiene `MotorcycleRoutingEngine.planMotorcycleRoute()`, que ya generaba 3 perfiles aproximados (`FASTEST`, `CURVY_SCENIC`, `OFFROAD_ADVENTURE`) con waypoints interpolados.
- `RouteEnginePhase4Test.kt:31-70` ya probaba ese generador (≥10 waypoints, distancia, elevación, hash y POIs).
- `ScheduleRideMapScreen.kt:354-386` ya dibujaba las polilíneas y los chips de tiempo, con selección al tocar la línea en `:912-914`.

Lo que **nunca estuvo conectado** era ese generador con la pantalla de creación de ruta. `git log -S planMotorcycleRoute --all -- android/app/src/main/java` solo muestra el commit inicial `766fe86`, donde se define pero no se llama desde la UI. La pantalla siempre invocó `RoadRoutingEngine.fetchThreeRouteAlternatives()`. Con internet, Mapbox entregaba las 3 rutas y ocultaba el hueco; sin internet en campo, el hueco quedó expuesto. No hay un commit posterior que haya «roto» el dibujo: `e647a7c` corrigió Chaikin/deduplicación y `4aa464e` solo separó el paso 3 en 3A/3B.

Tras autorización explícita del usuario, se aplicó el fix mínimo:

1. `RoadRoutingEngine.buildApproximateAlternatives()` reutiliza `MotorcycleRoutingEngine.planMotorcycleRoute()` para completar hasta 3 alternativas cuando no hay Mapbox ni assets offline.
2. Se etiquetan como `Ruta N · <perfil> (aproximada)` y `Trazado aproximado sin internet · no navegable`.
3. `ScheduleRideMapScreen` pasa de «3 rutas trazadas sobre vías reales» a «3 alternativas de ruta», texto válido para ambos orígenes de geometría.
4. Se actualizaron los comentarios que afirmaban que nunca se generaban trazados aproximados.
5. `RoadRoutingFallbackTest.kt` verifica 3 alternativas, IDs `route-1..3`, ≥6 waypoints, extremos exactos y etiquetado no navegable, además del respeto al `limit`.

Verificación local:

- `:app:testStandardDebugUnitTest`: **89 suites / 505 tests, 0 fallos, 0 errores, 0 omitidos** (dos tests nuevos).
- `:app:assembleStandardDebug`: **BUILD SUCCESSFUL**.

Alcance respetado: sin cambios en `MapView`, zoom, pan, rotación, estilo ni attribution; sin cambios en GATT, Ed25519/ChaCha20, `HeartbeatFrame`, PTT ni liderazgo. Sin push.

## 7. Instalación en hardware (2026-11-22 15:56 -04:00)

APK instalado con `adb install -r` desde
`android/app/build/outputs/apk/standard/debug/app-standard-debug.apk`:

| Dispositivo | Serie | Resultado |
|---|---|---|
| TECNO KI5k (líder) | `098813734B105395` | `Success` |
| Redmi Note 13 Pro (miembro) | `b2c398bb` | `Success` |

Verificación posterior con `run-as`:

- `versionName=0.1.0`, `versionCode=1` en ambos.
- `base_url` vigente conservado: `https://reputation-improved-definition-absence.trycloudflare.com`.
- TECNO: `user_id=b5d0228d-8ab9-49fd-9cc7-efa452a484f2`.
- Redmi: `user_id=e54bb490-754a-4aa6-9b4c-8b7b043e9dff`.

La reinstalación conservó la identidad real y la configuración del piloto.

## 8. Rechazo del fallback aproximado y revert (2026-11-22 16:01 -04:00)

Tras instalar y probar el build, el usuario reportó que el trazado aproximado dibuja
**una línea recta sobre casas, sin seguir ninguna vía**, y concluyó:

> «un producto así no se puede utilizar para hacer pruebas de campo con terceros»

Ese resultado es incompatible con la regla del proyecto de no presentar geometría falsa,
aunque el usuario hubiera autorizado previamente «geometría no perfecta». La diferencia
importante es que *aproximada* no puede significar *atravesar edificios*.

Se aplicó un `git revert` atómico del commit `50476e5`:

- `2f0182b Revert "fix(routes): conectar fallback aproximado sin internet"`

Sin `amend` y sin `push`. El comportamiento vuelve al diagnóstico original: sin red y
sin asset offline, la app muestra el Toast y no dibuja ninguna línea inventada.

Las secciones 6 y 7 quedan como registro histórico de la decisión que fue revertida;
**no representan el estado actual del código**.

### Siguiente decisión pendiente

Para pruebas con terceros se necesita una de estas alternativas, aún no implementadas:

1. **Grafo vial offline real** para el área de prueba (OSM/BRouter/PMTiles con calles),
   de modo que las 3 alternativas sigan calles existentes.
2. **Trazado manual asistido**: el piloto toca puntos sobre la vía real y la app une
   solo esos puntos; no se inventa geometría.
3. **Bloqueo honesto hasta tener red**: mantener el Toast actual y no guardar ruta
   mientras no haya cobertura/asset válido.

No se reanuda ninguna de estas opciones sin confirmación explícita.

### Verificación del revert (16:03 -04:00)

- Tras el revert: **88 suites / 503 tests, 0 fallos, 0 errores, 0 omitidos**.
- `:app:assembleStandardDebug`: **BUILD SUCCESSFUL**.
- APK revertido reinstalado con `adb install -r` en TECNO `098813734B105395` y Redmi `b2c398bb`: **Success** en ambos.
- UUIDs y `base_url` vigente se conservaron tras la reinstalación.

## 9. Causa raíz real: token de Mapbox vacío (2026-11-22 16:12 -04:00)

El usuario aclaró que **ambos móviles tenían internet** durante la prueba fallida.
Esto descarta la falta de cobertura como causa raíz y cambia el diagnóstico.

Verificación:

- El workspace piloto no tenía `android/local.properties`.
- La variable de entorno `MAPBOX_TOKEN` tampoco estaba definida.
- `android/app/build.gradle.kts:41-52` genera `BuildConfig.MAPBOX_ACCESS_TOKEN` desde
  esas fuentes; con ambas ausentes, el APK se compiló con:
  `public static final String MAPBOX_ACCESS_TOKEN = "";`
- `RoadRoutingEngine.kt:218` omite la consulta a Mapbox cuando el token está vacío
  (`if (mapboxToken.isNotBlank()) … else emptyList()`).
- Por tanto, **con internet pero sin token**, el motor nunca pidió vías reales: solo
  quedaban los 3 assets offline y los puntos de prueba caían fuera de ellos.

El token sí estaba disponible en el repo base (`D:\Ruta-Biker-Sync\android\local.properties`).
Se copió ese archivo al workspace piloto. Está cubierto por `.gitignore`, así que
**no se versionó ningún secreto**.

Verificación del token y del build:

- Llamada directa a Mapbox Directions con el token real:
  `HTTP=200`, `code=Ok`, `routes=2` para Los Teques → El Jarillo.
- `BuildConfig.MAPBOX_ACCESS_TOKEN` regenerado con valor no vacío (prefijo `pk.eyJ1I.`).
- `:app:assembleStandardDebug`: **BUILD SUCCESSFUL**.
- APK recompilado e instalado con `adb install -r` en TECNO y Redmi: **Success** en ambos.
- TECNO alcanza `api.mapbox.com` (ping 0 % pérdida); Redmi responde `HTTP 200` a la API.

Reproducción controlada en TECNO (16:10 -04:00 aprox.):

1. Se abrieron `Rodadas` → `+ Crear Ruta Oficial de Club`.
2. Se fijó el lugar de salida desde la ubicación cacheada (`8XX7+JW3, Los Teques`).
3. Se eligió como destino `San Pedro de Los Altos`, **fuera de los 3 assets offline**.
4. La app avanzó a `Paso 3A: Escoge tu Ruta`, mostró
   `3 rutas trazadas sobre vías reales` y `Ruta 1 (8.6 km) · 19 min`.
5. **No apareció el Toast de error**. Captura: `rbs_token_ok_TECNO_rutas_reales.png`.

Conclusión corregida: el fallo de campo fue una **configuración de build incompleta en el
workspace piloto**, no falta de internet, no backend y no geometría offline. La recta sobre
casas era un intento de solución que ya fue revertido; la solución correcta era restaurar
el token para que Mapbox trace sobre vías reales.

### Pendiente

El usuario pidió además **redundancia BLE + 4G completa**. Ese pilar sigue sin implementar
y debe abordarse por separado, sin mezclarlo con este fix de configuración.
