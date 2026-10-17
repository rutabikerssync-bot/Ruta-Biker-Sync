# BUSINESS_MODEL — Ruta Biker Sync

Versión 1.0 · 2026-10-16

## 1. Modelo
Suscripción mensual con descarga gratuita de la app. Cada persona paga su propia cuota; el líder controla su grupo.

| Plan | Precio | Quién | Incluye |
|---|---|---|---|
| **Líder** | $15/mes | Quien crea y administra grupos | Todas las funciones: grupos, miembros, cargos, estatus, rodadas, rutas, insignias, semáforo de pagos |
| **Miembro** (full o prospecto) | $2.50/mes | Un solo grupo | Mesh, mapa, voz, perfil, bóveda, SOS |
| **Invitado** | $2.50/mes **por cada grupo** | Cualquier usuario agregado con su consentimiento | Lo mismo que miembro, en rodadas del grupo que lo agregó |

- Hasta 3 líderes por grupo, cada uno paga sus $15.
- El líder no paga $2.50 en su propio grupo.
- El invitado paga aunque no ruede ese mes.
- Un usuario no puede ser invitado hasta que un líder lo agregue y él acepte.

## 2. Ejemplos de ingreso mensual por club (antes de comisiones e impuestos)

| Club | Cálculo | Total |
|---|---|---|
| 1 líder + 10 miembros | 15 + 10 × 2.50 | $40.00 |
| 2 líderes + 20 miembros | 30 + 20 × 2.50 | $80.00 |
| 3 líderes + 40 miembros + 5 invitados | 45 + 45 × 2.50 | $157.50 |

Un invitado en 3 grupos genera $7.50/mes.

## 3. Costos a considerar

| Concepto | Notas |
|---|---|
| Comisión de Google Play | Porcentaje sobre cada suscripción; verificar tarifa vigente para suscripciones |
| Impuestos (IVA/VAT) | Play los gestiona en muchos países; el precio neto varía |
| Registro de desarrollador Play | $25 pago único |
| Infraestructura | API, base de datos, KMS, notificaciones, almacenamiento de logos |
| Soporte | Atención de pagos, revisión de duplicados de nombre/logo |
| Asesoría legal | Privacidad, datos de salud, términos y marcas |
| Mapas | OSM sin costo; si se usa hosting de tiles propio, costo de ancho de banda |

Los datos y la voz no pasan por el servidor, lo que mantiene bajo el costo variable por usuario.

## 4. Estrategia de lanzamiento
1. **Piloto cerrado** con 3 a 5 clubes (pruebas de campo y de cobro).
2. **Fundadores:** ofrecer a los primeros clubes la insignia 🔥 Fundador y un mes de prueba si el costo de adquisición lo permite.
3. **Comunidad:** grupos de WhatsApp/Telegram de clubes, concentraciones y eventos.
4. **Embajadores:** líderes con influencia que reciben beneficio por traer clubes.
5. **Contenido compartible:** tarjeta de rodada y repetición animada para redes.

## 5. Puntos fuertes frente a la competencia
- Mesh que funciona sin cobertura.
- Estructura de club completa (cargos, estatus, rangos).
- Ficha de emergencia con acceso controlado.
- Privacidad: sin guardar ubicación ni voz en servidor.
- Precio bajo y claro para el club.

## 6. Riesgos del modelo
| Riesgo | Efecto | Respuesta |
|---|---|---|
| $2.50 de invitado mensual aunque ruede poco | Puede frenar al invitado ocasional | Evaluar pase por rodada como opción futura |
| Líder gratis como invitado en otros grupos | Posible evasión de pagos | Pendiente P1 en RULES.md |
| Pocos cupos por grupo | Ingreso bajo | Incentivos a clubes grandes |
| Dependencia de Google Play | Reglas y comisiones pueden cambiar | Revisión periódica de políticas |
| Cancelaciones masivas fuera de temporada | MRR inestable | Pausa de suscripción, planes anuales a futuro |

## 7. Opciones futuras (no incluidas)
- Plan anual con descuento.
- Pase por rodada para invitados ocasionales.
- Plan de club con tarifa por tramos.
- Patrocinios o alianzas con marcas de equipamiento (sin vender datos).

## 8. Pendientes
Ver RULES.md: P1 (líder invitado), P2 (líder de varios grupos), P3 (reembolsos).
