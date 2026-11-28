# IARC — respuestas exactas al cuestionario

**Estado:** RESPUESTAS PREPARADAS; **NO enviadas**. La clasificación definitiva la emite Play Console.
**Fecha:** 2026-11-27 · **Package:** `com.rutabikersync.app`
**Fuentes:** `docs/play_store/iarc.md`, `docs/play_store/data_safety_form.md`, `docs/BUSINESS_MODEL.md`, `docs/BACKEND_LICENSING.md`.

## 1. Respuestas por bloque

| Bloque / pregunta | Respuesta a marcar | Justificación verificable |
|---|---|---|
| **Violencia** (gore, peleas, armas) | **No** | Sin contenido violento. Las alertas SOS son avisos de emergencia, no representación gráfica de violencia. |
| **Contenido sexual** (desnudos, insinuaciones) | **No** | Sin contenido sexual en la app ni en los textos propios. |
| **Lenguaje** (palabras ofensivas) | **Sí, generado por usuarios** (marcar *user-generated content* si el formulario lo ofrece) | Chat y PTT entre miembros del club. No hay lenguaje ofensivo propio; existe reporte/sanción progresiva prevista en `RULES` §13–14 (`docs/play_store/iarc.md`). |
| **Drogas / tabaco / alcohol** | **No** | Sin referencias promocionales ni contenido relacionado. |
| **Juego de azar / apuestas** | **No** | Sin casino, apuestas ni ruleta. |
| **Interacción social** | **Sí** | Chat, invitaciones, RSVP de rodadas, miembros del club y voz PTT entre pilotos. |
| **Contenido generado por usuarios** | **Sí** | Mensajes, nombres, avatares y clips PTT; moderación por líderes del club según `RULES` §13–14. |
| **Ubicación** | **Sí, en primer plano durante la rodada; no se almacena en el servidor** | No es una pregunta de clasificación IARC, pero debe ser coherente con Data Safety: `ACCESS_FINE_LOCATION`/`COARSE_LOCATION` + `FOREGROUND_SERVICE_LOCATION`; sin `ACCESS_BACKGROUND_LOCATION`. |
| **Compras en la app** | **Sí** | Suscripción de Líder `$15/mes` y de Miembro `$2.50/mes`, gestionadas por Google Play (`docs/BACKEND_LICENSING.md`, `docs/BUSINESS_MODEL.md`). |
| **Anuncios** | **No** | Sin SDK publicitario en el cliente Android; los banners propios son del sitio web. |
| **Público objetivo** | **Adultos y mayores de 13 años con supervisión de un adulto responsable** | Criterio de seguridad vial y de la política de privacidad publicada. |

## 2. Clasificación propuesta

**Propuesta:** `Teen` (12+) por contenido generado por usuarios (chat/voz) y compras en la app.

Si Play Console, con estas respuestas, asigna `Everyone 10+` u otra categoría, **esa será la clasificación vigente**; no se inventa un resultado antes de enviar el formulario.

## 3. Pasos en Console

1. App content → **Content rating** → empezar el cuestionario IARC.
2. Responder según la tabla anterior; no marcar violencia, sexo, drogas ni apuestas.
3. En *Target audience*, indicar adultos y mayores de 13 con supervisión cuando aplique.
4. Guardar y copiar el **código IARC** y la clasificación asignada.
5. Registrar en este archivo: clasificación real, código IARC, fecha y captura (PENDIENTE; requiere Console).

## 4. Coherencia que Google puede revisar

- La descripción de la ficha menciona PTT y SOS, por lo que **interacción social** debe ir en `Sí`.
- La ficha menciona bóveda/ficha médica; Data Safety declara salud cifrada localmente.
- La app ofrece suscripciones; **compras en la app** debe ir en `Sí`.
- No hay anuncios en el cliente; **anuncios** debe ir en `No`.
- No se declara ubicación en segundo plano en el manifest; no marcar esa opción en Data Safety ni en permisos.
