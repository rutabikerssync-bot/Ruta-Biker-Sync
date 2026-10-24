# BUSINESS_MODEL — Ruta Biker Sync

Versión 1.1 · 2026-10-23

## 1. Modelo
Suscripción mensual con descarga gratuita de la app. Cada persona paga su propia cuota; el líder controla su grupo. La pertenencia a grupos es estricta: un usuario solo pertenece a un club (como líder, full member o prospecto).

| Plan | Precio | Quién | Incluye |
|---|---|---|---|
| **Líder** | $15/mes | Quien crea y administra su único grupo | Todas las funciones: grupo, miembros, cargos, estatus, rodadas, rutas, insignias, semáforo de pagos |
| **Miembro** (full o prospecto) | $2.50/mes | Un solo grupo oficial | Mesh, mapa, voz, perfil, bóveda, SOS |
| **Invitado a rodada** | Acceso temporal por rodada | Miembro de otro club o invitado puntual | Mesh, mapa, voz, SOS durante la rodada específica a la que fue invitado |

- **Un líder = un solo grupo:** Cada líder administra exclusivamente un grupo y paga **$15/mes**. No existe liderazgo multi-grupo ni tarifa combinada.
- Hasta 3 líderes por grupo, cada uno paga sus $15.
- El líder no paga $2.50 en su propio grupo.
- Ningún usuario puede tener membresía permanente en más de un club (`trg_memberships_no_cross_membership`).
- Los invitados no pagan mensualidades fijas entre múltiples grupos; su participación es temporal y puntual por rodada (`ride_guest_invitations`).

## 2. Ejemplos de ingreso mensual por club (antes de comisiones e impuestos)

| Club | Cálculo | Total |
|---|---|---|
| 1 líder + 10 miembros | 15 + 10 × 2.50 | $40.00 |
| 2 líderes + 20 miembros | 30 + 20 × 2.50 | $80.00 |
| 3 líderes + 45 miembros | 45 + 45 × 2.50 | $157.50 |

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
- Estructura de club completa (cargos de Mesa Directiva, estatus, rangos y jerarquía RC/MG/Gang/MC).
- Ficha de emergencia con acceso controlado.
- Privacidad: sin guardar ubicación ni voz en servidor.
- Precio bajo y claro para el club.

## 6. Riesgos del modelo
| Riesgo | Efecto | Respuesta |
|---|---|---|
| Invitados ocasionales sin suscripción | Fricción al unirse a salidas puntuales | Invitación temporal a rodada (`ride_guest_invitations`) y pase por rodada |
| Pocos cupos por grupo | Ingreso bajo | Incentivos y herramientas para clubes grandes |
| Dependencia de Google Play | Reglas y comisiones pueden cambiar | Revisión periódica de políticas y plan de contingencia |
| Cancelaciones masivas fuera de temporada | MRR inestable | Pausa de suscripción, planes anuales a futuro |

## 7. Opciones futuras (no incluidas)
- Plan anual con descuento.
- Pase por rodada para invitados ocasionales.
- Plan de club con tarifa por tramos.
- Patrocinios o alianzas con marcas de equipamiento (sin vender datos).

## 8. Decisiones y Pendientes
- **P1:** Cerrado (D43). Sin membresías `guest` cruzadas; participación como invitado temporal por rodada.
- **P2:** Cerrado (D42). Un líder = un solo grupo a $15/mes.
- **P3:** Pendiente. Acceso hasta fin del periodo pagado en caso de retiro voluntario.

