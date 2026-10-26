# UX_TEST_PLAN.md — Pruebas de usabilidad con 6 usuarios (Ruta Biker Sync)

Estado: borrador v1.2. Complementa FIELD_TEST_PLAN.md (pruebas de radio) y PLAY_TEST_SETUP.md (compras). Las cifras marcadas "propuesta" se ajustan tras la primera sesión piloto.

## 0. Propósito y reglas

**Objetivo.** Medir si 6 personas reales pueden usar la app sin ayuda en las tareas clave y qué experiencia tienen durante una rodada de grupo.

**Reglas no negociables**
1. **Seguridad:** nadie toca el teléfono mientras conduce. En marcha solo se mira de reojo y se escuchan alertas. Todas las tareas con interacción se hacen detenidos. Cualquier SOS de prueba se marca como PRUEBA.
2. **Consentimiento:** cada participante acepta por escrito (o mensaje) que se registran sus tiempos, toques y respuestas. No se graba voz ni se guarda ubicación en el registro UX.
3. **Anonimato:** se usa solo el código P1–P6. Nunca nombres, teléfonos ni coordenadas en los archivos de resultados.
4. **Con 6 personas** se detectan la mayoría de los problemas graves de usabilidad, pero **no se sacan porcentajes ni conclusiones estadísticas**. Se reportan conteos ("4 de 6 fallaron la tarea T3").
5. **No se mezcla** con la depuración BLE: la Fase B (rodada) solo se hace después de pasar las pruebas de distancia (B-series).

## 1. Participantes

Cubrir perfiles distintos. Completar la tabla antes de empezar.

| Código | Rol de club | Teléfono (marca/modelo) | Android | Experiencia con apps de grupo (Zello, WhatsApp ubicación, etc.) | Usa guantes al rodar (S/N) |
|---|---|---|---|---|---|
| P1 | Líder | | | | |
| P2 | Co-líder / Cierre | | | | |
| P3 | Miembro | | | | |
| P4 | Miembro (poca habilidad tecnológica) | | | | |
| P5 | Miembro | | | | |
| P6 | Invitado | | | | |

Meta de diversidad: al menos 3 marcas de teléfono y al menos 1 persona poco cómoda con la tecnología. Estos teléfonos sirven también para las pruebas de malla (≥ 5 teléfonos / 3 marcas).

## 2. Build para los testers

Requisitos (los implementa la IA de desarrollo, con revisión previa):
- **Build `uxTest`** (no `standardRelease`): panel DEV desactivado, sin controles de depuración, `applicationId` con sufijo `.uxtest`, marca visible "UX TEST", firmado con clave de depuración, **nunca se sube a Play**.
- **Clave de rodada de prueba permitida** (`ALLOW_DEFAULT_TEST_RIDE_KEY=true`). Sin ella, en release cada teléfono genera una clave aleatoria y **los teléfonos no se verán entre sí** (D58). Release real de Play mantiene `false`.
- **Registro UX local** (ver §6).
- **ID de malla único por teléfono:** P1→#1, P2→#2 … P6→#6 (el apoyo P0 con #99). Dos teléfonos con el mismo ID se confunden en la malla y sus marcadores se mezclan. El líder lo fija el moderador (por defecto #1).
- **Acceso del moderador (Gesto protegido):** Mantener pulsada la insignia "UX TEST · Px" durante 2 segundos e introducir el PIN de 4 dígitos (`1984`) para que un tester no abra por error el cambio de participante ni la difusión de SOS.
- **Mapa sin conexión:** el archivo de teselas incluido es un relleno de 177 bytes (zona de muestra Ciudad de México–Cuernavaca), no un mapa real. Hoy no hay mapa base: solo fondo oscuro, marcadores y el corredor de muestra. Antes de la Fase B hay que generar teselas reales de la zona de la rodada y probarlas en un teléfono en modo avión. En la Fase A se avisa a los testers de que el mapa es de demostración.
- Controles no implementados (ver inventario) ocultos o con texto "Próximamente".
- Textos sin referencias internas ("Fase 3", "placeholder").

Backend para la pestaña Grupo: definir si es Fastify real o FakeBackend (decisión pendiente, §9). Debe ser el mismo para los 6.

## 3. Fase A — Sesiones estáticas (≈ 40 min por persona, 6 sesiones)

**Formato.** Una persona a la vez, con el teléfono en la mano, en un lugar tranquilo. Quien modera lee la tarea, **no ayuda** salvo que el participante se bloquee más de 2 minutos (se anota "con ayuda"). El participante piensa en voz alta.

**Estructura:** bienvenida y consentimiento (3 min) → tareas (25 min) → SUS (3 min) → preguntas finales (5 min) → cierre.

### Tareas
Un teléfono por participante; las tareas que necesitan a otro participante usan un teléfono de apoyo.

| # | Tarea (se lee tal cual) | Éxito si… | Tiempo objetivo (propuesta) |
|---|---|---|---|
| T1 | "Abre la app por primera vez y deja que funcione para una rodada." | Concede permisos de ubicación, Bluetooth y notificaciones sin ayuda. | ≤ 90 s |
| T2 | "Crea tu cuenta y pon tu nombre de rider, tu moto y el color de tu pin." | Perfil guardado con los 3 datos. | ≤ 120 s |
| T3 | "Crea un grupo para tu club." | Grupo creado con nombre. | ≤ 90 s |
| T4 | "Haz que otra persona entre a tu grupo." Solo Presidente y Vicepresidente (P1, P2) pueden mostrar el QR (R17). Los demás lo escanean con la cámara; si falla, escriben el código. | Segundo teléfono unido al grupo (anotar si usó QR o código y si la cámara enfocó a la primera). | ≤ 120 s |
| T5 | "Inicia una rodada." | Rodada activa con el mapa abierto. | ≤ 30 s |
| T6 | "Encuentra dónde está el líder." | Usa "Seguir líder" o identifica el marcador correcto. | ≤ 15 s |
| T7 | "Imagina que llevas guantes: activa el modo que haga los botones más grandes." | Modo guantes activado (repetir con guantes reales en 2 personas). | ≤ 15 s |
| T8 | "Te dicen que un compañero se quedó atrás. ¿Qué te indica la app y qué harías?" (alerta simulada) | Interpreta bien la alerta y dice una acción correcta. | ≤ 20 s |
| T9 | "Imagina una emergencia: ¿cómo avisas al grupo?" (SOS de PRUEBA; es local, solo el moderador puede activar la difusión por malla) | Activa el SOS y entiende la confirmación. | ≤ 15 s |
| T10 | "Termina la rodada." | Detiene con la pulsación de 3 s sin ayuda. | ≤ 15 s |

**No se prueban con usuarios (todavía):** Rutas (maqueta), voz (Fase 5), suscripciones y pagos (hasta cerrar P4 de Play). Si el inventario cambia, se ajusta esta lista.

### Qué se anota por tarea
| Dato | Valores |
|---|---|
| Éxito | 0 = falló · 1 = con ayuda · 2 = solo |
| Tiempo | segundos (del registro UX y cronómetro del moderador) |
| Errores | número de toques equivocados o pantallas erróneas |
| Gravedad del problema | 0 sin problema · 1 cosmético · 2 menor · 3 grave · 4 impide usar |
| Cita textual | frase del participante |

## 4. Fase B — Rodada de grupo (30–45 min, los 6 juntos)

**Condiciones previas:** pruebas de distancia BLE superadas, build `uxTest` en los 6, teléfonos en soporte, pantalla encendida, ruta de poco tráfico, reunión de seguridad (nadie toca el teléfono; orden de marcha; punto de reunión).

**Durante:** una persona de apoyo (no conductora) anota la hora de cada alerta que alguien comenta de palabra al detenerse. Nadie anota rodando.

**Datos objetivos por teléfono:** nivel de batería al inicio y al final, JSON y CSV de métricas BLE con el código P1–P6 en el nombre del archivo, cantidad de alertas de "compañero perdido" y cuántas fueron falsas según el grupo.

**Cuestionario posterior** (escala 1–5, 1 = nada de acuerdo, 5 = totalmente de acuerdo):

| # | Afirmación |
|---|---|
| R1 | Pude leer la pantalla con facilidad mientras rodaba (sol, casco). |
| R2 | Entendí de un vistazo dónde estaban los demás. |
| R3 | Confié en que la app me avisaría si alguien se quedaba atrás. |
| R4 | Las alertas llegaron a tiempo. |
| R5 | La app me distrajo de la conducción. |
| R6 | Me preocupó la batería del teléfono. |
| R7 | Lo usaría en mis rodadas habituales. |

Preguntas abiertas: ¿cuántas alertas falsas notaste? ¿Hubo un momento en que no supiste qué pasaba? ¿Qué cambiarías primero?

## 5. SUS (System Usability Scale)

Se responde de 1 a 5 al final de la Fase A.

1. Creo que me gustaría usar esta app con frecuencia.
2. Encontré la app innecesariamente compleja.
3. Pensé que la app era fácil de usar.
4. Creo que necesitaría ayuda de una persona con conocimientos técnicos para usarla.
5. Encontré que las funciones estaban bien integradas.
6. Pensé que había demasiada inconsistencia en la app.
7. Imagino que la mayoría de la gente aprendería a usarla muy rápido.
8. Encontré la app muy incómoda de usar.
9. Me sentí muy seguro/a usándola.
10. Necesité aprender muchas cosas antes de poder usarla.

**Puntuación:** en las preguntas impares, puntaje − 1. En las pares, 5 − puntaje. Se suman las 10 y se multiplican por 2,5. Resultado de 0 a 100. Referencia habitual: 68 es un promedio aceptable. Con 6 personas es **orientativo**: se reporta cada puntaje individual, no solo el promedio.

## 6. Registro UX automático (instrumentación)

- Local en el teléfono, sin red. Código de participante (P1–P6) elegido en una pantalla de ajustes al instalar.
- Eventos: pantalla abierta/cerrada, toques en botones principales, inicio/fin de cada tarea (el moderador marca con un gesto protegido o botón oculto), errores mostrados, alertas recibidas, permisos concedidos/denegados.
- **No se guardan:** nombres, contactos, coordenadas, audio.
- Exportación a JSON junto al JSON/CSV BLE (mismo código P).

## 7. Cómo se decide qué arreglar

1. Un problema de gravedad 3–4 visto en **2 o más** de los 6 participantes se corrige antes de la siguiente ronda.
2. Gravedad 2 con 3 o más participantes: va a la lista de mejoras.
3. Tareas con tiempo mayor al doble del objetivo en 3 o más participantes: se revisa el diseño de esa pantalla.
4. Entre rondas: corregir y repetir con 3 personas nuevas o las mismas con una tarea corta de comprobación.

## 8. Hojas de resultados (copiar para cada participante)

**Sesión Pn — fecha, teléfono, Android, build.**

| Tarea | Éxito 0/1/2 | Tiempo (s) | Errores | Gravedad 0–4 | Cita / nota |
|---|---|---|---|---|---|
| T1 | | | | | |
| T2 | | | | | |
| T3 | | | | | |
| T4 | | | | | |
| T5 | | | | | |
| T6 | | | | | |
| T7 | | | | | |
| T8 | | | | | |
| T9 | | | | | |
| T10 | | | | | |

SUS (10 respuestas): ____ → puntaje ____ · Fase B (R1–R7): ____ · Batería inicio/fin: ____ / ____

**Resumen de la ronda:** problemas por gravedad y por cuántos participantes; SUS individuales; decisiones.

## 9. Pendientes que condicionan este plan

- P6 (¿un líder de $15 puede liderar más de un grupo?) y la jerarquía RC → MG → Gang → MC contra los 7 cargos: afectan las tareas T3, T4 y la pestaña Grupo.
- Backend de Grupo en pruebas: Fastify real o FakeBackend.
- Estado real de "Hablar", "Señales/Gestos" y de suscripciones/pagos (hasta P4).
- Resultados de las pruebas de distancia BLE (condición para la Fase B).
- Zona y ruta de la Fase B, y teselas offline reales de esa zona (compatibles con el estilo actual del mapa). Verificar en un teléfono que MapLibre lee el archivo desde la app; puede requerir copiarlo a almacenamiento interno.
- Umbral de "compañero perdido" (15 s): medir falsas alarmas en las pruebas de distancia (objetivo ≤ 1 cada 30 min).
