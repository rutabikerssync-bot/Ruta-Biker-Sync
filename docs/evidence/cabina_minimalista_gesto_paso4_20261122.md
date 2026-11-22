# Paso 4 — Cabina minimalista y gesto vertical (2026-11-22)

Rama `feat/logo-oficial-escala`. Archivos tocados:
`feature/livemap/LiveMapScreen.kt`, nuevo `feature/livemap/CockpitGestureResolver.kt`
y su test JVM. Sin tocar el SDK del mapa (es **MapLibre 11.5.2**, fork de Mapbox GL;
no se añadió Mapbox SDK), GATT, cifrado, PTT, liderazgo, backend ni BD.

## 1. Estado anterior

`isBottomCockpitExpanded` nacía en `false` y la botonera ya se ocultaba, pero el área
inferior seguía mostrando siempre convoy, «Seguir líder», feedback, avisos SOS, banner
de ruta por malla y una barra larga de texto. El único disparador era un toque
(`onMapClick` o la propia barra). No existía gesto de arrastre.

## 2. Cambio

### 2.1 Gesto aislado del mapa

El `pointerInput` con `detectVerticalDragGestures` se registra **sólo en el asa inferior
de Compose**, no en el `MapView`. Por tanto zoom, pan, rotación y brújula de MapLibre
quedan intactos, y el botón PTT está en la sección expandida, fuera del área del gesto.

- Arrastre hacia arriba ≥ 24 dp → abre la cabina.
- Arrastre hacia abajo ≥ 24 dp → cierra la cabina.
- El toque simple sigue alternando (comportamiento previo conservado).
- El umbral se convierte de dp a px con `LocalDensity`; la decisión pura vive en
  `CockpitGestureResolver` para poder verificarla en JVM.

### 2.2 HUD inferior mínimo

La fila «Convoy / Seguir líder» pasa a `AnimatedVisibility(visible = isBottomCockpitExpanded)`.
En modo contraído sólo queda un asa compacta con: punto de estado de malla
(`MALLA ACTIVA` / `SIN MALLA`, según el `MeshRelayEngine` activo), número de convoy,
flecha y la pista «Controles (desliza arriba)».

Se conservan **visibles de forma deliberada**, por seguridad: el banner de SOS activo
y el ticker de feedback de acciones. «Seguir líder» sigue accesible desde la cápsula
superior del líder, que ya invocaba el mismo `controller.followLeader(...)`.

## 3. Verificación

- `:app:assembleStandardDebug` → `BUILD SUCCESSFUL`.
- `:app:testStandardDebugUnitTest --rerun-tasks` → **498 pruebas, 0 fallos, 0 errores**
  (87 archivos XML): 491 previas + 4 del Paso 3 + 3 nuevas de `CockpitGestureResolverTest`.
- En una corrida intermedia aparecieron dos flakes conocidos de coroutines
  (`Phase1RulesTest > SKELETONS_5_1…` y `LiveMapViewModelTest > sin fix GPS…`); una
  repetición idéntica terminó limpia. Ver diagnóstico aparte en el informe del CI.
- Sin cambios en estilo, tiles, attribution ni gesture settings de MapLibre.

## 4. Pendiente

- Validación visual y del gesto en hardware (TECNO líder / Redmi miembro).
- La validación física 5–50 m sigue **NO EJECUTADA / PENDIENTE**.
- Sin push: `docs/AGENTS.md` exige instrucción explícita.
