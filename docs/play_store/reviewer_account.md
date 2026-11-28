# Cuenta de revisor — acceso para Google Play

**Estado:** PROCEDIMIENTO PREPARADO; **credenciales NO ENCONTRADAS**. El propietario debe crear la cuenta y pegarlas solo en Play Console (no versionar contraseñas).
**Fecha:** 2026-11-27 · **Package:** `com.rutabikersync.app`

> No se inventan correos, contraseñas ni nombres de club. Los valores entre `<...>` son placeholders que debes sustituir por datos reales de tu cuenta de prueba.

## 1. Datos a crear

| Campo | Valor | Estado |
|---|---|---|
| Correo de revisor | `<REVIEWER_EMAIL>` (cuenta Google dedicada, ejemplo pattern `rbs.reviewer.<nombre>@gmail.com`) | **NO ENCONTRADO** |
| Contraseña | `<REVIEWER_PASSWORD>` | **NO ENCONTRADO** — no guardar en el repo |
| Método de acceso | Google Sign-In / correo+contraseña según el proveedor configurado en el backend | Confirmar en tu backend |
| Club de prueba | `<CLUB_DE_PRUEBA>` | **NO ENCONTRADO** |
| Rodada de prueba | `<RODADA_DE_PRUEBA>` | **NO ENCONTRADO** |
| Suscripción de prueba | `<ESTADO_SUSCRIPCION>` (activa/prueba de Google Play) | **NO ENCONTRADO** si la función de Líder la exige |

## 2. Creación de la cuenta

1. Crea una cuenta de Google exclusiva para pruebas, distinta de la cuenta personal del propietario.
2. Configura contraseña fuerte y guárdala en tu gestor de secretos; **no la escribas en este archivo**.
3. Si la app usa Google Sign-In, añade `<REVIEWER_EMAIL>` como usuario autorizado/tester en tu proveedor de autenticación.
4. Si la app usa correo+contraseña, registra la cuenta desde la pantalla de acceso de la app con `<REVIEWER_EMAIL>` / `<REVIEWER_PASSWORD>`.
5. Inicia sesión en un dispositivo de prueba con esa cuenta y completa el perfil (nombre, avatar y motocicleta de prueba).
6. Añade la misma cuenta al grupo de **Closed testing** para que pueda instalar la build de prueba desde Play Store.

## 3. Cómo acceder a las funciones críticas

| Función | Ruta exacta en la app | Acción del revisor | Verificación esperada |
|---|---|---|---|
| Rodada | Inicio → **Iniciar Ruta Libre** o pestaña **Rodadas** → crear/unirse a `<RODADA_DE_PRUEBA>` | Inicia la rodada y abre la cabina | Mapa/HUD del convoy y notificación persistente del servicio en primer plano |
| RSVP | Pestaña **Rodadas** → detalle de `<RODADA_DE_PRUEBA>` → confirmar asistencia | Marca *Asistiré* / confirmar | El estado del asistente cambia en el detalle de la rodada |
| Chat | Conversación del club o de la rodada en la cabina/pestaña del club | Envía un mensaje de prueba corto | El mensaje aparece en la conversación |
| SOS | Cabina de la rodada → botón **SOS** | Pulsa y **cancela** la cuenta regresiva antes de cualquier envío real | Cuenta regresiva visible y cancelación; **no llamar a emergencias reales** |
| PTT | Cabina de la rodada → botón **PTT** | Mantén presionado, habla y suelta | Se solicita micrófono y el botón responde; la voz extremo a extremo sigue pendiente de validación humana (no bloquear el formulario por eso) |
| Bóveda / ficha médica | Perfil → **Bóveda** | Abre la ficha médica de prueba | Datos cifrados localmente; no enviar datos médicos reales |
| Política legal | Perfil → engranaje → **Privacidad y términos** | Abre la pestaña *Privacidad* | Texto coherente con `/privacy.html` y sin `ACCESS_BACKGROUND_LOCATION` |

> Para SOS y PTT usa solo el dispositivo de prueba y avisa a los testers de que es una simulación controlada. No modificar GATT, crypto, `HeartbeatFrame` ni voz.

## 4. Cargar las credenciales en Play Console

1. Play Console → la app → **App content** → **App access** (o *Store settings → App access*).
2. Marca: la app **requiere acceso** para probar todas las funciones.
3. En *Instructions to access the app*, pega este texto sustituyendo los placeholders:

```text
Correo de revisor: <REVIEWER_EMAIL>
Contraseña: <REVIEWER_PASSWORD>
Club de prueba: <CLUB_DE_PRUEBA>
Rodada de prueba: <RODADA_DE_PRUEBA>

1. Inicia sesión con el correo y la contraseña anteriores.
2. Abre Rodadas y confirma asistencia (RSVP) en <RODADA_DE_PRUEBA>.
3. Inicia la rodada para ver mapa, HUD y notificación persistente.
4. En la cabina, envía un mensaje de chat de prueba.
5. Prueba el botón SOS y cancela la cuenta regresiva (no llames a emergencias reales).
6. Mantén presionado PTT, habla y suelta (se solicitará permiso de micrófono).
7. Perfil → Ajustes → Privacidad y términos para revisar la política.
```

4. Guarda y comprueba que el revisor pueda iniciar sesión con esas credenciales antes de enviar la build.
5. Registra aquí la fecha y captura de la validación del revisor (PENDIENTE; no se inventa).
