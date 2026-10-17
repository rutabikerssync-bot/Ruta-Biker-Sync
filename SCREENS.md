# SCREENS — Definición de ventanas de Ruta Biker Sync

Versión 1.0 · 2026-10-17
Complementa DESIGN.md (sección 6). Reglas de negocio: RULES.md. Estados de carga: SKELETONS.md.

## 1. Convenciones

**Origen de datos** (determina si hay skeleton, ver SKELETONS.md):
- **L** = local (Room, instantáneo, nunca skeleton)
- **B** = backend (red, puede tardar: lleva skeleton)
- **M** = mesh (tiempo real; usa estado "conectando", no skeleton)
- **L+B** = local primero y refresco desde backend (muestra lo local, sin skeleton salvo primera vez)

**Roles:** `Todos` · `Líder` · `Cargo` (según permisos de RULES R3) · `Rodada` (rol de rodada: Scout, Sweep, Capitán).

**Modos de interfaz:**
- **Normal:** pantallas de gestión, uso en reposo.
- **Rodada (guantes):** botones ≥72 dp, máximo 3 acciones, tema oscuro, sin animaciones decorativas, audio y vibración como respuesta.

**Estados que toda pantalla debe resolver:** `Loading` · `Content` · `Empty` · `Error` · `Offline` · `Stale` (datos viejos mostrados mientras se refresca). Cada ficha indica solo lo particular.

## 2. Mapa de navegación

```
Splash
 ├─ Bienvenida / Login ─ Onboarding ─ Permisos ─ Guía de batería
 └─ Inicio (barra inferior)
     ├─ [Rodadas]  Lista ─ Crear ─ Detalle ─ Sala de espera ─ RODADA ACTIVA ─ Resumen
     ├─ [Grupo]    Mis grupos ─ Grupo ─ Miembro ─ Ajustes ─ Reportes ─ Votaciones ─ Transferencia
     ├─ [Rutas]    Lista ─ Planificador ─ Descarga offline ─ Detalle
     └─ [Perfil]   Perfil ─ Rango e insignias ─ Ficha de emergencia ─ Bóveda ─ Suscripciones ─ Consentimientos ─ Ajustes

Invitaciones (entrada desde notificación, QR o Mis grupos)

RODADA ACTIVA (modo guantes)
 ├─ Mapa en vivo (base)
 ├─ Hablar (PTT)
 ├─ SOS ─ Cuenta regresiva ─ SOS emitido ─ Panel de emergencia (líderes)
 ├─ Aviso de desvío / pérdida de cobertura (superposiciones)
 └─ Reportar rider
```

Barra inferior (modo normal): **Rodadas · Grupo · Rutas · Perfil**. Los miembros sin grupo ven una pantalla "Sin grupo" en la pestaña Grupo con el acceso a invitaciones.

---

## 3. Fichas de pantalla

### A. Arranque y acceso

**A1. Splash**
- Propósito: arrancar, comprobar sesión y credenciales locales.
- Datos: L. Sin skeleton: logo estático, máximo 1,5 s; si la sesión local existe va directo a Inicio.
- Acciones: ninguna.

**A2. Bienvenida / Login**
- Propósito: entrar como miembro; opción "Activar cuenta de líder" (compra $15/mes).
- Datos: B (inicio de sesión). Rol: Todos.
- Estados particulares: error de credenciales, sin internet (permite solo reabrir sesión existente).
- Acciones: iniciar sesión, crear cuenta, aceptar reglas de convivencia y términos (RULES R13.6).

**A3. Onboarding de miembro** (4 pantallas, DESIGN 13)
- Contenido: qué es una rodada, permisos, perfil y avatar, consentimientos separados.
- Datos: L. Sin skeleton.

**A4. Onboarding de líder** (5 pantallas, DESIGN 13)
- Contenido: plan $15, cobro a miembros, crear grupo (nombre y logo únicos), invitar, primera rodada.
- Datos: B en el paso de crear grupo (validación de nombre y logo).
- Skeleton: verificación de unicidad usa indicador inline, no skeleton de pantalla.
- Error clave: nombre o logo ya existente en el país (mensaje claro y sugerencia).

**A5. Permisos**
- Propósito: pedir ubicación, Bluetooth, notificaciones y micrófono con explicación previa de cada uno.
- Datos: L. Estado por permiso: pendiente / concedido / denegado con enlace a ajustes.
- Regla: ubicación en segundo plano solo se pide al crear o unirse a la primera rodada, con justificación visible.

**A6. Guía de batería**
- Propósito: pantalla obligatoria antes de la primera rodada, según el fabricante detectado (Samsung, Xiaomi, Huawei, Oppo…).
- Datos: L. Acciones: abrir ajustes del sistema, marcar "hecho".

### B. Inicio y rodadas

**B1. Inicio (pestaña Rodadas)**
- Propósito: ver la próxima rodada, rodadas pasadas y estado de cuenta.
- Datos: L+B. Rol: Todos.
- Elementos: tarjeta "Próxima rodada", alerta de vencimiento de suscripción, accesos rápidos.
- Skeleton: tarjeta de próxima rodada + 3 filas de historial.
- Vacío: "Aún no tienes rodadas" con botón (líder: "Crear rodada"; miembro: "Ver mis grupos").

**B2. Lista de rodadas**
- Datos: L+B. Filtros: próximas, pasadas, mis grupos.
- Skeleton: lista de 5 tarjetas.

**B3. Crear rodada** (Líder)
- Propósito: nombre, fecha y hora, grupo(s), ruta, participantes, coanfitrión.
- Datos: B (grupos, miembros con semáforo de pago).
- Reglas: solo participantes al día (RULES R7); invitados deben estar agregados (R6.4); si participa otro grupo, su líder debe aceptar.
- Skeleton: formulario con campos fantasma + selector de miembros (lista de filas con avatar).
- Error clave: miembros con pago vencido (se muestran en rojo y quedan excluidos).

**B4. Detalle de rodada**
- Propósito: ver ruta, participantes con estatus, roles de rodada, confirmaciones.
- Datos: L+B. Acciones (Líder): editar, asignar roles (Scout/Sweep/Capitán, con excepción de rango R8.3), cancelar, iniciar sala.
- Skeleton: cabecera de mapa + lista de participantes.

**B5. Sala de espera (pre-rodada)**
- Propósito: sincronizar antes de salir: credencial, lista de revocación, manifiesto, descarga de mapa, comprobación de batería y permisos.
- Datos: B (sincronización forzada, BACKEND 7) + L.
- Estado propio: **lista de verificación** con cada ítem en cargando / ok / aviso. Es una lista de progreso, no un skeleton.
- Acciones: "Iniciar rodada" (solo anfitrión, solo con verificación completa o aviso aceptado), "Estoy listo" (miembros).
- Avisos: sin internet → "la lista de revocación puede estar desactualizada" con fecha de última sincronización.

**B6. Resumen de rodada**
- Propósito: km, tiempo, participantes, rango ganado, insignias, validez para el rango (RULES R6.6).
- Datos: L (calculado en el teléfono) + B (confirmación de cierre por un líder).
- Skeleton: tarjeta de estadísticas + fila de insignias.
- Acciones: compartir tarjeta de rodada, ver repetición.

### C. Rodada activa (modo guantes)

> Regla de oro: **nada en rodada activa depende de la red ni muestra skeleton**. Todo sale de datos locales o de la mesh.

**C1. Mapa en vivo** (pantalla base)
- Propósito: ver a todos los riders, la ruta y el estado de la mesh.
- Datos: M + L (tiles y ruta cacheados).
- Elementos: avatares con color, velocidad, distancia al líder, estado (en ruta / desviado / fuera de mesh); barra superior con batería y nº de nodos; atribución de OpenStreetMap.
- Estados propios: `Conectando` (buscando riders), `Mesh parcial` (islas), `Solo` (sin nadie en rango), `GPS sin fijar`.
- Acciones (máx. 3 en pantalla): **Hablar**, **SOS**, **Señales**.

**C2. Hablar (PTT por clips)**
- Propósito: grabar y enviar clip de hasta 15 s.
- Datos: L → M. Elementos: botón grande de mantener pulsado, contador, anillo de progreso, cola de recepción con avatar de quien habla.
- Estados: grabando, enviando, enviado, falló (reintentar), silenciado (rider silenciado localmente).

**C3. Señales rápidas**
- Propósito: parada, peligro, policía, bache, gasolina, voy a parar.
- Datos: L → M. Rejilla de 6 botones grandes con icono y texto.

**C4. SOS y cuenta regresiva**
- Propósito: emitir SOS manual o confirmar caída detectada (cuenta de 20 s con cancelación grande).
- Datos: L → M. Estados: cuenta regresiva, SOS emitido, SOS recibido de otro rider (alerta a pantalla completa con posición y distancia).
- Sin confirmaciones adicionales; vibración y audio continuos.

**C5. Panel de emergencia** (Líder de la rodada)
- Propósito: abrir el paquete de emergencia tras SOS o declaración (RULES R10.3).
- Datos: L (paquete sellado). Elementos: temporizador de 60 min, datos de identificación, ficha médica, contactos, registro de acceso.
- Flujo de declaración: un líder propone, otro confirma (R14.2).
- Aviso permanente: "La app no sustituye a los servicios de emergencia".

**C6. Superposiciones de evento**
- **Pérdida de cobertura:** banner "X salió de la mesh · última posición hace N min" con botón "Ver en mapa".
- **Reconexión:** banner breve.
- **Desvío de ruta:** alerta con vibración y botón "Voy a parar".
- Datos: M + L. Sin skeleton.

**C7. Reportar rider**
- Propósito: motivo (insultos, acoso, conducta peligrosa, otro) y envío a líderes (RULES R13). Se puede completar después de la rodada.
- Datos: L → B diferido (se encola si no hay internet).

### D. Grupo

**D1. Mis grupos / Sin grupo**
- Propósito: grupos a los que pertenezco, con mi estatus, cargo y estado de pago por grupo; invitaciones pendientes.
- Datos: L+B. Skeleton: 2 tarjetas de grupo con logo circular.
- Vacío: "No perteneces a ningún grupo. Pide a un líder que te invite" + mostrar mi ID/QR.

**D2. Invitación (detalle)**
- Propósito: ver grupo, logo, estatus propuesto y **costo $2.50/mes** y aceptar o rechazar (RULES R2.1).
- Datos: B. Skeleton: logo + nombre + bloque de costo.
- Acciones: aceptar (lanza compra en Play) / rechazar. Consentimientos separados mostrados en el mismo flujo (R15.1).
- Estados: invitación expirada, grupo suspendido, compra pendiente.

**D3. Grupo (vista principal)**
- Propósito: ver miembros, cargos, estatus, semáforo de pagos (líder/tesorero), líderes y actividad.
- Datos: L+B. Pestañas: Miembros · Rodadas · Insignias · Ajustes.
- Skeleton: cabecera con logo + 8 filas de miembro con avatar.
- Semáforo 🟢🟡🔴 con texto, no solo color.

**D4. Miembro (detalle)**
- Propósito: perfil público, estatus, cargo, rango, insignias, rodadas con el grupo.
- Acciones (Líder): cambiar estatus o cargo, dar insignia de honor, retirar (sujeto a votación si es sensible), reportes.
- Datos: B. Skeleton: avatar grande + bloques de datos + fila de insignias.

**D5. Ajustes del grupo** (Líder)
- Propósito: nombre y logo (cambio = decisión sensible), permisos por cargo, líderes (hasta 3), transferencia.
- Datos: B. Skeleton: lista de ajustes.

**D6. Agregar miembro** (Líder)
- Propósito: buscar por ID o QR, elegir estatus (full / prospecto / invitado), enviar invitación (estado pendiente).
- Datos: B. Estados: usuario no encontrado, ya pertenece a otro grupo como full/prospecto (RULES R2).

**D7. Reportes de moderación** (Líder)
- Propósito: bandeja con motivo, rodada y estado; acciones advertir, silenciar, expulsar de la rodada, proponer retiro del grupo.
- Datos: B. Skeleton: lista de tarjetas.

**D8. Votaciones de líderes** (Líder)
- Propósito: decisiones sensibles que requieren mayoría (RULES R14.2): propuesta, votos, caducidad.
- Datos: B. Skeleton: 2–3 tarjetas con barra de votos.

**D9. Transferencia de grupo** (Líder principal)
- Propósito: elegir un miembro con cuenta de líder activa, espera de 7 días con cancelación (R5.3).
- Datos: B. Estados: pendiente, en espera con cuenta atrás, cancelada, completada.

### E. Rutas

**E1. Lista de rutas**
- Datos: L. Muestra rutas guardadas, tamaño en disco y si están descargadas offline. Sin skeleton (local).

**E2. Planificador de ruta**
- Propósito: trazar con BRouter (perfil moto), importar GPX, ajustar waypoints.
- Datos: L (BRouter offline) con mapa. Skeleton: solo para el mapa base mientras carga tiles (ver SKELETONS).
- Acciones: guardar, descargar corredor ±10 km, compartir con la rodada.

**E3. Descarga offline**
- Propósito: elegir corredor, ver tamaño estimado, progreso, pausar y reanudar, borrar.
- Datos: B (descarga) con progreso real. Es una **barra de progreso**, no un skeleton. Estados: pausada por falta de Wi-Fi, espacio insuficiente.

**E4. Detalle de ruta**
- Datos: L. Elementos: mapa, distancia, desnivel, POIs (gasolineras), estado de descarga y de compartido (hash verificado).

### F. Perfil y cuenta

**F1. Perfil personal**
- Propósito: avatar, apodo, moto, color en el mapa, rango, insignias, grupos.
- Datos: L+B. Skeleton: avatar circular + 3 líneas + fila de insignias.

**F2. Rango e insignias**
- Propósito: progreso del rango (rodadas válidas), insignias de rango, logros y honor con el logo del grupo emisor.
- Datos: B. Skeleton: barra de progreso + rejilla de insignias 3×N.
- Vacío: "Aún no tienes insignias" con explicación de cómo ganarlas.

**F3. Ficha de emergencia**
- Propósito: sangre, alergias, medicamentos, condiciones, contactos, seguro; permisos por campo (RULES R10).
- Datos: L (cifrado). Sin skeleton. Acceso protegido por biometría.
- Aviso permanente: "No sustituye a los servicios de emergencia".

**F4. Bóveda de documentos**
- Propósito: licencia, seguro, registro, revisión técnica; alarmas a 30/7/1 días.
- Datos: L (cifrado). Sin skeleton (la miniatura usa un placeholder de documento al descifrar).
- Acciones: añadir, ver, mostrar rápido, eliminar. Semáforo de vencimiento con texto.

**F5. Suscripciones**
- Propósito: mi plan, vencimientos, cupos por grupo con su estado, enlace a gestión en Google Play.
- Datos: B. Skeleton: tarjeta de plan + filas de cupos.
- Estados: gracia (aviso amarillo), retenida, vencida (acceso limitado con aviso).

**F6. Consentimientos**
- Propósito: ver y revocar por grupo y por tipo (RULES R15.2).
- Datos: B. Skeleton: lista agrupada por grupo con interruptores.
- Acciones: revocar, exportar mis datos, borrar mi cuenta (con aviso a líderes).

**F7. Ajustes**
- Propósito: idioma (es/en/pt), tema, unidades, volumen de avisos, modo guantes, ahorro de batería, ayuda y soporte.
- Datos: L.

---

## 4. Resumen de datos por pantalla

| Origen | Pantallas |
|---|---|
| **L** (sin skeleton) | A1, A3, A5, A6, E1, E4, F3, F4, F7 |
| **L+B** (skeleton solo la primera vez) | B1, B2, B4, D1, D3, F1 |
| **B** (skeleton) | A2, A4, B3, B6, D2, D4, D5, D6, D7, D8, D9, F2, F5, F6 |
| **M** (estados de conexión) | C1, C2, C3, C4, C6 |
| **Progreso propio** | B5 (lista de verificación), E3 (descarga) |
| **Mixto** | C5 (L), C7 (L → B diferido), E2 (mapa) |

## 5. Decisiones abiertas
1. Si "Señales rápidas" (C3) es pantalla propia o panel desplegable sobre el mapa.
2. Nivel de detalle de la repetición de rodada en B6 (Fase 6).
3. Si la pestaña "Rutas" se mantiene como pestaña o pasa dentro de Rodadas.
4. Diseño visual final (logo, paleta y tipografía) pendiente de la marca.
