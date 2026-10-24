# ICONS — Catálogo de iconos de Ruta Biker Sync

Versión 1.0 · 2026-10-17
Complementa SCREENS.md y SKELETONS.md. Base: Material Icons (ya incluido con `material-icons-extended` en el proyecto). Los marcados **PROPIO** no existen en Material y requieren diseño (SVG → VectorDrawable).

> **Verificar nombres:** los nombres de Material están escritos de memoria y algunos (`Sos`, `Emergency`, `TrafficCone`, `PersonRemove`, `Route`, `LocalPolice`) pueden no existir en la versión fijada de `material-icons-extended`. Al integrarlos, comprobar cada uno en el autocompletado o en fonts.google.com/icons; los que falten pasan a la lista de propios.

## 1. Reglas

1. **Un icono nunca va solo en funciones críticas:** SOS, hablar, señales y estados de pago llevan icono + texto (accesibilidad y guantes).
2. **Color no es la única señal:** cada estado tiene forma distinta (✓, ⚠, ✕), no solo verde/amarillo/rojo.
3. **Tamaños:** 24 dp normal · 32 dp listas con avatar · **48 dp modo rodada** · botones de acción de rodada con icono de 40 dp dentro de un área de ≥72 dp.
4. **Trazo uniforme** (outline 2 dp) para los propios, para que convivan con Material.
5. **Un solo significado por icono:** no reutilizar el de "líder" para "cargo" ni el de "rango" para "insignia".
6. Todos con `contentDescription` traducido (es/en/pt), salvo decorativos.

## 2. Navegación (barra inferior y superior)

| Uso | Icono Material | Notas |
|---|---|---|
| Pestaña Rodadas | `TwoWheeler` | Moto; si se ve poco claro, **PROPIO** casco o moto de perfil |
| Pestaña Grupo | `Groups` | |
| Pestaña Rutas | `Route` | |
| Pestaña Perfil | `AccountCircle` | Se sustituye por el avatar del usuario |
| Atrás | `ArrowBack` | |
| Cerrar | `Close` | |
| Menú / más | `MoreVert` | |
| Ajustes | `Settings` | |
| Ayuda | `HelpOutline` | |
| Buscar | `Search` | |
| Filtrar | `FilterList` | |
| Actualizar | `Refresh` | |

## 3. Rodada activa (modo guantes, 48 dp)

| Función | Icono | Notas |
|---|---|---|
| **Hablar (PTT)** | `Mic` | Estados: `Mic` en reposo, `GraphicEq` grabando, `MicOff` silenciado |
| Enviando clip | `Upload` o `Send` | Con anillo de progreso |
| Clip recibido / reproduciendo | `VolumeUp` | Con avatar de quien habla |
| **SOS** | `Sos` o `Emergency` | Rojo + texto "SOS" siempre |
| Cancelar cuenta regresiva | `Cancel` | Grande |
| Caída detectada | **PROPIO** moto caída | Alternativa: `CrisisAlert` |
| Señales rápidas (abrir) | `Campaign` | |
| Parada | `PauseCircle` | "Voy a parar" |
| Peligro adelante | `Warning` | |
| Policía | **PROPIO** (silueta de patrulla o escudo) | `LocalPolice` si se acepta Material |
| Bache / mal camino | **PROPIO** | Alternativa: `Report` |
| Gasolina | `LocalGasStation` | |
| Reagrupar / punto de encuentro | `PinDrop` o `Flag` | |
| Mi ubicación / centrar | `MyLocation` | |
| Seguir al líder | `NearMe` | |
| Orientación norte | `Explore` | |
| Capas del mapa | `Layers` | |
| Zoom | `Add` / `Remove` | |

## 4. Mesh y conectividad

| Estado | Icono | Notas |
|---|---|---|
| Mesh conectada | **PROPIO** nodos conectados | Alternativa: `Hub` |
| Mesh parcial (islas) | **PROPIO** nodos con enlace roto | Alternativa: `Hub` en amarillo con `⚠` |
| Solo / sin nadie en rango | `BluetoothSearching` | |
| Buscando riders | `BluetoothSearching` animado | Sin animación en ahorro de batería |
| Bluetooth apagado | `BluetoothDisabled` | |
| Rider fuera de la mesh | `PersonOff` | Banner de pérdida |
| Rider reconectado | `PersonAdd` o `CheckCircle` | |
| GPS sin fijar | `GpsNotFixed` | |
| GPS fijado | `GpsFixed` | |
| Sin internet | `CloudOff` | |
| Con internet | `Cloud` | Solo en pantallas de gestión |
| Sincronizando | `Sync` | |
| Datos desactualizados (stale) | `History` | |

## 5. Estado del rider en el mapa

| Estado | Marcador | Forma |
|---|---|---|
| En ruta | Círculo con avatar y borde del color del rider | |
| Desviado | Mismo círculo + insignia `⚠` | |
| Fuera de la mesh | Avatar en gris + `PersonOff` | Última posición conocida |
| Líder | Corona pequeña **PROPIO** sobre el avatar | |
| Cierre (sweep) | Bandera a cuadros **PROPIO** | `Flag` si no |
| Scout | `Visibility` | |
| SOS activo | Anillo rojo pulsante + `Sos` | Sin pulso con reducir animaciones |
| Yo | Punta de flecha con rumbo | `Navigation` |
| Punto de encuentro | `PinDrop` | |
| Peligro marcado | `Warning` | |

## 6. Grupos, estatus, cargos y roles

**Diferenciar siempre cargo, estatus y rango:** tres formas visuales distintas.

| Concepto | Icono | Forma |
|---|---|---|
| **Líder** (permiso de administración) | **PROPIO** corona o `Shield` con estrella | Escudo |
| Líder principal (Fundador) 🔥 | **PROPIO** llama sobre escudo | |
| Full member | `Verified` | Círculo con ✓ |
| Prospecto | `HourglassTop` o **PROPIO** medio parche | |
| Invitado | `PersonAdd` o `Badge` | |
| Presidente | `Star` | Cargos usan **estrellas y llaves** |
| Vicepresidente | `StarHalf` | |
| Capitán de Ruta | `Route` o `Explore` | |
| Sargento de Armas | `Security` | |
| Secretario | `EditNote` | |
| Tesorero | `AccountBalanceWallet` | |
| Road Marshal (rol de rodada) | `TrafficCone` | **PROPIO** si no se acepta |
| Invitar miembro | `PersonAdd` | |
| Retirar miembro | `PersonRemove` | |
| Transferir grupo | `SwapHoriz` | |
| Logo del grupo | Imagen del grupo (círculo) | Placeholder con iniciales |
| QR | `QrCode` / `QrCodeScanner` | |
| Votación | `HowToVote` | |
| Reportes | `Flag` o `Report` | |

## 7. Rangos e insignias

Los rangos son **medallas** (forma de escudo/medalla), las insignias de honor llevan el **logo del grupo**.

| Elemento | Icono | Notas |
|---|---|---|
| 🥉 Novato | **PROPIO** medalla bronce | Distinto de forma, no solo de color |
| 🥈 Rider | **PROPIO** medalla plata | |
| 🥇 Veterano | **PROPIO** medalla oro | |
| 💎 Élite | **PROPIO** gema | |
| 👑 Leyenda | **PROPIO** corona | Evitar confundir con el de líder: usar corona con laurel |
| 🔥 Fundador | **PROPIO** llama | Marca permanente, no rango |
| Insignia de logro (genérica) | `EmojiEvents` | Con icono interior según el logro |
| Insignia de honor | **PROPIO** sello con el logo del grupo | |
| Progreso de rango | Barra + medalla actual | |
| Rodada válida | `CheckCircle` | |

Insignias de logro sugeridas (cada una necesita arte propio): primera rodada, 500 km, rodada nocturna, rodar con 3 grupos, rodada sin cobertura, racha de meses.

## 8. Rodadas y rutas

| Función | Icono |
|---|---|
| Crear rodada | `AddCircle` / `Event` |
| Calendario | `CalendarMonth` |
| Hora | `Schedule` |
| Participantes | `People` |
| Iniciar rodada | `PlayArrow` |
| Finalizar rodada | `Stop` / `Flag` |
| Listo / confirmado | `CheckCircle` |
| Pendiente | `RadioButtonUnchecked` |
| En curso | `Autorenew` |
| Aviso | `ErrorOutline` |
| Trazar ruta | `Timeline` |
| Importar GPX | `FileUpload` |
| Exportar GPX | `FileDownload` |
| Waypoint | `Place` |
| Compartir ruta | `Share` |
| Descargar mapa | `Download` |
| Pausar descarga | `Pause` |
| Mapa disponible offline | `DownloadDone` |
| Borrar descarga | `Delete` |
| Distancia | `Straighten` |
| Desnivel | `Terrain` |
| Duración | `Timer` |
| Velocidad | `Speed` |
| Desvío de ruta | `AltRoute` |
| Resumen / estadísticas | `BarChart` |
| Repetición de rodada | `Replay` |
| Compartir tarjeta | `IosShare` / `Share` |

## 9. Seguridad, emergencia y salud

| Función | Icono | Notas |
|---|---|---|
| Ficha de emergencia | `MedicalInformation` | |
| Tipo de sangre | `Bloodtype` | |
| Alergias | `Warning` o **PROPIO** | Evitar iconos que sugieran enfermedades concretas |
| Medicamentos | `Medication` | |
| Contacto de emergencia | `ContactPhone` | |
| Seguro | `HealthAndSafety` | |
| Llamar | `Call` | |
| Paquete de emergencia bloqueado | `Lock` | |
| Paquete desbloqueado | `LockOpen` | Con temporizador de 60 min |
| Biometría | `Fingerprint` | |
| Aviso "no sustituye emergencias" | `Info` | Texto siempre visible |

## 10. Perfil, bóveda y documentos

| Función | Icono |
|---|---|
| Avatar / foto | `AddAPhoto` |
| Apodo | `Badge` |
| Moto | `TwoWheeler` |
| Color en el mapa | `Palette` |
| Editar | `Edit` |
| Bóveda | `Lock` / `FolderSpecial` |
| Licencia de conducir | `Badge` o `CreditCard` |
| Seguro del vehículo | `Policy` |
| Registro / titularidad | `Description` |
| Revisión técnica | `FactCheck` |
| Documento por vencer | `EventBusy` |
| Documento al día | `EventAvailable` |
| Añadir documento | `NoteAdd` |
| Mostrar rápido | `Visibility` |
| Alarma | `NotificationsActive` |

## 11. Cuenta, pagos y consentimientos

| Estado o función | Icono | Notas |
|---|---|---|
| Suscripción activa (🟢) | `CheckCircle` | Con texto "Al día" |
| Por vencer / gracia (🟡) | `Warning` | "Vence en N días" |
| Vencida (🔴) | `Cancel` | "Vencida" |
| Plan líder | **PROPIO** escudo-corona | Igual que el de líder |
| Cupo de miembro | `Person` / `ConfirmationNumber` | |
| Pago / Google Play | `CreditCard` / `Payments` | No usar el logotipo de Google |
| Renovar | `Autorenew` | |
| Consentimientos | `Gavel` o `PrivacyTip` | |
| Ubicación | `LocationOn` | |
| Notificaciones | `Notifications` / `NotificationsOff` | |
| Idioma | `Language` | |
| Tema oscuro / claro | `DarkMode` / `LightMode` | |
| Modo guantes | **PROPIO** guante | Alternativa: `PanTool` |
| Batería baja | `BatteryAlert` | Del rider en el mapa |
| Modo ahorro | `BatterySaver` | |
| Exportar mis datos | `FileDownload` | |
| Borrar cuenta | `DeleteForever` | Rojo, con confirmación |
| Cerrar sesión | `Logout` | |

## 12. Estados de pantalla (SKELETONS.md)

| Estado | Icono / ilustración |
|---|---|
| Vacío | Ilustración sencilla propia por contexto (sin grupo, sin rodadas, sin insignias, sin reportes) |
| Error de red | `WifiOff` / `CloudOff` |
| Error de servidor | `ErrorOutline` |
| Sin permiso | `Block` |
| Sin conexión | `CloudOff` |
| Reintentar | `Refresh` |
| Éxito | `CheckCircle` |
| Pendiente | `Schedule` |

## 13. Moderación

| Función | Icono |
|---|---|
| Reportar rider | `Flag` |
| Silenciar a un rider | `VolumeOff` |
| Expulsar de la rodada | `PersonRemove` |
| Advertencia | `Warning` |
| Decisión de líderes | `HowToVote` |

## 14. Iconos de aplicación y sistema

| Elemento | Especificación | Notas |
|---|---|---|
| **Icono del lanzador** | Adaptativo (primer plano + fondo) 108 dp, zona segura 66 dp | **PROPIO**; concepto: moto + nodos de mesh. Debe verse bien en miniatura |
| Icono monocromo (tema) | Android 13+ `monochrome` | Obligatorio para iconos temáticos |
| Icono de notificación | 24 dp, **blanco con transparencia**, sin color | Una variante de SOS y una de pérdida de cobertura |
| Notificación de servicio en primer plano | `TwoWheeler` o el nodo de mesh | Aviso permanente durante la rodada |
| Icono de la tienda | 512×512 | |
| Splash | Logo estático | Sin animación |
| Atribución de OSM | Texto "© OpenStreetMap" | Obligatorio, visible |

## 15. Resumen de iconos PROPIOS (para el diseñador)

| # | Icono | Prioridad |
|---|---|---|
| 1 | Icono de la app (lanzador + monocromo) | P0 |
| 2 | Nodos de mesh conectada / parcial | P0 |
| 3 | Corona de líder y escudo de líder | P0 |
| 4 | Llama de Fundador | P1 |
| 5 | Medallas de rango (bronce, plata, oro) + gema + corona de Leyenda | P1 |
| 6 | Moto caída (detección de caída) | P1 |
| 7 | Señales: policía, bache | P1 |
| 8 | Bandera a cuadros (cierre) | P1 |
| 9 | Sello de insignia de honor (con espacio para el logo del grupo) | P1 |
| 10 | Guante (modo guantes) | P2 |
| 11 | Insignias de logro (6 iniciales) | P2 |
| 12 | Ilustraciones de estados vacíos (≈6) | P2 |
| 13 | Icono de notificación SOS y pérdida de cobertura | P0 |

## 16. Implementación

- Material: `androidx.compose.material:material-icons-extended` (ya está en `libs.versions.toml`). **Aviso:** esa biblioteca es pesada; con R8 activado solo se incluyen los iconos usados, pero conviene confirmarlo en el tamaño del APK, o copiar solo los vectores necesarios al proyecto.
- Propios: SVG de 24×24 → VectorDrawable en `res/drawable/ic_*.xml`; nombrar `ic_<categoria>_<nombre>` (p. ej. `ic_mesh_connected`).
- Una sola clase `AppIcons` que mapea el concepto al recurso, para cambiar un icono en un solo lugar.
- Revisión de licencia: Material Icons es Apache 2.0. Si se compran o descargan iconos propios, verificar que la licencia permita uso comercial en app de pago y de código cerrado.

## 17. Decisiones abiertas
1. Estilo del sistema de iconos propios (trazo, esquinas, grosor) según la marca.
2. Si "líder" y "plan líder" usan el mismo icono (propuesta: sí, para reforzar la relación).
3. Si se acepta `LocalPolice` y `TrafficCone` de Material o se dibujan propios.
4. Si las medallas de rango se animan al subir de rango (desactivado con reducir animaciones).
