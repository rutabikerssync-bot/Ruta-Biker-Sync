# PLAY_TEST_SETUP — Configuración de productos de prueba y validación de P4

Versión 1.1 · 2026-10-17
Objetivo: probar con evidencia real si Google Play permite el modelo de cupos de \$2.50 por grupo (RULES P4) antes de programar la integración con Google Play Billing en la Fase 2.

> Las pantallas de Play Console cambian con frecuencia. Los nombres de menú de este documento son orientativos: si no coinciden, buscar la función equivalente en la ayuda oficial. Lo que sí es verificable está marcado con su fuente al final.

## 1. Qué se quiere saber

| # | Pregunta | Por qué importa |
|---|---|---|
| Q1 | ¿Una misma cuenta puede tener activas a la vez `member_slot_1` y `member_slot_2` (productos distintos)? | Es la base del modelo "invitado en varios grupos" |
| Q2 | ¿Se puede comprar dos veces el mismo producto? (se espera que no) | Confirma por qué hacen falta cupos numerados |
| Q3 | ¿Se puede cancelar un cupo sin afectar a los demás? | Salida de un grupo sin perder los otros (RULES R2.3) |
| Q4 | ¿La API de Google Play devuelve un token y un estado por cada cupo? | El backend asigna cada token a un grupo |
| Q5 | ¿Qué pasa con el pago fallido: gracia, retención, recuperación? | Semáforo y credenciales (RULES R7) |
| Q6 | ¿Funciona la alternativa de suscripción con complementos (add-ons)? | Posible plan B de P4 (ver secciones 6 y 13) |
| Q7 | ¿Los RTDN llegan al endpoint y son idempotentes? | `POST /v1/play/rtdn` |

## 2. Requisitos previos (a cargo del dueño del proyecto)

- [ ] Cuenta de desarrollador de Google Play activa (pago único de registro y verificación de identidad).
- [ ] App creada en Play Console con el paquete `com.rutabikersync.app` (no se puede cambiar después).
- [ ] Clave de firma de subida creada y guardada fuera del repositorio (copias de respaldo).
- [ ] Un AAB firmado subido a la pista de pruebas internas (la facturación solo funciona con una versión de la app subida a Play; instalar el APK de depuración por cable no sirve para compras reales).
- [ ] Al menos 2 cuentas de Google de prueba: **T1** (tester principal) y **T2** (tester secundario, para pruebas de líder y miembro).
- [ ] Teléfonos con Play Store y esas cuentas iniciadas.

## 3. Configurar testers

1. En Play Console, en **Configuración → Pruebas de licencia**, agregar los correos de T1 y T2 como *license testers*.
2. En **Pruebas internas**, crear la lista de testers con los mismos correos y compartir el enlace de adhesión.
3. Cada tester acepta la invitación e instala la app desde Play (enlace de pruebas internas).

Con cuentas de prueba de licencia no se cobra dinero real y se pueden elegir instrumentos de pago de prueba (tarjeta que siempre aprueba, que siempre rechaza, y otras que simulan demoras; verificar la lista vigente en la consola).

## 4. Crear los productos

Todos son suscripciones con un plan base mensual de renovación automática:

| ID de producto | Precio base | Uso |
|---|---|---|
| `leader_monthly` | USD 15.00 | Cuenta de líder |
| `member_slot_1` | USD 2.50 | Cupo de miembro o invitado #1 |
| `member_slot_2` | USD 2.50 | Cupo #2 |
| `member_slot_3` | USD 2.50 | Cupo #3 |
| `member_slot_4` | USD 2.50 | Cupo #4 |
| `member_slot_5` | USD 2.50 | Cupo #5 |

**Notas:**
- Los IDs coinciden con `BACKEND_LICENSING.md` v1.1 y `BillingPolicyConfig` (D30/D32). **No renombrar** después de publicar: un ID de producto no se reutiliza.
- Verificar el precio mínimo permitido por país y cómo Play convierte y redondea el precio base a cada moneda e impuestos. \$2.50 puede quedar distinto por región.
- Para la prueba conviene un solo plan base mensual y sin prueba gratuita ni oferta de introducción, para no mezclar variables.
- Activar los productos (estado "activo") y esperar a que se propaguen (puede tardar de minutos a horas).

## 5. Matriz de pruebas (P4 y cobros)

Registrar en cada caso: fecha, cuenta, dispositivo, resultado y evidencia (captura y, redactado, el token de compra). Resultado: ✅ / ❌ / ⚠️.

| ID | Prueba | Resultado esperado |
|---|---|---|
| P4-1 | T1 compra `member_slot_1` | Compra aprobada; la app recibe token A |
| P4-2 | Sin cancelar, T1 compra `member_slot_2` | Compra aprobada; dos suscripciones activas; token B distinto de A |
| P4-3 | T1 intenta comprar `member_slot_1` otra vez | Play lo impide o lo trata como ya adquirido |
| P4-4 | T1 compra `member_slot_3`, `4`, `5` | Cinco cupos activos a la vez |
| P4-5 | La app consulta las compras activas | Devuelve 5 compras con su producto y su token |
| P4-6 | El backend consulta cada token con la API de Google Play | Estado, fechas y renovación correctos por token |
| P4-7 | T1 cancela `member_slot_2` desde Play | `member_slot_1` y `3` a `5` siguen activos; `2` activo hasta fin del periodo |
| P4-8 | Reasignar el cupo `member_slot_2` libre a otro grupo | Define cómo se reutiliza un cupo (comprar de nuevo el mismo ID tras expirar) |
| P4-9 | Compra de `leader_monthly` por T2 y de cupos por T1 | Las dos cuentas son independientes |
| P4-10 | Renovación acelerada | Ver sección 7 |
| P4-11 | Instrumento de pago "siempre rechaza" en una renovación | Estado gracia → retención (`grace` → `on_hold`) |
| P4-12 | Recuperar el pago tras la retención | Vuelve a `active` |
| P4-13 | Reembolso/revocación desde la API para un token | Estado `refunded`; la credencial deja de renovarse |
| P4-14 | Reinstalar la app e iniciar sesión con T1 | Las compras se restauran y el backend las reconcilia (depende de refresh token, ver §15) |
| P4-15 | Dos dispositivos con la misma cuenta | Un solo conjunto de suscripciones, sin duplicar cupos |

**Criterio de decisión:**
- Si **P4-2** y **P4-4** pasan → el modelo de cupos numerados (`member_slot_1..5`) funciona y queda confirmado como Plan A.
- Si **P4-2** falla → evaluar la sección 6 (add-ons) bajo la regla estricta de la sección 13; si AO-3 confirma que cancelar afecta a toda la compra, **descartar add-ons** y pasar directamente al pase por rodada (`ride_pass_guest`, P5).

## 6. Plan B en evaluación: suscripción con complementos (add-ons)

Según la documentación de Android Developers, una compra puede agrupar varias suscripciones existentes (un artículo base y varios complementos) con un solo token de compra, un solo ciclo de facturación y hasta 50 artículos por compra. Requisitos relevantes: mismo periodo de facturación para todos, planes base de renovación automática, sin productos repetidos, no disponible en Corea del Sur.

**Posible uso:** una compra con un artículo base más `member_slot_N` como complementos, de modo que cada cupo sea un artículo distinto dentro de la misma compra.

**A comprobar en las pruebas:**

| ID | Prueba | Pregunta |
|---|---|---|
| AO-1 | Comprar base + 2 cupos en una sola compra | ¿Un token, tres artículos? |
| AO-2 | Añadir un cupo a una compra existente | ¿Se cobra prorrateado o desde el próximo ciclo? |
| AO-3 | Quitar un cupo / cancelar desde Google Play | La documentación indica que quitar un artículo expira al final de su periodo, pero **cancelar la suscripción desde Play cancela toda la compra** |
| AO-4 | Atribuir cada artículo a un grupo en el backend | ¿La API devuelve el estado por artículo? |
| AO-5 | Pago fallido | ¿Afecta a todos los artículos a la vez? |

> **Decisión previa vinculante (ver §13):** Si **AO-3** confirma que cancelar desde Google Play cancela toda la compra (afectando todos los grupos del usuario y violando `RULES.md` R2.3), **los add-ons quedan descartados** y se pasa directamente al **Plan C: pase por rodada (`ride_pass_guest`, P5)**. No se adoptarán add-ons sin aislamiento de cancelación.

## 7. Renovaciones aceleradas en pruebas

Las suscripciones de cuentas de prueba renuevan más rápido, según el blog oficial de Android Developers: semanal y mensual cada **5 minutos**, trimestral 10 minutos, semestral 15 y anual 30, con un máximo de **6 renovaciones**, tras las cuales se cancelan solas. Con eso se puede probar renovación, gracia, retención y expiración en una sesión de media hora.

**Consecuencia para el backend:** las pruebas de fechas (`current_period_end`, `grace_until`) deben parametrizarse a partir del `expiryTimeMillis` devuelto por la API de Google Play y nunca asumir 30 días fijos.

## 8. RTDN (notificaciones en tiempo real) y API de desarrollador

### RTDN
1. Crear un proyecto en Google Cloud y un tema de Pub/Sub.
2. Dar permiso de publicación sobre el tema a la cuenta de servicio de notificaciones de Google Play (`google-play-developer-notifications@system.gserviceaccount.com`).
3. En Play Console, configurar el nombre del tema en la sección de monetización (RTDN) y enviar una notificación de prueba.
4. Crear una suscripción push hacia `POST /v1/play/rtdn` con verificación del token OIDC de Pub/Sub.
5. En desarrollo local, exponer el backend con un túnel HTTPS temporal (por ejemplo `cloudflared` o `ngrok`) o usar suscripción pull en pruebas.

### API de Google Play Developer
1. Crear una cuenta de servicio en Google Cloud y guardar su clave en `signing-keys/` (excluida de git por `.gitignore`).
2. Invitar esa cuenta en Play Console con permisos para ver datos financieros y administrar pedidos y suscripciones.
3. Probar `purchases.subscriptionsv2.get` con un token real de la prueba P4-1.
4. **Backend:** guardar el evento con su `message_id` (tabla `play_events`) antes de procesarlo, y reconsultar siempre a la API el estado real del token (`BACKEND_LICENSING.md` §6.3).

## 9. Biblioteca de facturación (Play Billing 8.x)

La documentación oficial de Android Developers establece que, desde el **31 de agosto de 2026**, todas las aplicaciones nuevas y actualizaciones enviadas a Google Play deben usar **Play Billing Library 8 o superior**. El `libs.versions.toml` generado originalmente por `setup.sh` fijaba `billing = "7.1.1"`. Ver la propuesta completa de versiones y cambios de API en la **sección 14**.

## 10. Evidencia y entregable

Crear `docs/PLAY_P4_RESULTS.md` con:
1. Fecha, versión de la app, cuentas usadas (sin datos personales).
2. La matriz de la sección 5 completada.
3. Capturas de Play Console y de las compras.
4. Respuestas de la API (con tokens recortados).
5. Decisión cerrada: cupos numerados / add-ons / pase por rodada, con el motivo.
6. Cambios que implica en `BACKEND_LICENSING.md`, `RULES.md` (P4) y `MEMORY.md`.

## 11. Riesgos y precauciones

- No poner claves de cuenta de servicio ni tokens reales en el repositorio.
- Las compras de prueba no deben mezclarse con las de producción: usar solo cuentas de licencia.
- Mantener los IDs de producto tal cual; un ID borrado no se puede reutilizar.
- La política de pagos de Play para bienes digitales restringe cobrar fuera de la tienda: revisar antes de ofrecer otro método de pago.
- Esto no es asesoría legal ni fiscal. Los impuestos y la comisión varían por país.

## 12. Fuentes

- [Subscription with add-ons — Android Developers](https://developer.android.com/google/play/billing/subscription-with-addons)
- [Faster renewals for test subscriptions — Android Developers Blog](https://android-developers.googleblog.com/2018/01/faster-renewals-for-test-subscriptions.html)
- [Sobre las suscripciones — Android Developers](https://developer.android.com/google/play/billing/subs)
- [Migrate to Google Play Billing Library 8 — Android Developers](https://developer.android.com/google/play/billing/migrate-gpblv8)

## 13. Criterio de decisión cerrado (Plan A vs Plan B vs Plan C)

Para proteger la regla de negocio **`RULES.md` R2.3** (*"El usuario puede salirse; el acceso dura hasta el fin del periodo pagado"* sin perder sus demás grupos), se fija el siguiente árbol de decisión antes de ejecutar las pruebas:

| Nivel | Modelo | Productos en Play Console | Condición de adopción / descarte |
|---|---|---|---|
| **Plan A (Por defecto)** | **Cupos numerados independientes** | `leader_monthly` (\$15) + `member_slot_1..5` (\$2.50 c/u) | Se adopta si **P4-2**, **P4-4** y **P4-7** pasan (compras independientes y cancelación aislada por cupo). |
| **Plan B (Evaluación condicional)** | **Suscripción con add-ons** | Compra agrupada con ítems adicionales | **Regla de descarte inmediato:** si **AO-3** confirma que cancelar desde Google Play cancela toda la compra (o si AO-5 confirma que un fallo de pago tumba todos los grupos sin posibilidad de aislamiento), **los add-ons quedan descartados** y no se usan en producción. |
| **Plan C (Contingencia definitiva)** | **Pase por rodada para invitados (P5)** | `leader_monthly` (\$15) + `member_slot_1` (\$2.50 miembro único) + `ride_pass_guest` (consumible/pase por rodada) | Se activa directamente si falla el Plan A y se descarta el Plan B por AO-3. Ya está modelado en `BillingPolicyConfig.MultiSlotStrategy.RIDE_PASS_FALLBACK`. |

## 14. Propuesta de versiones de Play Billing 8.x y compatibilidad (Decisión D41)

De acuerdo con la regla de gobernanza (§4.3: no modificar versiones fijadas sin proponerlas primero), se documenta la matriz de compatibilidad y los cambios de API entre `7.1.1` y `8.0.0`:

### 14.1 Matriz de compatibilidad propuesta (`gradle/libs.versions.toml`)

| Componente | Versión actual (`libs.versions.toml`) | Versión propuesta | Compatibilidad verificada |
|---|---|---|---|
| `com.android.billingclient:billing-ktx` | `7.1.1` (obsoleta el 31-ago-2026) | **`8.0.0`** | Compatible con `minSdk = 26`, `compileSdk = 35`, `targetSdk = 35` y Kotlin `2.0.x` (metadata Kotlin 2.0 soportada nativamente). |
| Android Gradle Plugin (`agp`) | `8.7.3` | **`8.7.3`** (sin cambio) | Soporta `compileSdk = 35` y AARs de Billing `8.0.0`. *(Plan B si una versión futura 8.1+ exigiera `compileSdk = 36`: subir `agp = "8.8.0"` y `compileSdk = 36` manteniendo `minSdk = 26`)*. |
| Kotlin (`kotlin`) | `2.0.21` | **`2.0.21`** (sin cambio) | Totalmente compatible con `billing-ktx:8.0.0`. |
| KSP (`ksp`) | `2.0.21-1.0.28` | **`2.0.21-1.0.28`** (sin cambio) | Alineado con Kotlin `2.0.21` y Room `2.6.1`. |
| Compose BOM (`composeBom`) | `2024.10.01` | **`2024.10.01`** (sin cambio) | Estable con Kotlin `2.0.21` (`org.jetbrains.kotlin.plugin.compose`). |

### 14.2 Cambios de API obligatorios entre Play Billing 7.1.1 y 8.0.0

1. **`queryProductDetailsAsync` y `ProductDetailsResponseListener`:**
   - **En PBL 7.x:** `onProductDetailsResponse(billingResult: BillingResult, productDetailsList: List<ProductDetails>)`.
   - **En PBL 8.0.0:** `onProductDetailsResponse(billingResult: BillingResult, queryProductDetailsResult: QueryProductDetailsResult)`. La lista de productos válidos viene en `queryProductDetailsResult.productDetailsList` y los productos no recuperables o mal configurados vienen en `queryProductDetailsResult.unfetchedProductList`.
2. **`BillingFlowParams.SubscriptionUpdateParams.Builder`:**
   - Se eliminan definitivamente `setOldSkuPurchaseToken(...)`, `setReplaceProrationMode(...)` y `setReplaceSkusProrationMode(...)`.
   - Se usan únicamente `setOldPurchaseToken(oldToken)` y `setSubscriptionReplacementMode(SubscriptionUpdateParams.ReplacementMode.*)`.
3. **Compras pendientes (`PendingPurchasesParams`):**
   - `BillingClient.newBuilder(context).enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())` es obligatorio al construir el cliente.
4. **Reconexión automática del servicio:**
   - PBL 8.0.0 introduce `BillingClient.Builder.enableAutoServiceReconnection()`, reduciendo el código manual de reintento ante `BillingResponseCode.SERVICE_DISCONNECTED`.
5. **Eliminación de `queryPurchaseHistoryAsync`:**
   - Para restaurar o auditar compras expiradas no se usa el cliente local, sino el backend (`GET /v1/subscriptions/me` + reconciliación contra `purchases.subscriptionsv2.get`).

## 15. Dependencia con el modelo de sesión, Refresh Token y Logout (Prueba P4-14)

La prueba **P4-14** (*"Reinstalar la app e iniciar sesión con T1"*) requiere que el flujo de sesión en Android y backend esté cerrado antes de ejecutar la matriz:
- **En el backend (`backend/src/lib/auth.ts` y `0001_init.sql`):**
  - El `accessToken` tiene una duración corta (**30 minutos** = `1800 s`).
  - El `refreshToken` (vigencia **30 días**) se almacena en PostgreSQL **únicamente como hash SHA-256** en la tabla `refresh_tokens`, con soporte de **rotación en cada uso** (`POST /v1/auth/refresh`) y **revocación explícita** (`POST /v1/auth/logout`).
- **En Android (Decisión D40):**
  - El `refreshToken` y el par de claves del dispositivo se resguardan mediante **Android Keystore** (`MasterKey` AES256-GCM) y almacenamiento local cifrado, nunca en `SharedPreferences` en texto plano.
