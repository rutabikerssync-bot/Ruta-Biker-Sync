# RULES — Reglas de negocio de Ruta Biker Sync

Versión 1.0 · 2026-10-16
Este documento es la **fuente de verdad** de las reglas. Si otro documento lo contradice, manda este.

## 1. Cuentas
- R1.1 Todos descargan la app gratis y se registran como **Miembro**.
- R1.2 Un miembro que quiere ser **Líder** paga $15/mes y conserva su perfil e historial.
- R1.3 El perfil es personal: avatar, apodo, moto, color en el mapa.

## 2. Estatus en un grupo (lo asigna un líder)
| Estatus | Pertenece a otros grupos | Invitable a rodadas de otros | Cuota |
|---|---|---|---|
| Full member | No, un solo grupo | Sí | $2.50/mes |
| Prospecto | No, un solo grupo | Sí | $2.50/mes |
| Invitado | Sí, a varios | Sí | $2.50/mes por cada grupo |

- R2.1 Nadie entra a un grupo sin su consentimiento: un líder envía invitación y el usuario acepta.
- R2.2 El invitado paga mensualidad en cada grupo aunque no ruede.
- R2.3 El usuario puede salirse; el acceso dura hasta el fin del periodo pagado.
- R2.4 El líder puede retirar a un miembro.

## 3. Cargos del club
Presidente, Vicepresidente, Capitán de Ruta, Sargento de Armas, Secretario, Tesorero, Road Marshal.
- R3.1 Los cargos son **etiquetas con permisos delegados**, no son lo mismo que ser líder.
- R3.2 Solo los líderes crean, asignan o cambian cargos y estatus.
- R3.3 Los permisos por cargo son configurables por grupo. Valores por defecto:

| Cargo | Permiso por defecto |
|---|---|
| Presidente / Vicepresidente | Honorífico (líder solo si se le asigna) |
| Capitán de Ruta | Propone rutas y orden de marcha |
| Road Marshal | Seguridad y señales en la rodada |
| Sargento de Armas | Asistencia y orden en eventos |
| Secretario | Ve asistencia e historial |
| Tesorero | Ve el semáforo de pagos (solo lectura) |

## 4. Líderes
- R4.1 Hasta **3 líderes por grupo**, cada uno paga **$15/mes** individualmente.
- R4.2 Los líderes tienen todas las funciones: administrar miembros, cargos, estatus, rodadas, rutas e **insignias**.
- R4.3 Hay un **líder principal (Fundador)**, único con poder de: nombrar o retirar a otros líderes y transferir el grupo.
- R4.4 El líder paga solo $15 y no paga $2.50 en su propio grupo.
- R4.5 Si el principal no paga durante 7 días, el rol pasa al siguiente líder y se avisa a todos.
- R4.6 Si fallan los 3 líderes, el grupo se suspende: no se inician rodadas, pero el historial e insignias se conservan.
- R4.7 Nota: la regla previa de que solo el líder principal entrega insignias de honor queda **reemplazada**: cualquier líder del grupo puede asignarlas.

## 5. Grupos
- R5.1 Nombre único por país (normalizado: sin acentos, mayúsculas, símbolos ni sustituciones leet).
- R5.2 Logo único por país (comparación por huella de imagen). Puede repetirse entre países.
- R5.3 El grupo es único y solo puede **transferirse entre miembros del grupo**: el receptor debe aceptar, tener cuenta de líder activa y se aplica una espera de 7 días con posible cancelación.
- R5.4 Botón de denuncia por imitación y revisión manual. El registro no reemplaza una marca registrada.

## 6. Rodadas
- R6.1 La mesh, el mapa compartido y la voz **solo existen dentro de una rodada activa**.
- R6.2 La rodada tiene lista cerrada de participantes, con su estatus.
- R6.3 Si participan dos grupos, hay un anfitrión (quien crea la rodada) y un coanfitrión. **Cada líder controla a su propio grupo** y ve solo el estado de su gente.
- R6.4 Un invitado de otro grupo solo entra si fue agregado a ese grupo.
- R6.5 Cierre de la rodada: se borran las notas de voz y el acceso temporal a datos de emergencia.
- R6.6 Una rodada cuenta para el rango si: ≥2 participantes con contacto real en la mesh, >15 km y >30 min en movimiento, presencia ≥70 % del recorrido, cierre confirmado por un líder y máximo 3 rodadas contadas por día.

## 7. Pagos y acceso
- R7.1 Cada persona paga su propia suscripción mensual.
- R7.2 El líder ve un semáforo: 🟢 al día · 🟡 vence en <7 días o en gracia · 🔴 vencido. No ve datos de pago.
- R7.3 Gracia de 3 días (configurable).
- R7.4 Si el pago vence, la credencial caduca y los demás teléfonos rechazan al usuario.
- R7.5 Alarmas de vencimiento: miembro a 7, 3 y 1 día; líder con resumen previo a cada rodada y aviso de su propia suscripción.

## 8. Rangos, insignias y roles de rodada
| Rango | Rodadas válidas | Rol que habilita |
|---|---|---|
| 🥉 Novato | 0–4 | Participante |
| 🥈 Rider | 5–19 | Proponer rutas |
| 🥇 Veterano | 20–49 | Scout |
| 💎 Élite | 50–99 | Sweep (cierre) |
| 👑 Leyenda | 100+ | Capitán de rodada |

- R8.1 El rango es personal, global y automático. Cuentan las rodadas con cualquier grupo, incluso como invitado.
- R8.2 🔥 **Fundador** no es un rango: es una marca permanente del creador del grupo.
- R8.3 Los líderes pueden asignar un rol de rodada saltándose el requisito de rango.
- R8.4 Familias de insignias: **Rango** (automática), **Logros** (automática), **Honor** (la otorga un líder del grupo, lleva el logo del grupo emisor y puede darse a invitados).
- R8.5 Las insignias de honor no suman para el rango. Hay límite mensual de entrega y registro de quién las dio.

## 9. Voz
- R9.1 Solo por **clips** (máx. 15 s) y solo dentro de rodadas.
- R9.2 Se reproduce y se borra. No hay historial de voz.
- R9.3 Los avisos de pérdida de cobertura usan voz sintética local.

## 10. Datos personales y emergencia
- R10.1 La **bóveda de documentos** es personal y cifrada en el teléfono. Los documentos tienen alarmas de vencimiento (30, 7 y 1 día).
- R10.2 Los líderes pueden abrir **solo el paquete de emergencia** (identificación, seguro, ficha médica, contactos), nunca la bóveda completa.
- R10.3 Requisitos: consentimiento previo y separado, cifrado sellado para los líderes de esa rodada, desbloqueo solo por SOS o declaración de emergencia de un líder con confirmación, acceso limitado a 60 min con registro, borrado al cerrar la rodada y aviso posterior al afectado.
- R10.4 El backend no guarda ubicación, voz, documentos ni datos de salud.
- R10.5 La app no sustituye atención médica ni servicios de emergencia.

## 11. Cumplimiento
- R11.1 Venta mundial: GDPR, LGPD, CCPA y políticas de Google Play (ubicación en segundo plano, datos de salud).
- R11.2 Borrado y exportación de cuenta bajo petición.
- R11.3 Idiomas: español, inglés y portugués.
- R11.4 Esto es orientación general; se requiere revisión legal.

## 12. Pendientes por decidir
| # | Tema | Propuesta actual |
|---|---|---|
| P1 | ¿Un líder pagando solo $15 es invitado gratis en otros grupos? | Propuesta: paga $2.50 como invitado para evitar evasión. **Por confirmar** |
| P2 | ¿Un líder de varios grupos paga $15 una vez o por grupo? | Propuesta: $15 por persona. **Por confirmar** |
| P3 | Retiro o reembolso a mitad de mes | Acceso hasta fin del periodo pagado |
| P4 | Cómo cobrar varios cupos de invitado en Google Play | Probar cupos numerados en consola (ver `BACKEND_LICENSING.md` y `PLAY_TEST_SETUP.md`). **Plan B si falla:** pase por rodada para invitados |
| P5 | ¿Pase por rodada para invitados ocasionales? | Fuera del alcance inicial; pasa a ser el plan B de P4 |
| P6 | ¿Existe un límite de grupos donde una misma cuenta de líder puede ser líder principal? | Propuesta: máximo 2 grupos principales por cuenta de líder (`MAX_PRINCIPAL_GROUPS_PER_LEADER` en `app.ts`, desactivado por defecto hasta confirmación del dueño). **Por confirmar** |
| P7 | ¿Un invitado (`guest`) puede ostentar cargos honoríficos del club (`R3`)? | Propuesta: los cargos del club (`R3.1`) se reservan a miembros `full` (`forbidGuestClubRoles = false` por defecto hasta confirmación del dueño). **Por confirmar** |

## 13. Moderación y convivencia
- R13.1 Las notas de voz no se guardan, pero cualquier participante puede usar el botón **"Reportar rider"** durante o después de la rodada, indicando motivo (insultos, acoso, conducta peligrosa, otro).
- R13.2 El reporte llega a los líderes del grupo del reportado y queda en `moderation_reports` (sin contenido de audio, solo motivo, hora y rodada).
- R13.3 Un clip ofensivo se puede **silenciar** localmente al instante (el receptor silencia a ese rider durante la rodada).
- R13.4 Sanciones posibles: advertencia, silenciado en la rodada, expulsión de la rodada y retiro del grupo. Las decide un líder según R14.
- R13.5 Un reporte contra un líder lo revisan los otros líderes del grupo; si no hay otro líder, se escala a soporte de la plataforma.
- R13.6 Reglas de convivencia aceptadas al registrarse: respeto, uso responsable, prohibido usar la app mientras se conduce de forma peligrosa.
- R13.7 Conductas graves (amenazas, contenido ilegal) pueden causar suspensión de la cuenta por parte de la plataforma.

## 14. Resolución de conflictos entre líderes
- R14.1 **Decisiones ordinarias** (rodadas, rutas, cargos, insignias, estatus): las toma cualquier líder.
- R14.2 **Decisiones sensibles** requieren **mayoría de los líderes del grupo** (2 de 3; con 2 líderes, ambos; con 1, él solo): expulsar a un miembro, retirar a un líder, declarar una emergencia para abrir paquetes sin SOS, cambiar nombre o logo del grupo.
- R14.3 Excepciones: solo el **líder principal** puede transferir el grupo y nombrar o retirar líderes, sujeto a R14.2 en el caso de retirar.
- R14.4 Un líder no puede votar su propia sanción.
- R14.5 Todas las decisiones sensibles quedan en el registro de auditoría con quién las propuso y quién las aprobó.
- R14.6 Un líder puede vetar la **expulsión** de otra persona solo si el voto en contra impide la mayoría; no existe veto individual absoluto.
- R14.7 En una rodada conjunta de dos grupos cada líder decide sobre su gente; las decisiones sobre la rodada las toma el anfitrión.

## 15. Gestión de consentimientos
- R15.1 Los consentimientos son **por grupo y por tipo**, nunca globales: (a) pertenencia y cuota, (b) compartir ubicación en rodadas, (c) paquete de emergencia, (d) notificaciones.
- R15.2 El usuario puede ver y revocar sus consentimientos en cualquier momento (`GET /v1/users/me/consents`); revocar (b) lo saca de las rodadas de ese grupo, revocar (c) invalida el paquete sellado.
- R15.3 Al **borrar la cuenta** se cancelan las membresías, se notifica a los líderes de cada grupo afectado, se eliminan los datos del backend y se conservan solo los registros que la ley exija, anonimizados.
- R15.4 Un invitado en varios grupos decide por separado en cada uno.
- R15.5 La exportación de datos incluye membresías, suscripciones, consentimientos e historial de rodadas; los datos locales (bóveda, ficha) se exportan desde el teléfono.
- R15.6 Las insignias recibidas se conservan como registro del grupo emisor, anonimizadas si el usuario elimina su cuenta.
