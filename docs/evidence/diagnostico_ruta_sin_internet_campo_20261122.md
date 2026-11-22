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

El mensaje es técnicamente correcto, pero la UX no ofrece una salida cuando el usuario está en campo y no tiene cobertura: no permite guardar un borrador sin geometría ni explica que el destino específico no está cubierto por los assets offline.

## 5. Limitaciones de esta evidencia

- No hay captura del instante exacto del error; solo confirmación verbal del Toast.
- No se conocen las coordenadas exactas de los tres puntos 5/25/50 m; se sabe que son los marcadores de la prueba de separación, no los destinos del catálogo offline.
- No hay logs de app después de `13:42:53` por la muerte de los grabadores internos.
- No se replicó el intento con internet para comparar, para no alterar el estado de la prueba de campo.

## 6. Fix propuesto (NO implementado; requiere autorización)

No se modificó código. Si se autoriza un pilar de corrección, la opción mínima y coherente con la regla «nunca dibujar líneas rectas» sería:

1. Distinguir en la UI entre «sin internet» y «destino sin cobertura offline», reutilizando el resultado real de `DefaultRouteCatalog.getRoutesFor()`.
2. Permitir guardar un **borrador de ruta sin geometría** (`status=draft`) solo con origen/destino y una bandera `geometry_pending`, sin dibujar ninguna línea falsa; el backend ya admite `draft` en `club_routes_status_check`.
3. Recalcular la geometría automáticamente cuando vuelva la conectividad y el destino tenga cobertura, manteniendo el `route_ref` existente.
4. Añadir pruebas JVM para `RoadRoutingEngine`/`DefaultRouteCatalog` cubriendo: sin red + destino no cubierto, sin red + destino cubierto, y con red.

Quedan fuera de este alcance, salvo autorización explícita: GATT, cifrado Ed25519/ChaCha20, `HeartbeatFrame`, PTT, liderazgo, `MapView`, zoom, pan, rotación, estilo y attribution.
