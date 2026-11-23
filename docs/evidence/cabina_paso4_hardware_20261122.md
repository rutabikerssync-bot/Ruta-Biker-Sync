# Paso 4 — Validación en hardware (2026-11-22)

Rama `feat/logo-oficial-escala`, commit `9cb1c00` (Paso 4) más `7c6970e`
(estabilización del test). Instalación offline: backend local y túnel caídos, según
se documenta más abajo. No se reiniciaron servicios ni se enviaron deep links.

## 1. Artefactos e instalación

APK `standardDebug` compilados con las claves locales del piloto
(`RBS_PILOT_KEY` / `RBS_PILOT_BUILD`, valores no versionados):

| Dispositivo | Serial | APK | Tamaño | Resultado |
|---|---|---|---:|---|
| TECNO KI5k | `098813734B105395` | `artifacts/pilotos/piloto-tecno-paso4.apk` | 105 776 102 B | `Success` |
| Redmi 2312DRA50G | `b2c398bb` | `artifacts/pilotos/piloto-redmi-paso4.apk` | 105 776 102 B | `Success` |

Ruta: `Perfil → Rodadas → Iniciar cabina y malla`. MapLibre sigue anunciando su
atribución en el volcado UI; no se tocaron zoom, pan, rotación ni estilo.

## 2. Estados observados

El toque de navegación cayó sobre el lienzo y `onMapClick` alternó la cabina una
vez, por lo que el primer volcado (`ui_expandida_inicial.xml`) quedó expandido. Ese
mismo toque confirma que el comportamiento previo de tap sigue vivo.

### 2.1 Cabina contraída (objetivo del Paso 4)

Tras tocar «Ocultar», ambos dispositivos muestran **sólo el asa inferior**:

| Dispositivo | Textos presentes | Textos/controles ausentes |
|---|---|---|
| Redmi | `1 rider`, `MALLA ACTIVA`, `Controles (desliza arriba)` | `Convoy`, `MONITOR MALLA`, `Hablar (PTT)`, `Señal de ruta`, `SOS Emergencia` |
| TECNO | `2 riders`, `MALLA ACTIVA`, `Controles (desliza arriba)` | los mismos; el ticker de feedback `⚠ Piloto #22873: … Reducir velocidad!` permanece visible |

El punto de estado de malla es verde (`MALLA ACTIVA`) en ambos. El HUD superior
(`Eres Líder`, velocidad, batería, `MAPAS OFFLINE`, brújula) y los avatares del
mapa permanecen intactos.

### 2.2 Swipe arriba sobre el asa

`input swipe` de ~350 dp de recorrido, 350 ms. En ambos dispositivos se abre la
cabina y reaparecen: `Convoy`, `MONITOR MALLA`, `Hablar (PTT)`, `Señal de ruta`,
`SOS Emergencia · Mantener presionado 3 segundos` y el texto del asa pasa a
`Ocultar`. Por tanto PTT y SOS siguen accesibles, y el feedback no se perdió.

### 2.3 Swipe abajo sobre el asa

Vuelve al estado 2.1 en ambos: `Controles (desliza arriba)` y sin botonera. El
gesto es reversible y no se registró en el `MapView`.

## 3. Evidencia

`docs/evidence/cabina_paso4_redmi_20261122/`

- `inicial.png`, `ui_inicial.xml`
- `expandida_inicial.png`, `ui_expandida_inicial.xml`
- `contraida.png`, `ui_contraida_verificada.xml`
- `tras_swipe_arriba.png`, `ui_tras_swipe_arriba.xml`
- `tras_swipe_abajo.png`, `ui_tras_swipe_abajo.xml`
- `ui_rodadas.xml`

`docs/evidence/cabina_paso4_tecno_20261122/`: mismos nombres.

## 4. Condición de red (no fabricada)

Estado durante la captura original:

- `http://127.0.0.1:3100/health` → conexión rechazada (puerto equivocado; el
  backend real escucha en `3000`).
- `https://coding-earl-derby-windsor.trycloudflare.com/health` → DNS no resuelve.
- No se había reenviado el deep link `rbs://debug/set-base-url` ni se promovió
  rol alguno.
- Por ello **no se afirmó** banner `🟢 Fastify online` en esa validación; la
  captura corresponde al estado real offline.

**Actualización posterior (mismo día):** el backend y el túnel se restauraron y
la URL se reenvió a ambos móviles. Ver
`docs/evidence/url_tunel_vigente_fastify_20261122.md`. Las capturas de esta
sección no se han reescrito: documentan el estado offline original.

## 5. Pendiente

- Visto bueno visual humano de la asa mínima y del contraste del HUD.
- Validación física 5–50 m: **NO EJECUTADA / PENDIENTE**.
- Sin push de los commits locales (`e647a7c`, `9cb1c00`, `7c6970e`) hasta
  instrucción explícita.
