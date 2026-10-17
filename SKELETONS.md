# SKELETONS — Sistema de Skeleton Screens de Ruta Biker Sync

Versión 1.0 · 2026-10-17
Complementa SCREENS.md y DESIGN.md. Implementación en `ui/components/skeleton/` (Jetpack Compose).

## 1. Qué es y qué no es

Un **skeleton** es una versión sin datos de la pantalla (cajas y líneas grises) que se muestra mientras llegan datos del **backend**. Mantiene la forma final para que la interfaz no salte cuando llegan los datos.

No es un skeleton:
- Una **lista de verificación** (sala de espera, B5).
- Una **barra de progreso** (descarga offline, E3).
- Un estado de **conexión** de la mesh ("buscando riders", C1).
- Un **spinner** de acción corta (botón enviando).

## 2. Principios

1. **Local primero.** Si el dato está en Room, se muestra al instante. El skeleton existe solo cuando no hay nada local que mostrar (primera carga o datos que solo viven en el backend).
2. **Nunca en rodada activa.** Mapa, PTT, SOS, señales, panel de emergencia y avisos salen de datos locales o de la mesh. Un skeleton ahí sería un fallo de diseño: un SOS no puede esperar nada. (Excepción: el mapa muestra tiles cacheados; ver 6.)
3. **Mismo esqueleto que el contenido.** Mismas medidas, márgenes y número de filas aproximado. Cero saltos de diseño (CLS).
4. **Quieto cuando conviene.** En modo guantes, con ahorro de batería activo o con "reducir animaciones", el skeleton es **estático** (sin brillo).
5. **Honesto.** Si algo falló, se muestra el error con acción. Un skeleton no puede durar para siempre.
6. **Oscuro por defecto** y con contraste suficiente para no desaparecer al sol.
7. **Accesible.** El lector de pantalla anuncia "Cargando" una vez, no cada caja.

## 3. Tokens de diseño

| Token | Valor | Notas |
|---|---|---|
| `skeleton.base` (oscuro) | `#2A2D33` | Fondo de bloques |
| `skeleton.highlight` (oscuro) | `#3A3E46` | Franja del brillo |
| `skeleton.base` (claro) | `#E3E5E8` | |
| `skeleton.highlight` (claro) | `#F2F3F5` | |
| `skeleton.radius.sm` | 4 dp | Líneas de texto |
| `skeleton.radius.md` | 12 dp | Tarjetas, botones |
| `skeleton.radius.full` | 50 % | Avatares, logos |
| `skeleton.shimmer.duration` | 1 200 ms | Una pasada |
| `skeleton.shimmer.angle` | 20° | Inclinación del brillo |
| `skeleton.line.height` | 14 dp (cuerpo) · 20 dp (título) | Igual que la tipografía real |
| `skeleton.line.gap` | 8 dp | |

Los valores son un punto de partida; ajustarlos con la paleta final de la marca y comprobar contraste en pantalla real bajo el sol.

## 4. Componentes base

| Componente | Descripción | Uso |
|---|---|---|
| `SkeletonBox(w, h, radius)` | Rectángulo | Imágenes, mapas, botones |
| `SkeletonLine(widthFraction)` | Línea de texto (ancho en fracción: 0.9, 0.6…) | Títulos, párrafos |
| `SkeletonCircle(size)` | Círculo | Avatares (64 dp), logos de grupo |
| `SkeletonChip` | Píldora 72×28 dp | Estatus, cargo, rango |
| `SkeletonBadgeGrid(cols, rows)` | Rejilla de círculos 56 dp | Insignias (F2) |
| `SkeletonScope { … }` | Contenedor que aplica un único brillo sincronizado a todos sus hijos | Todas las pantallas |

Compuestos reutilizables (arman los de arriba):

| Compuesto | Estructura |
|---|---|
| `SkeletonListItem` | Círculo 48 dp + línea título 0.6 + línea subtítulo 0.4 + chip a la derecha |
| `SkeletonCard` | Caja 12 dp de radio + cabecera (círculo 40 + línea 0.5) + 2 líneas (0.9 y 0.7) |
| `SkeletonMemberRow` | Avatar 48 + nombre + cargo + semáforo (círculo 16) |
| `SkeletonRideCard` | Caja de mapa 16:9 + título + 2 chips + línea de fecha |
| `SkeletonProfileHeader` | Círculo 96 dp + línea 0.5 + chip de rango + fila de 4 insignias 40 dp |
| `SkeletonForm(fields)` | N × (línea etiqueta 0.3 + caja 56 dp) |
| `SkeletonStatsCard` | 3 columnas (línea valor 0.6 + línea etiqueta 0.9) |
| `SkeletonVoteCard` | Línea título + barra segmentada 3 tramos + 2 botones fantasma |

**Sincronía:** todos los hijos de un `SkeletonScope` comparten una sola animación (un `InfiniteTransition`), no una por caja. Ahorra CPU y batería.

## 5. Estados de pantalla

Toda pantalla con datos de backend usa el mismo contrato:

```kotlin
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>                         // skeleton
    data class Content<T>(val data: T, val stale: Boolean = false) : UiState<T>
    data object Empty : UiState<Nothing>                           // ilustración + acción
    data class Error(val kind: ErrorKind) : UiState<Nothing>       // mensaje + reintentar
    data object Offline : UiState<Nothing>                         // sin red y sin datos locales
}

enum class ErrorKind { NETWORK, SERVER, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, SUBSCRIPTION_EXPIRED, UNKNOWN }
```

**Transiciones:**

```
            ┌────────── datos locales ──────────┐
 inicio ──▶ │ ¿hay caché local?                 │
            └───────┬───────────────┬───────────┘
                    sí              no
                    ▼               ▼
         Content(stale=true)     Loading (skeleton)
                    │               │
              refresco backend      ├─ ok ───▶ Content / Empty
                    │               ├─ sin red ▶ Offline
                    ▼               └─ falla ──▶ Error
              Content(stale=false)
```

- **Stale-while-revalidate:** con caché local se muestra el contenido de inmediato y se refresca en segundo plano, con un indicador fino arriba ("Actualizando…"), sin skeleton.
- **Pull-to-refresh** nunca vuelve a mostrar el skeleton completo, solo el indicador.
- **Paginación:** al final de la lista aparece **una** `SkeletonListItem` (no un spinner), y se reemplaza por los datos.

### 5.1 Reglas de tiempo

| Regla | Valor | Motivo |
|---|---|---|
| Retraso antes de mostrar | **150 ms** | Si los datos llegan antes, el usuario no ve parpadeo |
| Tiempo mínimo visible | **400 ms** | Evita el destello si llegan a los 160 ms |
| Tiempo máximo | **8 s** | Pasado el límite, pasa a `Error(NETWORK)` con "Reintentar" |
| A los 3 s | Texto secundario "Esto está tardando más de lo normal…" | Honestidad sin alarmar |
| Reintento automático | 1 vez a los 2 s si hay red | Cubre fallos transitorios |
| Reintento al volver la red | Automático | Escucha de conectividad |

### 5.2 Vacío, error y sin conexión

| Estado | Contenido | Acción |
|---|---|---|
| **Empty** | Icono sencillo + una frase + beneficio | Botón principal de la pantalla (p. ej. "Invitar miembro") |
| **Error de red** | "No pudimos cargar" + causa corta | **Reintentar** |
| **Error 403** | "No tienes permiso para ver esto" | Volver |
| **Suscripción vencida** | Qué se pierde y cuándo | "Renovar" (a Suscripciones) |
| **Offline sin caché** | "Sin conexión. Conéctate una vez para cargar esto." | Reintentar y ver lo disponible sin conexión |
| **Offline con caché** | Contenido + banda "Sin conexión · datos de hace N h" | — |

Textos en es/en/pt, sin culpar al usuario.

## 6. Casos especiales

**Mapa (E2, vista previa de rutas, D3 con mapa de grupo)**
- No hay "skeleton de mapa" gris vacío. Se muestra primero el **último estado cacheado** de los tiles; si no hay, una cuadrícula tenue con el patrón de calles genérico.
- Los tiles faltantes se pintan de `skeleton.base` y se rellenan solos.
- En rodada activa, el mapa siempre se abre con tiles del corredor descargado; si faltan, se avisa con un banner ("Zona sin mapa descargado"), no con un skeleton.

**Avatares y logos**
- Placeholder circular con las **iniciales** del rider (o la inicial del grupo) sobre `skeleton.base` hasta que la imagen de 64×64 llegue de la caché.
- Con la imagen en caché local no hay placeholder.
- Transición de la imagen: fundido de 150 ms (desactivado con reducir animaciones).

**Datos sensibles (F3, F4)**
- Son locales y cifrados, pero el descifrado puede tardar unos ms: se muestra un bloque estático con candado, **sin brillo**, y nunca se muestra contenido parcial.

**Sala de espera (B5)**
- Lista de verificación con cada ítem: `○` pendiente, `◔` en curso, `✓` listo, `⚠` aviso, `✕` fallo. Es información, no un skeleton.

**Descarga offline (E3)**
- Barra de progreso real con MB descargados / totales, tiempo estimado y botón de pausa.

**Mesh (C1)**
- Estados explícitos: `Conectando`, `Mesh parcial`, `Solo`, `GPS sin fijar`. Cada uno con icono, texto y sonido opcional. Nunca skeleton.

## 7. Matriz skeleton por pantalla

| Pantalla | Skeleton | Cuándo se muestra | Cantidad | Estado vacío |
|---|---|---|---|---|
| B1 Inicio | `SkeletonRideCard` + 3 `SkeletonListItem` | Primera carga sin caché | 1 + 3 | "Aún no tienes rodadas" |
| B2 Lista de rodadas | `SkeletonRideCard` | Primera carga | 4 | Igual que B1 |
| B3 Crear rodada | `SkeletonForm(5)` + `SkeletonMemberRow` | Al cargar grupos y miembros | 5 + 6 | "Tu grupo aún no tiene miembros" |
| B4 Detalle de rodada | Caja de mapa + `SkeletonMemberRow` | Primera carga | 1 + 6 | — |
| B6 Resumen | `SkeletonStatsCard` + fila de insignias | Esperando confirmación de cierre | 1 | — |
| D1 Mis grupos | `SkeletonCard` con círculo de logo | Primera carga | 2 | "No perteneces a ningún grupo" |
| D2 Invitación | `SkeletonCircle(96)` + 3 líneas + bloque de costo | Al abrir el enlace | 1 | "Invitación expirada" |
| D3 Grupo | Cabecera + `SkeletonMemberRow` | Primera carga | 1 + 8 | "Aún no hay miembros" |
| D4 Miembro | `SkeletonProfileHeader` + 3 bloques | Primera carga | 1 | — |
| D5 Ajustes grupo | `SkeletonListItem` sin avatar | Primera carga | 6 | — |
| D6 Agregar miembro | Resultado de búsqueda: 1 `SkeletonMemberRow` | Mientras busca | 1 | "No encontramos a ese usuario" |
| D7 Reportes | `SkeletonCard` | Primera carga | 3 | "Sin reportes. Buen ambiente" |
| D8 Votaciones | `SkeletonVoteCard` | Primera carga | 2 | "Sin votaciones abiertas" |
| D9 Transferencia | `SkeletonCard` | Primera carga | 1 | — |
| F1 Perfil | `SkeletonProfileHeader` | Solo sin caché | 1 | — |
| F2 Rango e insignias | Barra de progreso + `SkeletonBadgeGrid(3,3)` | Primera carga | 1 | "Aún no tienes insignias" |
| F5 Suscripciones | `SkeletonCard` + 2 filas | Primera carga | 1 + 2 | "Sin suscripciones activas" |
| F6 Consentimientos | Grupos con 3 interruptores fantasma | Primera carga | 2 grupos | "Sin consentimientos" |
| A2 Login / A4 paso de grupo | Indicador inline en el campo | Verificando nombre o logo | — | — |
| **Sin skeleton** | A1, A3, A5, A6, B5, C1–C7, E1, E3, E4, F3, F4, F7 | — | — | — |

## 8. Implementación en Compose

Estructura de archivos:

```
ui/components/skeleton/
├── SkeletonTokens.kt       // colores, radios, tiempos
├── Shimmer.kt              // Modifier.shimmer() y SkeletonScope
├── SkeletonPrimitives.kt   // Box, Line, Circle, Chip, BadgeGrid
├── SkeletonComposites.kt   // ListItem, Card, MemberRow, RideCard, ProfileHeader, Form, StatsCard, VoteCard
├── UiState.kt              // sealed interface y ErrorKind
├── StateHost.kt            // composable que decide Skeleton / Content / Empty / Error / Offline
└── SkeletonDelay.kt        // regla 150 ms / 400 ms / 8 s
```

**Shimmer sincronizado y respetuoso con la batería (esbozo):**

```kotlin
val LocalSkeletonAnimated = compositionLocalOf { true }

@Composable
fun SkeletonScope(content: @Composable () -> Unit) {
    val reduceMotion = rememberReduceMotion()          // ANIMATOR_DURATION_SCALE == 0
    val batterySaver = rememberBatterySaverOn()         // PowerManager.isPowerSaveMode
    val glovesMode = LocalRideMode.current              // modo guantes
    val animated = !(reduceMotion || batterySaver || glovesMode)

    CompositionLocalProvider(LocalSkeletonAnimated provides animated) {
        Box(Modifier.semantics { contentDescription = "Cargando" }) { content() }
    }
}

@Composable
fun Modifier.shimmer(): Modifier = composed {
    if (!LocalSkeletonAnimated.current) return@composed this.background(SkeletonTokens.base)
    val t = rememberInfiniteTransition(label = "skeleton")
    val x by t.animateFloat(
        initialValue = -1f, targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "shimmer"
    )
    this.drawWithCache {
        val brush = Brush.linearGradient(
            colors = listOf(SkeletonTokens.base, SkeletonTokens.highlight, SkeletonTokens.base),
            start = Offset(size.width * x, 0f),
            end = Offset(size.width * (x + 0.5f), size.height)
        )
        onDrawBehind { drawRect(brush) }
    }
}
```

**Host de estado (esbozo):**

```kotlin
@Composable
fun <T> StateHost(
    state: UiState<T>,
    skeleton: @Composable () -> Unit,
    empty: @Composable () -> Unit,
    onRetry: () -> Unit,
    content: @Composable (T, stale: Boolean) -> Unit,
) {
    val showSkeleton = rememberDelayedVisibility(
        visible = state is UiState.Loading, delayMs = 150, minVisibleMs = 400
    )
    when (state) {
        is UiState.Loading -> if (showSkeleton) SkeletonScope { skeleton() }
        is UiState.Content -> content(state.data, state.stale)
        UiState.Empty      -> empty()
        is UiState.Error   -> ErrorView(state.kind, onRetry)
        UiState.Offline    -> OfflineView(onRetry)
    }
}
```

**Convención en cada ViewModel:** exponer `StateFlow<UiState<X>>`; la pantalla solo llama a `StateHost`. Nada de `if (loading)` suelto en la interfaz.

**Medidas:** los skeletons se construyen con las mismas constantes de diseño que el contenido (`Dimens.avatarMd`, `Dimens.listItemHeight`…), no con números sueltos.

## 9. Accesibilidad

- El contenedor anuncia **"Cargando"** una sola vez (`contentDescription` en el `SkeletonScope`); las cajas hijas están ocultas a TalkBack.
- Al llegar el contenido, el foco va al título de la pantalla y se anuncia "Contenido cargado" con `liveRegion = Polite`.
- Con "reducir animaciones" del sistema, sin brillo y sin fundidos.
- Contraste mínimo entre `base` y el fondo: 1.4:1 como forma visible; el brillo es un refuerzo, no la única señal.
- Los errores no dependen del color: icono, texto y botón.
- Tamaños táctiles de los botones de error y reintento ≥48 dp (≥72 dp en modo guantes).
- Textos en es/en/pt desde recursos (`strings.xml`), nunca incrustados.

## 10. Rendimiento y batería

- Un solo `InfiniteTransition` por pantalla (`SkeletonScope`).
- Sin brillo en ahorro de batería, modo guantes ni reducir animaciones.
- Máximo ~10 filas fantasma por pantalla; no se dibujan más que las visibles (`LazyColumn` con skeletons).
- El brillo se dibuja con `drawWithCache` y un solo `Brush`, sin recomposición por fotograma.
- Medir en un teléfono de gama baja (objetivo: 60 fps sin tirones en un Android de 3 GB de RAM).

## 11. Pruebas

1. **Captura (screenshot tests)** de cada skeleton en claro y oscuro, y de cada estado (Content, Empty, Error, Offline).
2. **Sin saltos de diseño:** las alturas del skeleton y del contenido con datos típicos difieren menos de 8 dp.
3. **Temporización:** con respuesta a 100 ms no aparece skeleton; a 200 ms aparece y dura ≥400 ms; a 9 s aparece el error.
4. **Red lenta y sin red** (modo avión) en cada pantalla de backend.
5. **TalkBack:** se anuncia "Cargando" una vez y luego el contenido.
6. **Reducir animaciones y ahorro de batería:** el skeleton es estático.
7. **Rodada activa:** ninguna pantalla C1–C7 muestra un skeleton con la red cortada.
8. **Gama baja:** sin pérdida de fotogramas ni consumo anómalo de batería durante la carga.

## 12. Lista de comprobación para una pantalla nueva

- [ ] Se indicó su origen de datos (L, L+B, B, M).
- [ ] Si es B o L+B primera carga: tiene skeleton con la forma del contenido.
- [ ] Resuelve `Empty`, `Error`, `Offline` y `Stale`.
- [ ] Usa `StateHost` y `UiState`.
- [ ] Textos en es/en/pt.
- [ ] Probada con red lenta, sin red y TalkBack.
- [ ] Si es de modo rodada: **cero dependencias de red y sin skeleton**.

## 13. Decisiones abiertas
1. Paleta final de la marca para los tokens de color.
2. Si el brillo se muestra en tema claro con luz solar fuerte (probar en campo).
3. Si el estado `Stale` lleva marca de tiempo visible en todas las pantallas o solo en las críticas (suscripciones, pagos).
4. Biblioteca: implementación propia (propuesta) o una librería de terceros; la propia evita dependencias y permite el control de batería.
