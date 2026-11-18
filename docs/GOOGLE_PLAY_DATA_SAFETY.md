# Declaración de Seguridad de los Datos y Permisos para Google Play Console
**Aplicación:** Ruta Biker Sync (com.rutabikersync.app)  
**Fecha:** Octubre 2026 · Versión 1.0  
**Referencia:** Google Play Developer Program Policies · Privacidad y Datos del Usuario

---

## 1. Justificación de Permisos Sensibles

### Permiso: `ACCESS_BACKGROUND_LOCATION` (Ubicación todo el tiempo)

#### A. Propósito de la función principal
Ruta Biker Sync es una plataforma de telemetría, seguridad vial y asistencia en carretera para caravanas y convoyes de motociclistas. Durante una rodada en grupo, los motociclistas guardan el teléfono celular en sus alforjas, bolsillos de la chaqueta o soportes bajo lluvia/vibración severa con la pantalla apagada. 

Para salvaguardar la vida de los pilotos en autopistas y zonas remotas sin señal celular, la aplicación debe:
1. **Calcular la distancia relativa entre motociclistas** y despachar alertas sonoras inmediatas vía casco Bluetooth si un hermano queda rezagado o se separa del grupo.
2. **Detectar caídas o paradas súbitas por colisión (SOS vial)** mediante acelerómetro y cambios de velocidad GPS, iniciando un conteo regresivo que alerta a los demás miembros del convoy a través de la red en malla Bluetooth Low Energy (BLE).
3. **Mantener la formación de la cápsula en el mapa fuera de línea** para los roles de Capitán de Ruta y Barredor (Sweep).

#### B. Implementación técnica y control del usuario
- **Servicio en Primer Plano (`RideService`):** La captura de ubicación en segundo plano se ejecuta **únicamente** mientras existe una rodada activa convocada por el usuario.
- **Notificación Persistente Obligatoria:** Mientras el servicio está activo, Android muestra una notificación fija que detalla el número de motociclistas conectados, la distancia al convoy y un botón de apagado de seguridad que requiere 3 segundos de pulsación continua.
- **Cero Rastreo en Reposo:** Cuando la rodada se detiene (por el usuario o el líder), el servicio se destruye por completo (`stopAll()`), liberando los listeners de ubicación y el `PARTIAL_WAKE_LOCK`. La aplicación nunca accede a la ubicación cuando no hay una rodada en curso.

---

## 2. Formulario de Seguridad de los Datos (Data Safety Section)

### A. Ubicación
| Campo | Valor | Justificación Técnica |
|---|---|---|
| **Ubicación aproximada** | Sí (Recopilada efímeramente) | Coordinación de convoy en carretera y cálculo de distancias relativas. |
| **Ubicación precisa** | Sí (Recopilada efímeramente) | Detección de colisión vial y coordenadas exactas para rescate en modo SOS. |
| **¿Se comparte con terceros?** | **No** | Las coordenadas solo se difunden vía BLE a los nodos autorizados por el manifiesto. |
| **¿Se almacena en servidores?** | **No** | Arquitectura local / P2P. El backend central no almacena historial ni puntos GPS. |
| **¿Es efímero?** | **Sí** | Los paquetes se procesan en memoria RAM del dispositivo y se descartan tras el reenvío. |
| **¿Se utiliza para publicidad?** | **No** | Cero fines comerciales o de rastreo publicitario. |

---

### B. Información Personal
| Campo | Valor | Propósito |
|---|---|---|
| **Nombre / Apodo de Rider** | Recopilado | Identificación del piloto en la pantalla de cabina y lista de miembros del club. |
| **Correo electrónico** | Recopilado | Autenticación de cuenta, facturación de líderes y recuperación de acceso. |
| **Identificadores de usuario** | Recopilado | Vinculación de suscripción mensual y credenciales criptográficas Ed25519. |

---

### C. Salud y Condición Física (Ficha Médica de Emergencia)
| Campo | Valor | Justificación Técnica |
|---|---|---|
| **Información médica y de salud** | Recopilado **únicamente en almacenamiento local seguro** | Tipo de sangre, alergias medicamentosas, póliza de seguro y contacto de rescate. |
| **Cifrado** | **Sí (AES-256-GCM)** | Cifrado en el dispositivo mediante llaves generadas en el Android Keystore y derivación PBKDF2. |
| **¿Se envía al backend?** | **No** | El servidor central nunca tiene acceso a los datos médicos ni a las llaves de descifrado. |
| **Apertura de emergencia** | Regulada | Solo se comparte mediante un paquete sellado criptográficamente si el usuario detona un SOS de accidente. |

---

### D. Audio (Notas de Voz e Intercomunicador Mesh)
| Campo | Valor | Justificación Técnica |
|---|---|---|
| **Grabaciones de voz** | Efímero en malla BLE | Comunicación Push-To-Talk entre pilotos para avisar baches, derrapes u obstáculos. |
| **¿Se almacena en la nube?** | **No** | Las notas de voz no se guardan en ningún servidor (RULES §13.1). |
| **¿Se conserva en disco?** | **No** | Los fragmentos de audio se reproducen al instante y se purgan de la cola de audio. |

---

### E. Fotos y Documentos
| Campo | Valor | Justificación Técnica |
|---|---|---|
| **Fotos del usuario** | Recopilado localmente | Avatar del perfil y foto de licencia/póliza en la Bóveda Segura. |
| **Almacenamiento** | Local cifrado | Las imágenes de documentos se cifran en `cacheDir/vault` con AES-256. |

---

## 3. Prácticas de Seguridad y Cumplimiento Normativo (RGPD / CCPA)

1. **Datos Cifrados en Tránsito:** Toda comunicación cliente-servidor se realiza bajo protocolos TLS 1.3 con certificados validados y verificación de integridad Play Integrity.
2. **Cifrado de Extremo a Extremo en Carretera:** Las comunicaciones en malla están protegidas con HMAC-SHA256 y claves de rodada dinámicas efímeras (`rideSecretKey`) de 256 bits generadas por sesión.
3. **Mecanismo de Solicitud de Eliminación:**
   - Todo usuario puede pulsar el botón **"Eliminar Cuenta y Datos (Derecho al Olvido)"** desde su perfil en la app.
   - La acción cancela inmediatamente todas las membresías, notifica a la mesa directiva del club, purga los registros de base de datos y anonimiza la telemetría histórica conforme al artículo 17 del RGPD.
4. **Portabilidad de Datos:** La app incluye la función **"Exportar mis Datos"**, que entrega al usuario un archivo JSON estructurado y firmado con todas sus credenciales, historial y membresías.
