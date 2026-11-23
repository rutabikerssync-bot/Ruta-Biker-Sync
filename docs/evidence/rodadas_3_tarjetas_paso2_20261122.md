# Paso 2 — Tres tarjetas canónicas en RODADAS (2026-11-22)

Rama `feat/logo-oficial-escala` (PR #3, base `feat/piloto-seguridad`). Sin tocar GATT,
cifrado, `HeartbeatFrame`, PTT ni liderazgo fuera del gate autorizado.

## 1. Cambio

La pantalla `RidesReleaseHomeScreen` (`ui/navigation/MainAppScaffold.kt`) pasa a
tres tarjetas `HeritageBevelCard` canónicas, con el mismo patrón visual:

| # | Tarjeta | Contenido |
|---|---|---|
| 1 | **Próxima Convocatoria de Club** | Rodada convocada o estado «Sin programar» y CTA de ruta oficial. |
| 2 | **Ruta y Corredor Vial / Rodada Individual** | Mapa y trazado cuando hay rodada; «Modo Libre» con «Ruta Libre» e «Iniciar» cuando no la hay. |
| 3 | **Iniciar Rodada (Cabina Activa)** | CTA de cabina/malla y acceso a «Rutas y Corredor sin señal». |

Antes del cambio, la tercera sección era un `HeritageBrassButton` y un `Box`
sueltos, sin el bisel heritage; por eso la pantalla no tenía tres tarjetas
homogéneas. Ahora los dos controles viven dentro de la tercera tarjeta.

## 2. Gate autorizado

Se aplica el único cambio permitido en liderazgo: el CTA
`🗺️ + Crear Ruta Oficial de Club` de la tarjeta 1 usa `canPublishClubRoute`.
Si el usuario no es presidente ni líder del grupo, se muestra el texto
«Solo la directiva del club puede trazar y publicar rutas oficiales.» en vez
del botón. El cálculo de `canPublishClubRoute` no se modifica.

La puerta de arranque de la tarjeta 3 conserva exactamente la condición
existente (`rideRoster == null || !canPublishClubRoute`) del commit `4ba368a`,
para no reactivar el error de los miembros no líderes sin cabina.

## 3. Verificación

- `:app:assembleStandardDebug` → `BUILD SUCCESSFUL`.
- `:app:testStandardDebugUnitTest` → 491 pruebas, 0 fallos, 0 errores
  (85 archivos XML). La primera corrida reprodujo el flake conocido
  `UncaughtExceptionsBeforeTest` en `LiveMapViewModelTest`; una segunda con
  `--rerun-tasks` terminó limpia, confirmando que no es regressions del cambio.
- Sin cambios en backend, BD ni datos demo.

## 4. Verificación en hardware

APK `PILOTO-REDMI-2026` instalado en `b2c398bb` (105 776 189 bytes). El volcado
UI `docs/evidence/rodadas_paso2_redmi_20261122/ui_rodadas.xml` confirma las
tres tarjetas y sus textos: «Próxima Convocatoria de Club», «Rodada Individual /
Solitaria» e «Iniciar Rodada (Cabina Activa)», además del gate «Solo la
directiva del club puede trazar y publicar rutas oficiales.» y los accesos
«Iniciar cabina y malla» y «🗺️ Rutas y Corredor sin señal». Captura:
`docs/evidence/rodadas_paso2_redmi_20261122/rodadas_3_tarjetas.png`.

## 5. Pendiente

- Pasos 3 y 4 del plan maestro (geometría vial y cabina/gestos).
