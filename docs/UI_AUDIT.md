# UI_AUDIT.md — Auditoría de interfaz del build uxTest (commit 234dad3)

Fecha: 2026-10-26 · Base: capturas del Redmi (P1) de Rodadas/Mapa, Grupo, Rutas y Perfil, contrastadas con DESIGN.md v1.0 y SKELETONS.md v1.0.
Severidad: **Alta** = invalida la prueba de usabilidad o viola un principio de seguridad · **Media** = degrada la experiencia · **Baja** = pulido.

## 1. Resumen

La interfaz funciona, pero hoy **no cumple DESIGN.md** y **no usa nada de SKELETONS.md**. Hay tres causas de fondo:

1. **No existe un sistema visual concreto.** DESIGN.md fija principios (§1, §9), pero no define paleta, escala tipográfica, espaciado ni componentes, y deja la paleta como decisión abierta (§15.3). SKELETONS.md solo define tokens del skeleton. Sin tokens, cada pantalla se resolvió por su cuenta (dos colores primarios, títulos del mismo color que los botones, tamaños dispares).
2. **Hay datos de demostración que parecen reales**, y textos técnicos o en inglés que no son para el rider.
3. **El modo rodada no está tratado como tal:** barra inferior visible, más de 3 acciones, coordenadas en lugar de distancia al líder.

## 2. Hallazgos transversales

| ID | Qué se ve | Regla incumplida | Sev. | Corrección esperada |
|---|---|---|---|---|
| X1 | La insignia ámbar "UX TEST · P1" se superpone al encabezado en todas las pantallas (tapa la batería en el mapa y roza los títulos) y tiene el mismo color que las acciones primarias, así que parece un control de la app. | DESIGN §9 (claridad de colores) | Alta | Etiqueta pequeña, neutra (gris con contorno), en una esquina que no tape contenido; o invisible con zona de pulsación larga. Solo el moderador necesita encontrarla. |
| X2 | Datos de demostración que parecen reales: grupos "Halcones del Asfalto [MX/CO]", "Coyotes de la Sierra", invitación "Titanes del Pacífico", líder "J.M.", perfil "Comandante Águila · 14 rodadas · BMW R1250GS", código "RUTA-TEST-01" ya escrito. Anulan T2, T3, T4 y T6. | UX_TEST_PLAN §3 | Alta | En uxTest arrancar **vacío**, con el estado Empty correcto. Los datos de demostración solo los carga el moderador. El código de invitación no se precarga: lo comunica P0 de viva voz o por QR. |
| X3 | Texto técnico o en inglés en la interfaz: `GUEST`, `LEADER`, `VETERAN`, "Líderes: 3/3", "PMTiles Offline", "Zoom 14x", "Cámara libre", "Modo Guantes (≥72 dp)", "Avatar 64×64 WebP (~3 KB) listo para sincronización pre-rodada", "#RRGGBB". | DESIGN §9 (es/en/pt desde recursos) | Alta | Todo texto visible en `strings.xml` (es/en/pt), con lenguaje de rider: Invitado, Líder, Veterano. Nada de formatos, tamaños ni jerga. |
| X4 | Dos colores primarios (ámbar "Escanear" y verde "Unirse"). Los títulos de página también son ámbar, igual que los botones. El verde significa a la vez "acción" y "estado correcto". | DESIGN §9 | Media | Ver §5: una acción primaria por tarjeta, títulos neutros, verde solo para estado correcto y rojo solo para peligro. |
| X5 | La barra inferior (Rodadas, Grupo, Rutas, Perfil) sigue visible con una rodada activa en el mapa. | DESIGN §1.1 y §9 (modo rodada, sin distracción) | Alta (Fase B) | Rodada activa en pantalla completa, sin barra inferior. Salida con la pulsación sostenida de 3 s ya existente. |
| X6 | No se ve ningún estado Empty, Error, Offline ni skeleton. Rutas es solo un párrafo. | SKELETONS §5, §5.2, §12 | Media (Alta con backend real) | Contrato `UiState` y `StateHost`. Empty = icono + frase + beneficio + acción. |

## 3. Mapa y rodada (Rodadas)

| ID | Qué se ve | Regla incumplida | Sev. | Corrección esperada |
|---|---|---|---|---|
| M1 | El panel dice "PMTiles Offline", pero el mapa no existe (el archivo de teselas es un relleno de 177 bytes). | DESIGN §1.4; SKELETONS §6 | Alta | Quitar la afirmación. Con zona sin mapa, banner "Zona sin mapa descargado". |
| M2 | Un pin fijo "J.M. · ✓ En ruta" con coordenadas de Ciudad de México (19.4326, -99.1332) aparece junto a "Solo · sin nadie en rango · 0 nodos". Datos falsos y contradictorios. T6 ("encuentra al líder") queda invalidada. | UX_TEST_PLAN T6 | Alta | Mostrar solo riders reales. Sin líder presente: "Sin líder en rango". |
| M3 | Seis controles: Hablar, SOS, Señales, Mi ubic., Seguir líder y el interruptor Modo Guantes. | DESIGN §9 (máx. 3 acciones en modo rodada) | Alta | Tres acciones principales abajo. "Mi ubic." y "Seguir líder" en un único control contextual, que aparece solo si hay líder. Modo Guantes sale de la pantalla. |
| M4 | El interruptor de Modo Guantes es pequeño, está dentro del panel de estado y su texto se corta ("Modo Guantes (≥72"). El control que activa el modo guantes no se puede usar con guantes. | DESIGN §1.2, §10 (configurar antes de salir) | Alta | Moverlo a un ajuste previo a la rodada, o a un botón ≥ 72 dp. |
| M5 | Superposiciones: el logo "MapLibre" sobre "Hablar", la atribución de OpenStreetMap sobre la tarjeta del líder, la insignia sobre la batería. | DESIGN §9 (atribución visible) | Media | Márgenes seguros, atribución en su propia esquina, sin choques. |
| M6 | El panel muestra coordenadas numéricas del líder en lugar de lo que define la pantalla 6: avatares, velocidad, estado y distancia al líder. | DESIGN §6 (pantalla 6) | Media | "Líder a 320 m ↗" en lugar de latitud y longitud. |
| M7 | El texto de estado ("Cámara libre · Zoom 14x…") es pequeño y gris sobre fondo oscuro. En modo rodada la tipografía debe ser grande. | DESIGN §9 | Media | En rodada, etiquetas ≥ 16 sp y valores clave ≥ 28 sp; texto secundario con contraste alto. |
| M8 | "Seguir líder" está activo aunque no hay líder. "Hablar" y "Señales" ocupan los lugares más cómodos pero aún no funcionan. | UX básica | Media | Desactivar o ocultar lo no disponible; "Pronto" en lo no implementado. |
| M9 | No se ve la confirmación de SOS (pulsación sostenida o cuenta atrás de 20 s). | DESIGN §8 | Media | Confirmación por pulsación sostenida, con respuesta háptica y sonora. |

## 4. Grupo, Rutas y Perfil

| ID | Pantalla | Qué se ve | Regla | Sev. | Corrección esperada |
|---|---|---|---|---|---|
| G1 | Grupo | Bajo "Invitaciones pendientes" aparecen también los grupos del usuario; no hay un encabezado "Mis grupos". | DESIGN §6 (pantallas 2 y 3) | Media | Dos secciones claras: Invitaciones y Mis grupos. |
| G2 | Grupo | "Crear grupo (Líder $15/mes)" implica un cobro que aún no existe (P4 pendiente). | UX_TEST_PLAN §3 | Media | En uxTest: "Crear grupo (prueba, sin cobro)". En producción el costo es visible, como pide DESIGN §6.2. |
| G3 | Grupo | "Estatus propuesto: GUEST" y "Líderes: 3/3" no se explican. | DESIGN §1.5 | Media | "Invitado propuesto · $2,50/mes"; "Líderes 3 de 3 cupos". |
| R1 | Rutas | Un párrafo y la atribución de OSM, sin mapa; es una pestaña muerta. | SKELETONS §5.2 | Media | En uxTest ocultar la pestaña o marcarla "Pronto". En producto, estado Empty completo. |
| P1 | Perfil | "Color en el mapa (#RRGGBB)" obliga a escribir un hexadecimal. T2 pide elegir un color. | Usabilidad | Alta | Selector de 8–12 colores con nombre y vista previa del pin, con contraste suficiente sobre el mapa. |
| P2 | Perfil | "Cuenta: LEADER · Rango: VETERAN (14 rodadas válidas)": inglés y sin barra de progreso. Faltan insignias y ficha de emergencia. | DESIGN §6 (pantalla 9), §7 | Media | Rango en español con progreso; insignias; ficha de emergencia. |
| P3 | Perfil | El botón "Login / Onboarding" es ambiguo. | Usabilidad | Media | "Cerrar sesión" o "Ver tutorial", según lo que haga. |

## 5. Propuesta de sistema visual (addendum a DESIGN.md §16, provisional)

Parte de lo que la app ya usa, para no rediseñarla. **Requiere tu aprobación** (DESIGN §15.3 la deja abierta) y validación en pantalla bajo el sol.

| Elemento | Valor provisional |
|---|---|
| Fondo / superficie / borde | `#0B0E14` / `#141922` / `#2A2F3A` |
| Texto principal / secundario | `#F2F4F8` / `#AAB2BF` (≈ 9:1 sobre el fondo) |
| Acción primaria | Ámbar `#FFB300` con texto oscuro (≈ 10:1 sobre el fondo) |
| Peligro / SOS | Rojo `#FF2D55`, texto en negrita grande |
| Estado correcto | Verde `#2EE59D`, solo para estados, nunca para acciones |
| Información | Azul claro `#4FC3F7` |
| Tipografía | Cuerpo 16 sp mínimo; secundario ≥ 14 sp; nunca < 12 sp. En rodada: etiquetas ≥ 16 sp, valores clave ≥ 28 sp |
| Espaciado | Múltiplos de 4 dp (4, 8, 12, 16, 24, 32); margen lateral 16 dp |
| Táctil | ≥ 48 dp en general; ≥ 72 dp en rodada y modo guantes, con 12 dp entre botones |

Reglas:
1. **Una acción primaria por tarjeta o pantalla.** El resto son secundarias (contorno).
2. **Los títulos no usan el color de las acciones:** blancos o neutros.
3. **Verde = estado correcto; rojo = peligro; ámbar = acción.** Nunca el color como único indicador (siempre icono y texto).
4. **Modo rodada:** pantalla completa, sin barra inferior, máx. 3 acciones principales y un control contextual.
5. **Todo texto de usuario** en `strings.xml` (es/en/pt), sin jerga técnica.

## 6. Orden de trabajo

| Lote | Cuándo | Contenido | Criterio de aceptación |
|---|---|---|---|
| **1** | Antes del ensayo piloto y de los 6 testers | X1, X2, X3 (los textos visibles), M1, M2, G2, R1 | Capturas de las 4 pestañas sin datos falsos, sin jerga y sin la insignia tapando contenido |
| **2** | Antes de la Fase B | X5, M3–M9, G1, G3 | Capturas de la rodada en pantalla completa con ≤ 3 acciones y sin solapes |
| **3** | Después de aprobar la paleta | Tema único (colores, tipografía, espaciado, componentes) y `UiState`/`StateHost` según SKELETONS | Revisión de cada pantalla con la lista de SKELETONS §12 |
| **4** | Siguiente ronda | P1, P2, P3, perfil completo | Tarea T2 hecha sin ayuda por 5 de 6 |

**Cómo se entrega cada lote:** capturas de cada pantalla (360×800 dp y con la fuente del sistema al 130 %), commit post-build, tests por suite desde XML y la casilla de DESIGN/SKELETONS cumplida o justificada.
