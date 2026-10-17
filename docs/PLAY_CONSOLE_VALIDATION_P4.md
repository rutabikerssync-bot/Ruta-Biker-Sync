# Configuración de Productos de Prueba en Google Play Console y Plan de Contingencia (Fase 0)

Versión 1.0 · 2026-10-17
Referencia: `BACKEND_LICENSING.md` (§3, §6, §7c), `RULES.md` (§7, §12 P1–P4), `ROADMAP.md` (Fase 0).

---

## 1. Problema Técnico que Valida este Documento (`RULES.md` P4)

Google Play Billing **no permite** que una misma cuenta de Google compre dos veces el **mismo** `productId` de suscripción activa simultáneamente.
Como en Ruta Biker Sync un **Invitado** puede pertenecer a varios grupos pagando **$2.50/mes por cada grupo** (`RULES.md` R2.2), debemos configurar **productos de cupo independientes numerados** (`Opción A` de `BACKEND_LICENSING.md` §3) y comprobar en Play Console con una cuenta *License Tester* que Google Play permite mantener activos varios cupos a la vez.

---

## 2. Catálogo Exacto de Productos a Crear en Google Play Console

Ruta en Play Console: **Monetizar con Play → Productos → Suscripciones → Crear suscripción** (Paquete: `com.rutabikersync.app`).

| Product ID (`play_product_id`) | Nombre visible | Plan base (`basePlanId`) | Periodo | Precio base (USD) | Gracia (`PAYMENT_GRACE_DAYS`) | Retención de cuenta (`on_hold`) | Uso en `subscriptions` |
|---|---|---|---|---|---|---|---|
| `leader_monthly` | Plan Líder Ruta Biker Sync | `leader-monthly-base` | Mensual (P1M) | **$15.00** | 3 días | 30 días | `kind = 'leader'`, `assigned_group_id = NULL` |
| `member_slot_1` | Cupo de Grupo #1 (Miembro / Invitado) | `slot-1-monthly` | Mensual (P1M) | **$2.50** | 3 días | 30 días | `kind = 'member_slot'`, asignado al grupo 1 |
| `member_slot_2` | Cupo de Grupo #2 (Invitado) | `slot-2-monthly` | Mensual (P1M) | **$2.50** | 3 días | 30 días | `kind = 'member_slot'`, asignado al grupo 2 |
| `member_slot_3` | Cupo de Grupo #3 (Invitado) | `slot-3-monthly` | Mensual (P1M) | **$2.50** | 3 días | 30 días | `kind = 'member_slot'`, asignado al grupo 3 |
| `member_slot_4` | Cupo de Grupo #4 (Invitado) | `slot-4-monthly` | Mensual (P1M) | **$2.50** | 3 días | 30 días | `kind = 'member_slot'`, asignado al grupo 4 |
| `member_slot_5` | Cupo de Grupo #5 (Invitado) | `slot-5-monthly` | Mensual (P1M) | **$2.50** | 3 días | 30 días | `kind = 'member_slot'`, asignado al grupo 5 |

> **Nota sobre nomenclatura (`BACKEND_LICENSING.md` §3 vs §4):** En la sección 3 se mencionaba conceptualmente `guest_slot_1..N` y en el esquema SQL de la sección 4 se usa `member_slot_1` con `kind IN ('leader', 'member_slot')`. Se adopta **`member_slot_1` .. `member_slot_5`** en código (`BillingPolicyConfig.memberSlotProductId(index)`) porque un cupo liberado al salir de un grupo puede reutilizarse tanto para ser *Full member / Prospecto* (en su único grupo) como para ser *Invitado* en otro grupo.

### Plan B preconfigurado (si Play rechaza cupos numerados en revisión):
Crear en **Monetizar con Play → Productos → Productos integrados en la aplicación (Compras únicas consumibles)**:
- **`ride_pass_guest`** — Precio: **$2.50 USD** (consumible, válido para asignar acceso temporal a un grupo/rodada cuando `BillingPolicyConfig.p4MultiSlotStrategy = MultiSlotStrategy.RIDE_PASS_FALLBACK`).

---

## 3. Configuración de Cuentas de Prueba (*License Testers*) y RTDN

1. En **Play Console → Configuración → Prueba de licencias**:
   - Añadir los correos Gmail de prueba del equipo de desarrollo.
   - Con cuentas *License Tester*, las suscripciones mensuales se renuevan cada **5 minutos** (hasta 6 renovaciones de prueba) y permiten simular:
     - Tarjeta de prueba que aprueba siempre (`Test card, always approves`) → prueba de alta y renovación.
     - Tarjeta de prueba que rechaza siempre (`Test card, always declines`) → prueba de entrada en **gracia de 3 días (`grace`)** y paso a **`on_hold`**.
2. En **Play Console → Configuración para monetizar → Notificaciones para desarrolladores en tiempo real (RTDN)**:
   - Configurar el tema de Google Cloud Pub/Sub: `projects/<PROJECT_ID>/topics/rbs-play-rtdn`.
   - Suscripción Push hacia `POST /v1/play/rtdn` con verificación de token OIDC (`PLAY_PUBSUB_AUDIENCE`).

---

## 4. Procedimiento para Validar Cupos Múltiples (`P4`) antes de Fase 2

1. Subir el AAB/APK firmado a la pista de **Prueba interna (Internal testing)** en Play Console.
2. En un teléfono con una cuenta *License Tester*, aceptar invitación al **Grupo A** → la app selecciona el primer cupo libre (`member_slot_1`) y lanza el flujo de Play Billing.
3. Sin cancelar `member_slot_1`, aceptar invitación como **Invitado** al **Grupo B** → la app detecta que `member_slot_1` ya tiene `assigned_group_id = Grupo A` y lanza la compra de `member_slot_2`.
4. Verificar en `subscriptions.purchasesv2.get` de Google Play Developer API que ambos `purchase_token` están en estado `SUBSCRIPTION_STATE_ACTIVE` de forma independiente.

---

## 5. Revisión Previa de Políticas de Google Play y Plan de Contingencia (`BACKEND_LICENSING.md` §7c)

| Política Sensible de Google Play | Requisito de Google Play | Cómo lo cumple Ruta Biker Sync |
|---|---|---|
| **Ubicación en segundo plano (`ACCESS_BACKGROUND_LOCATION`)** | Diálogo de divulgación destacada (*Prominent Disclosure*) antes del permiso del sistema + video en consola mostrando el uso durante una rodada activa | La ubicación solo se usa dentro de una rodada activa con servicio en primer plano (`RideService`) visible y nunca se sube al servidor (`RULES.md` R6.1, R10.4). En `SCREENS.md` A5 se pide solo al crear o unirse a la primera rodada. |
| **Datos de salud y emergencia (`Health / Medical info`)** | Consentimiento explícito separado (`R15.1c`), cifrado y aviso visible de que no es un dispositivo médico | `RULES.md` R10.5 y `SCREENS.md` C5/F3 muestran el aviso permanente *"La app no sustituye a los servicios de emergencia"*. El servidor nunca almacena la ficha médica (`BACKEND_LICENSING.md` §1). |
| **Permisos Bluetooth (`BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`)** | Declarar `neverForLocation` en `BLUETOOTH_SCAN` en Android 12+ | Ya declarado en `AndroidManifest.xml`: `android:usesPermissionFlags="neverForLocation"`. |
| **Borrado de cuenta y datos (Política de Eliminación de Cuentas de Play)** | Opción dentro de la app (`DELETE /v1/users/me`) y enlace web | Incluido en `SCREENS.md` F6 y `BACKEND_LICENSING.md` §8. |
