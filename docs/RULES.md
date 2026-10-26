# RULES — Reglas de negocio de Ruta Biker Sync

Versión 1.1 · 2026-10-23
Este documento es la **fuente de verdad** de las reglas. Si otro documento lo contradice, manda este.

## 1. Cuentas
- R1.1 Todos descargan la app gratis y se registran como **Miembro**.
- R1.2 Un miembro que quiere ser **Líder** paga $15/mes y conserva su perfil e historial.
- R1.3 El perfil es personal: avatar, apodo, moto, color en el mapa.

## 2. Estatus en un grupo (lo asigna un líder)
| Estatus | Pertenece a otros grupos | Invitable a rodadas de otros | Cuota |
|---|---|---|---|
| Full member | No, un solo grupo | Sí (como invitado a rodada) | $2.50/mes |
| Prospecto | No, un solo grupo | Sí (como invitado a rodada) | $2.50/mes |
| Invitado a rodada | Pertenece a su propio club (o independiente) | Sí | Sin cuota mensual (acceso temporal a la rodada) |

- R2.1 Nadie entra a un grupo ni a una rodada sin su consentimiento explícito: un líder envía invitación y el usuario acepta.
- R2.2 **Sin membresías cruzadas:** Un usuario no puede tener estatus de `leader`, `full` ni `prospect` en más de un grupo simultáneamente (`trg_memberships_no_cross_membership`). Se prohíbe la membresía permanente de `guest` entre múltiples grupos.
- R2.3 **Invitaciones temporales por rodada:** La única forma de participar con otro grupo es como invitado temporal a una rodada específica (`ride_guest_invitations`), sin crear membresía permanente de club ni cuotas recurrentes adicionales.
- R2.4 El usuario puede salirse de su grupo; el acceso dura hasta el fin del periodo pagado.
- R2.5 El líder puede retirar a un miembro de su grupo.

## 3. Mesa Directiva y Cargos del club
Cargos de la Mesa Directiva: **Presidente**, **Vicepresidente**, **Sargento de Armas**, **Secretario**, **Tesorero**, **Capitán de Ruta**, **Full Patch Member** y **Prospecto**.
- R3.1 **Requisito mínimo para crear un grupo:** Es obligatorio contar con al menos 3 cargos mínimos asignados: **Presidente**, **Vicepresidente** y **Sargento de Armas**. Sin estos 3 cargos cubiertos por miembros del grupo, la plataforma no permite crear el grupo (`trg_groups_minimum_directiva`).
- R3.2 **Capitán de Ruta:** Es un cargo formal de la Directiva pero **opcional** para la apertura del grupo. Si el cargo está vacante en el club, los líderes pueden asignar temporalmente a cualquier miembro calificado el rol de Capitán de rodada para una salida específica.
- R3.3 **Road Marshal:** Deja de ser cargo de la Directiva y pasa a ser exclusivamente un **rol de rodada** (responsable de la seguridad, bloqueos y señales dinámicas durante la marcha).
- R3.4 Los cargos son **etiquetas de gobernanza con permisos delegados** y son **exclusivos para miembros del grupo** (`trg_badges_officer_must_be_member`). Un invitado a rodada no puede ostentar cargos de Mesa Directiva.
- R3.5 Solo los líderes crean, asignan o cambian cargos y estatus.
- R3.6 Los permisos por cargo son configurables por grupo. Valores por defecto:

| Cargo | Permiso por defecto |
|---|---|
| Presidente / Vicepresidente | Máxima autoridad y representación honorífica (líder administrativo solo si se le asigna) |
| Sargento de Armas | Asistencia, disciplina, seguridad en eventos y cumplimiento de estatutos |
| Capitán de Ruta | Propone rutas oficiales, paradas y orden de marcha |
| Secretario | Custodia de actas, historial de rodadas, asistencia y registros |
| Tesorero | Semáforo de pagos (solo lectura) y balances del club |
| Full Patch Member | Miembro pleno con derecho a voz y voto en decisiones del club |
| Prospecto | Miembro en periodo de prueba con deberes pero sin derecho a voto |

## 4. Líderes
- R4.1 **Un líder = un solo grupo.** Cada líder administra y responde únicamente por su propio grupo y paga **$15/mes**. Un usuario no puede estar registrado como líder en más de un grupo (`trg_group_leaders_single_group_per_user`). No existe tarifa ni estatus de líder multi-grupo.
- R4.2 Hasta **3 líderes por grupo**, cada uno paga **$15/mes** individualmente.
- R4.3 Los líderes tienen todas las funciones: administrar miembros, cargos, estatus, rodadas, rutas e **insignias**.
- R4.4 Hay un **líder principal (Fundador)**, único con poder de: nombrar o retirar a otros líderes y transferir el grupo.
- R4.5 El líder paga solo $15 y no paga $2.50 en su propio grupo. Un líder no puede ser líder, full member ni prospecto en otro grupo (solo invitado temporal a rodadas específicas de otros clubes).
- R4.6 Si el principal no paga durante 7 días, el rol pasa al siguiente líder y se avisa a todos.
- R4.7 Si fallan los 3 líderes, el grupo se suspende: no se inician rodadas, pero el historial e insignias se conservan.
- R4.8 Cualquier líder del grupo puede asignar insignias de honor.

## 5. Grupos
- R5.1 Nombre único por país (normalizado: sin acentos, mayúsculas, símbolos ni sustituciones leet).
- R5.2 Logo único por país (comparación por huella de imagen). Puede repetirse entre países.
- R5.3 El grupo es único y solo puede **transferirse entre miembros del grupo**: el receptor debe aceptar, tener cuenta de líder activa y se aplica una espera de 7 días con posible cancelación.
- R5.4 Botón de denuncia por imitación y revisión manual. El registro no reemplaza una marca registrada.

## 6. Rodadas
- R6.1 La mesh, el mapa compartido y la voz **solo existen dentro de una rodada activa**.
- R6.2 La rodada tiene lista cerrada de participantes, con su estatus.
- R6.3 Si participan dos grupos, hay un anfitrión (quien crea la rodada) y un coanfitrión. **Cada líder controla a su propio grupo** y ve solo el estado de su gente.
- R6.4 Un invitado de otro grupo solo entra si recibió y aceptó una invitación temporal a esa rodada (`ride_guest_invitations`).
- R6.5 Cierre de la rodada: se borran las notas de voz y el acceso temporal a datos de emergencia.
- R6.6 Una rodada cuenta para el rango si: ≥2 participantes con contacto real en la mesh, >15 km y >30 min en movimiento, presencia ≥70 % del recorrido, cierre confirmado por un líder y máximo 3 rodadas contadas por día.

## 7. Pagos y acceso
- R7.1 Cada persona paga su propia suscripción mensual.
- R7.2 El líder ve un semáforo: 🟢 al día · 🟡 vence en <7 días o en gracia · 🔴 vencido. No ve datos de pago.
- R7.3 Gracia de 3 días (configurable).
- R7.4 Si el pago vence, la credencial caduca y los demás teléfonos rechazan al usuario.
- R7.5 Alarmas de vencimiento: miembro a 7, 3 y 1 día; líder con resumen previo a cada rodada y aviso de su propia suscripción.

## 8. Rangos, insignias y roles de rodada
| Rango | Rodadas válidas | Rol de rodada que habilita |
|---|---|---|
| 🥉 Novato | 0–4 | Participante |
| 🥈 Rider | 5–19 | Proponer rutas |
| 🥇 Veterano | 20–49 | Scout |
| 💎 Élite | 50–99 | Sweep (cierre) |
| 👑 Leyenda | 100+ | Capitán de rodada |

- R8.1 El rango es personal, global y automático. Cuentan las rodadas con su grupo y las rodadas válidas en las que haya participado como invitado temporal en otros clubes.
- R8.2 🔥 **Fundador** no es un rango: es una marca permanente del creador del grupo.
- R8.3 Los líderes pueden asignar roles de rodada (Scout, Sweep, Capitán de rodada, **Road Marshal**) saltándose el requisito de rango si la logística lo amerita.
- R8.4 **Familias y reglas de cruce de insignias:**
  - **Insignias de Mesa Directiva** (Presidente, Vicepresidente, Sargento de Armas, Capitán de Ruta, Secretario, Tesorero, Full Patch Member, Prospecto): **NO son cruzables entre grupos**. Solo pueden ser asignadas a miembros oficiales del grupo emisor (`trg_badges_officer_must_be_member`).
  - **Insignias de Rango y Logros:** Automáticas y personales (Novato, Rider, Veterano, Élite, Leyenda, Rodada Nocturna, 500 km, Sin Cobertura, 3 Grupos, etc.).
  - **Insignias de Honor:** Las otorga un líder del grupo y llevan el logo del grupo emisor. **SÍ son cruzables** hacia usuarios de otros grupos, con el requisito estricto de que el receptor haya participado previamente como invitado en al menos una rodada del grupo emisor (`trg_badges_honor_requires_guest_ride`). Quedan registradas en su expediente.
- R8.5 Las insignias de honor no suman para el rango. Hay límite mensual de entrega y registro de auditoría de quién las otorgó.
- R8.6 **Gamificación y Logros (Fase 6):** El sistema integral de logros de kilometraje acumulativo (10 km a 100.000 km), constancia (racha mensual ininterrumpida), logros de experiencia e hitos únicos, y el cálculo de rangos mediante XP se especifican en detalle en [GAMIFICATION.md](file:///d:/Ruta-Biker-Sync/docs/GAMIFICATION.md).

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

## 12. Decisiones y Pendientes
| # | Tema | Estado y Resolución |
|---|---|---|
| **P1** | ¿Un líder puede ser `guest` en otro grupo? | **CERRADO (2026-10-23, D43):** No como membresía. Solo puede ser invitado temporal a una rodada específica (`ride_guest_invitations`). |
| **P2** | ¿Un líder de varios grupos paga $15 una vez o por grupo? | **CERRADO (2026-10-23, D42):** Un líder = un solo grupo. Paga $15/mes por su único grupo. Prohibido liderar múltiples grupos. |
| **P3** | Retiro o reembolso a mitad de mes | **Propuesta activa:** Acceso hasta el fin del periodo pagado. |
| **P4** | Cómo cobrar varios cupos de invitado en Google Play | Validar cupos numerados (`member_slot_1..5`) en consola Play. **Plan B:** pase por rodada consumible (`ride_pass_guest`). |
| **P5** | Pase por rodada para invitados ocasionales | Plan B de P4 para acceso temporal a rodadas. |
| **P6** | ¿Límite de grupos principales por cuenta de líder? | **CERRADO (2026-10-23, D42):** No aplica. Quedó prohibido liderar más de un grupo. |
| **P7** | ¿Un invitado puede ostentar cargos de la Directiva? | **CERRADO (2026-10-23, D44 y D46):** No. Los cargos de Mesa Directiva se reservan exclusivamente a miembros oficiales del grupo. |
| **P9** | Sistema de logros de kilometraje, racha y progresión por XP | **CERRADO (2026-10-23, D48 y D49):** Aprobada curva de 10 km a 100.000 km (P9.1), logros personales no cruzables (P9.2) y progresión por XP para Fase 6 (P9.3). Consolidado en `GAMIFICATION.md`. |

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
- R15.4 Un invitado a una rodada consiente específicamente para esa salida.
- R15.5 La exportación de datos incluye membresías, suscripciones, consentimientos e historial de rodadas; los datos locales (bóveda, ficha) se exportan desde el teléfono.
- R15.6 Las insignias recibidas se conservan como registro del grupo emisor, anonimizadas si el usuario elimina su cuenta.

## 16. Jerarquía de Clubes (RC / MG / Gang / MC)
Integrada dentro del alcance oficial en Fase 6 (Gamificación y documentos), regulando la progresión estructural del motociclismo organizado mediante las columnas `groups.tier` y `groups.sponsor_group_id`:

| Nivel | Nombre | Concepto y Requisitos | Estructura y Parche |
|---|---|---|---|
| **RC** | Riding Club | Punto de partida inmediato al crear el grupo con la directiva mínima (Presidente, VP, Sargento). Enfoque en rodar y socializar sin compromisos vitalicios ni apadrinamiento. | Parche libre de 1 pieza. Estructura flexible. |
| **MG** | MotoGroup | "Club familiar". Requiere entre 1 y 3 años de operación formal activa y estatutos internos más estrictos. | Parche de 1 pieza (redondo u ovalado) sin las letras "MC". Admite mujeres en directiva. |
| **Gang** | Gang (Transición) | Etapa de transición previa a MC. Requiere mínimo 3 años previos como MG, cilindrada superior a 500cc en todas las motos de los miembros y participación en eventos regionales/aniversarios. | Parche de 3 piezas pero sin las letras "MC". |
| **MC** | MotoClub | Máximo estatus tradicional. Requiere apadrinamiento formal por un MC padrino registrado (`sponsor_group_id`), aprobación del comité de líderes del MC padrino y directiva completa. | Parche de 3 piezas tradicional con corte territorial y las letras "MC". Estatutos estrictos de hermandad. |

## 17. Generación y Compartición de Código QR de Invitación
- R17.1 (Confirmada por Oscar, 2026-10-26): El código QR para invitar y afiliar miembros a un grupo únicamente puede ser generado y compartido por el **Presidente** y el **Vicepresidente** del club (`ClubRole.PRESIDENT`, `ClubRole.VICE_PRESIDENT` o Fundador). Los demás miembros y cargos de directiva (Tesorero, Capitán de Ruta, Sargento de Armas, Secretario) tienen la función bloqueada.
- R17.2 Respaldo Alfanumérico: Si la cámara o condiciones de iluminación no permiten escanear el QR, se mantiene el código alfanumérico manual (ej. `RUTA-TEST-01`) como canal de entrada alternativo.
