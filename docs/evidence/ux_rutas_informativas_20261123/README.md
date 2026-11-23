# Evidencia hardware — RUTAS DEL CLUB informativo y PARTICIPANTES solo para la nueva rodada

Fecha: 23 nov 2026. Rama: `feat/logo-oficial-escala`. Build: `standardDebug`, SHA-256 `16372CF70716D707754E7D2D97853ED34E06EB16093132C18789E5DF76CBBF3E`.

## Decisión verificada

- Las **cards de rodadas viejas** son solo informativas: muestran `Participantes: N` y **no responden al tap**.
- Solo la card **PRÓXIMA RODADA** abre el bottom sheet con el CTA **“Ver participantes”**, que lleva a la pestaña Rodadas con el roster de esa ruta.
- PARTICIPANTES identifica la ruta cargada con `Ruta <id corto> · Salida: <fecha>`.

## Entorno

| Dispositivo | Serial | base_url | Usuario |
|---|---|---|---|
| TECNO | `098813734B105395` | `http://127.0.0.1:3000` | `b5d0228d-…484f2` |
| Redmi | `b2c398bb` | `http://127.0.0.1:3000` | `e54bb490-…9dff` |

Backend Fastify en `http://127.0.0.1:3000`, `/health` → `{"status":"ok"}`. Túnel por dispositivo: `adb -s <serial> reverse tcp:3000 tcp:3000`.

## Resultados

| # | Prueba | Resultado | Evidencia |
|---|---|---|---|
| 1 | Listado de RUTAS DEL CLUB en TECNO | Rodada nueva arriba; viejas con `Participantes: 2`, `Participantes: 1`, `Participantes: 1` | `tecno_final_01_listado.png`, `tecno_final_01_listado.xml` |
| 2 | Tap en card vieja (TECNO) | Permanece en el listado; no se abre bottom sheet ni se navega | `tecno_final_02_tap_card_vieja.xml` |
| 3 | Detalle de PRÓXIMA RODADA (TECNO) | Sin botones RSVP; único CTA “Ver participantes” | `tecno_final_03_detalle_nueva.png`, `tecno_final_03_detalle_nueva.xml` |
| 4 | CTA → PARTICIPANTES (TECNO) | `PARTICIPANTES (2)` y `Ruta 6935451a… · Salida: 23 nov., 16:11` | `tecno_final_04_participantes_nueva.png`, `tecno_final_04_participantes_nueva.xml` |
| 5 | Listado y conteos en Redmi | `Participantes: 2`, `Participantes: 1`, `Participantes: 1` | `redmi_final_01_listado.png`, `redmi_final_01_listado.xml` |
| 6 | CTA → PARTICIPANTES (Redmi) | `PARTICIPANTES (2)` y `Ruta 6935451a… · Salida: 23 nov, 16:11` | `redmi_final_02_detalle_nueva.xml`, `redmi_final_03_participantes_nueva.png`, `redmi_final_03_participantes_nueva.xml` |

El conteo sale de `loadRsvps(route.id).summary?.total` (datos reales del backend). Si no hay dato, la UI muestra `Participantes: —` en lugar de inventar un cero.

## Antecedente

Antes de este ajuste, una card vieja sí abría el roster y el encabezado mostraba `Ruta 6321ca67… · Salida: 25 nov., 08:00`. Eso demostró que la navegación funcionaba, pero también que el usuario no podía distinguir visualmente entre rodadas y parecía que siempre se abría la última convocada. Por eso las cards viejas dejaron de navegar y el encabezado quedó como identificación de la ruta nueva.

## Comandos

```powershell
# Instalación y túnel
adb -s 098813734B105395 install -r app-standard-debug.apk
adb -s 098813734B105395 reverse tcp:3000 tcp:3000
adb -s b2c398bb install -r app-standard-debug.apk
adb -s b2c398bb reverse tcp:3000 tcp:3000

# Captura
adb -s <serial> shell uiautomator dump /sdcard/ux_current.xml
adb -s <serial> exec-out screencap -p > captura.png
```

Suite JVM final: **106 suites / 561 tests / 0 fallos / 0 errores / 0 omitidos**.
