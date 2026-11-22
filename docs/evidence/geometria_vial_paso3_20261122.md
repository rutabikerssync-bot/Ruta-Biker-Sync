# Paso 3 — Geometría vial: spike filter y polilínea de El Jarillo (2026-11-22)

Rama `feat/logo-oficial-escala`. Único código tocado: `feature/routes/RoadRoutingEngine.kt`
(`RoadGeometryCleaner`). Sin GATT, cifrado, `HeartbeatFrame`, PTT, liderazgo, backend ni BD.

## 1. Análisis previo (sin modificar datos)

`android/app/src/main/assets/routes/jarillo_routes.json` es un array de 2 rutas cacheadas:

| Ruta | Puntos | Longitud medida | Declarada | Espigas detectables | Retrocesos bruscos |
|---|---:|---:|---:|---:|---:|
| `route-1` · El Jarillo Directa | 989 | 20,85 km | 20,9 km | 0 | 1 (índice 906, ~20 m) |
| `route-2` · El Jarillo Variante | 1107 | 21,06 km | 21,1 km | 0 | 2 (índices 231 y 1024) |

Las longitudes medidas coinciden con lo declarado y no hay espigas aisladas. Los dos
retrocesos bruscos son horquillas/artefactos de ~20 m, no saltos de coordenadas.
**Conclusión: el asset es sano y no se editó.** No se inventó ni reescribió ningún dato
de ruta. `DefaultRouteCatalog.loadCachedRoutes()` (`RideScheduleDomain.kt:65`) carga la
polilínea tal cual, sin pasar por el limpiador; estos dos defectos afectaban a las
rutas OSM obtenidas en línea (`parseDirectionsRoute`, `RoadRoutingEngine.kt:154`).

## 2. Defectos corregidos

### 2.1 Longitud corrupta en el suavizado Chaikin

`chaikinSmooth` calculaba el segundo punto interpolado con
`rLon = 0.18 * p0.longitude + 0.82 * p1.latitude`. En el corredor de El Jarillo
(lat ≈ 10.35, lon ≈ −67.1) eso mezclaba ~10.35 grados de latitud dentro de la longitud
y producía puntos en ≈ −3.59, a miles de kilómetros de la calzada.

Corrección: `rLon = 0.18 * p0.longitude + 0.82 * p1.longitude`. La rama de horquilla
(`cos < 0.25`, vértice exacto del asfalto) no se tocó.

### 2.2 Umbral de deduplicación 12,6× demasiado laxo

El código usaba el literal `0.00000008` grados² con el comentario «< 2.5 metros».
`sqrt(8e-8) ≈ 2,83e-4°` son ~31 m a esta latitud. En `route-1` conservaba 390 de
989 puntos (descartaba el 61 %); con el umbral previsto de 2,5 m se conservan 980.

Corrección: constante con nombre `MIN_VERTEX_SPACING_METERS = 2.5` y su equivalente
en grados² calculado como `(2.5 / 111_139.0)²`, sin `sqrt` por punto.

## 3. Regresiones añadidas

Nuevo `android/app/src/test/java/com/rutabikersync/app/feature/routes/RoadGeometryCleanerTest.kt`
(4 pruebas JVM, sin Android/MapLibre/BLE):

1. La longitud suavizada permanece en el corredor de El Jarillo (−67.11..−67.09).
2. La deduplicación conserva vértices separados 3,3 m (8 puntos de salida frente a 1 con el umbral viejo).
3. Una espiga sintética de ~100 m se elimina sin mover los extremos.
4. Una horquilla de montaña conserva su vértice exacto.

## 4. Verificación

- `:app:assembleStandardDebug` → `BUILD SUCCESSFUL`.
- `:app:testStandardDebugUnitTest --rerun-tasks` → **495 pruebas, 0 fallos, 0 errores**
  (86 archivos XML): las 491 previas más las 4 nuevas.
- La primera corrida completa reprodujo el flake conocido
  `UncaughtExceptionsBeforeTest` en `LiveMapViewModelTest`; una segunda idéntica con
  `--rerun-tasks` terminó limpia. No es regresión de este cambio (ya apareció en el Paso 2).
- `git diff --check` sin hallazgos.

## 5. Estado y pendiente

- Commit atómico local. **Sin push**: `docs/AGENTS.md` exige instrucción explícita.
- Sin instalación de APK ni validación visual en hardware para este Paso 3: el cambio
  afecta al trazado OSM en línea, no a las polilíneas cacheadas ya verificadas.
- La validación física 5–50 m sigue **NO EJECUTADA / PENDIENTE**.
- Paso 4 (cabina minimalista y gestos) pendiente.
