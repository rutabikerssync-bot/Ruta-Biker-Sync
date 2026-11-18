# Centro de Comando, Administración, Soporte & Analítica (Backoffice)

**Versión 1.0 · Fecha: 2026-10-31**  
**Proyecto:** Ruta Biker Sync  
**Arquitectura:** Backend Fastify `/v1/admin` + Dashboard Web Dark Elite

---

## 1. Visión General y Propósito

El sistema de **Administración, Soporte y Analítica** de Ruta Biker Sync proporciona al equipo operativo, moderadores y soporte técnico una consola centralizada de alta fidelidad para:

1. **Gobernanza y Jerarquía de Clubes:** Supervisión de clubes por país, verificación de Mesas Directivas (Presidente, Vicepresidente, Sargento de Armas), apadrinamiento de Motorcycle Clubs (MC) y prórrogas de gracia para suscripciones.
2. **Helpdesk & Moderación Escalada (R13.5):** Atención de tickets de soporte para líderes y resolución vinculante de denuncias de convivencia cuando el reportado es el único líder de un club o ante conflictos interclubes.
3. **Analítica de Comportamiento (Privacy-First):** Métricas agregadas anónimas sobre el uso de la aplicación, adopción de la red mesh offline, efectividad del sensor de caídas SOS y progresión comunitaria (Novatos a Leyendas), **cumpliendo estrictamente con la regla de oro: NUNCA almacenar geolocalización continua ni audio en la nube**.
4. **Auditoría Forense de Seguridad SOS:** Trazabilidad inmutable de cada ocasión en que se consultó la ficha médica y contactos ICE durante una emergencia en carretera.

---

## 2. Acceso y Ejecución del Dashboard Web

El Backoffice puede ejecutarse de dos formas:

### A. Integrado con el Servidor Node.js (Recomendado para Producción)
El servidor Fastify sirve automáticamente el panel web en:
```bash
http://localhost:3000/admin
```
Los archivos estáticos se encuentran en `backend/public/`.

### B. Modo Standalone (Para Moderadores o Entornos Desacoplados)
La carpeta `admin-dashboard/` contiene la aplicación web estática autónoma, la cual puede abrirse directamente con cualquier navegador o servirse con:
```bash
cd admin-dashboard
npx serve .
```

---

## 3. Endpoints de la API Backend (`/v1/admin`)

Todas las rutas administrativas requieren autenticación mediante el header `x-admin-key` o Bearer Token.

| Método | Endpoint | Descripción |
| :--- | :--- | :--- |
| `GET` | `/v1/admin/analytics/overview` | KPIs en vivo (usuarios, clubes, km, finanzas, precisión SOS, mesh offline). |
| `GET` | `/v1/admin/groups` | Listado paginado de clubes con filtros por nombre, país, tier y estado. |
| `GET` | `/v1/admin/groups/:id` | Detalle completo de un club, miembros, líderes e historial de rodadas. |
| `PATCH` | `/v1/admin/groups/:id/status` | Suspender o reactivar un club (con registro en auditoría). |
| `POST` | `/v1/admin/groups/:id/extend-grace` | Otorgar prórroga de días de gracia a la suscripción del líder. |
| `GET` | `/v1/admin/users` | Directorio de pilotos con rango, XP, kilometraje y club actual. |
| `PATCH` | `/v1/admin/users/:id/status` | Suspender o reactivar una cuenta de usuario. |
| `GET` | `/v1/admin/support/reports` | Bandeja de denuncias de convivencia escaladas a soporte (R13.5). |
| `PATCH` | `/v1/admin/support/reports/:id` | Emitir resolución formal (advertencia, silenciar, suspensión). |
| `GET` | `/v1/admin/support/tickets` | Bandeja de tickets de asistencia técnica y facturación. |
| `POST` | `/v1/admin/support/tickets` | Crear ticket de soporte. |
| `GET` | `/v1/admin/support/tickets/:id` | Detalle del ticket y conversación completa. |
| `POST` | `/v1/admin/support/tickets/:id/messages` | Enviar respuesta oficial de soporte o cambiar estado del caso. |
| `GET` | `/v1/admin/audit/sos` | Bitácora de accesos forenses a fichas médicas de emergencia SOS. |
| `GET` | `/v1/admin/audit/logs` | Registro inmutable de acciones tomadas por administradores. |

---

## 4. Estructura de la Base de Datos (Migración 0005)

* **`support_tickets`**:
  * `id`: Identificador UUID.
  * `user_id`: Piloto que solicita asistencia.
  * `group_id`: Club involucrado (opcional).
  * `subject`: Asunto de la consulta.
  * `category`: `'billing'`, `'mesh_sync'`, `'club_dispute'`, `'account'`, `'safety_sos'`, `'other'`.
  * `priority`: `'low'`, `'medium'`, `'high'`, `'critical'`.
  * `status`: `'open'`, `'in_progress'`, `'waiting_user'`, `'resolved'`, `'closed'`.
* **`support_ticket_messages`**: Hilo de mensajes bidireccional entre el piloto y el agente de soporte (`is_admin`).
* **`admin_audit_logs`**: Registro inmutable de cada acción administrativa (suspensiones, prórrogas, resoluciones de moderación e IP de origen).

---

## 5. Diseño y Estética Visual (Dark Elite Backoffice)

El diseño del panel sigue la guía de estilo de **Ruta Biker Sync**:
* **Fondo Principal:** Asfalto Profundo (`#07090E`).
* **Tarjetas:** Cuero Oscuro Biselado (`#121724` y `#181F30`).
* **Detalles y Resaltados:** Latón Dorado Biker (`#E5B869` / `#C69234`).
* **Indicadores:** Esmeralda (`#10B981`) para nodos activos y pagos al día; Rojo Rubí (`#EF4444`) para emergencias SOS y suspensiones; Cian Eléctrico (`#06B6D4`) para métricas de convoy y telemetría mesh.
