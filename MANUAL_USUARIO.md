# Manual de usuario — Nodiklab CCTV

Guía de referencia de toda la plataforma: empresas, roles, alta de vehículos, atributos de dispositivo y las páginas que vas a usar en el día a día. Complementa a [GUIA_INTEGRACION_MDVR.md](GUIA_INTEGRACION_MDVR.md) (que cubre solo la parte de conectar un MDVR Streamax) — este manual cubre la plataforma completa.

---

## 1. Conceptos básicos

- **Empresa** = un grupo de la plataforma. Cada vehículo y cada usuario pertenece a una empresa (o a ninguna, si eres administrador global).
- **Dispositivo** = el registro que representa un equipo físico (una cámara Hikvision, o el MDVR Streamax de un vehículo). Un vehículo puede tener uno o dos dispositivos, según el tipo de equipo (ver sección 3).
- **Vehículo** = la ficha que junta placa + empresa + el/los dispositivo(s) que le pertenecen.

## 2. Roles de usuario

Se configuran editando el usuario (`Configuración → Usuarios`), en la sección "Empresa y Rol" (solo visible/editable para administradores):

| Rol | Qué ve | Qué puede editar |
|---|---|---|
| Administrador (checkbox "Administrator") | Todo, todas las empresas | Todo |
| `admin_empresa` — Admin de Empresa | Su empresa | Su empresa |
| `supervisor_global` | Todas las empresas | Solo lectura |
| `supervisor` | Su empresa | Limitado |
| `propietario` | Su empresa | — |
| `auditor` | Su empresa | — |

Un usuario sin ningún rol asignado y sin empresa asociada no ve vehículos ni dispositivos por defecto.

## 3. Dar de alta un vehículo

Ruta: `Configuración → Vehículos → Alta guiada` (`/settings/vehicles/new`). Es un asistente de 3 pasos, solo para administradores.

### Paso 1 — Datos del vehículo

| Campo | Obligatorio | Notas |
|---|---|---|
| Placa | Sí | Se guarda en mayúsculas automáticamente |
| Empresa | Sí | Selecciona de la lista de empresas ya creadas |
| N° Interno | No | Número interno de flota, si tu operación lo usa |
| Tipo de vehículo | No | Bus / Buseta / Microbus / Van / Otro |
| Capacidad de pasajeros | No | Numérico |

### Paso 2 — Cámaras / dispositivos

Primero eliges el **tipo de equipamiento**:

- **Hikvision — cámara por puerta (2 dispositivos)**: una cámara independiente por cada puerta (delantera y trasera), cada una cuenta su propia puerta.
- **Streamax — MDVR centralizado (1 dispositivo)**: un solo equipo que maneja todas las cámaras/puertas del vehículo. Para esta opción, sigue además [GUIA_INTEGRACION_MDVR.md](GUIA_INTEGRACION_MDVR.md) para la configuración del equipo en sí.

Para cada cámara/equipo, eliges un modo:

| Modo | Qué hace |
|---|---|
| Crear nuevo | Da de alta un dispositivo nuevo con el identificador que escribas |
| Vincular existente | Asocia un dispositivo que ya existe en la plataforma |
| Vincular y actualizar | Igual que vincular, pero también actualiza sus atributos |

Campos por cámara:
- **Identificador (uniqueId / IMEI / serial)** — obligatorio si eliges "Crear nuevo". Para un equipo Streamax/JT808, es el identificador que el equipo manda al conectarse (ver sección "Cómo confirmarlo" en GUIA_INTEGRACION_MDVR.md si no lo sabes de antemano). Para una cámara Hikvision, puede ser cualquier identificador único que elijas.
- **Nombre del dispositivo** — opcional, si lo dejas vacío usa el identificador.
- **deviceID ISUP de la cámara** — opcional, solo si esa cámara va a usar el módulo experimental de video ISUP (ver sección 5.3 más abajo).

### Paso 3 — Confirmar

Resumen de todo lo anterior. Al confirmar, la plataforma en una sola operación: crea/vincula los dispositivos, los asigna al grupo (empresa) elegido, y registra el vehículo.

> Después de este asistente, todavía te falta agregar los atributos específicos del protocolo (sección 5) — el asistente solo deja listo `isupDeviceId` si lo llenaste; el resto (`mdvrMode`, `n9mSerial`, `hikIp`, etc.) se agrega por separado editando el dispositivo.

## 4. Editar atributos de un dispositivo

`Configuración → Dispositivos → (elige el dispositivo) → Atributos → Agregar atributo`.

Vas a ver un selector con una lista de atributos conocidos (sección 5.1) y, si el que necesitas no aparece ahí, la opción de escribir uno personalizado con el nombre exacto (sección 5.2) — **el nombre debe escribirse tal cual, sensible a mayúsculas/minúsculas**.

## 5. Referencia de atributos de dispositivo

### 5.1 Atributos con selector en la interfaz

Estos aparecen listados por nombre al agregar un atributo — no hace falta escribirlos a mano:

| Atributo | Para qué sirve |
|---|---|
| `command.sender` | Selecciona un mecanismo alterno de envío de comandos |
| `web.reportColor` | Color del vehículo en reportes/mapa |
| `devicePassword` | Contraseña propia del dispositivo (si el protocolo la usa) |
| `deviceImage` | Imagen personalizada del dispositivo |
| `processing.copyAttributes` | Copia atributos entre posiciones al procesar |
| `decoder.timezone` | Zona horaria para interpretar las marcas de tiempo del equipo (ej. `America/Bogota`) |
| `forward.url` | URL de reenvío de posiciones a un sistema externo |
| `isupDeviceId` | ISUP — deviceID de la cámara (ver 5.3) |
| `isupCamera1` | ISUP — cámara canal 1 (delantera) |
| `isupCamera2` | ISUP — cámara canal 2 (trasera) |
| `hikIp` | Hikvision — IP de la cámara (LAN/VPN) |
| `hikUser` | Hikvision — usuario |
| `hikPass` | Hikvision — contraseña |
| `hikTzOffset` | Hikvision — offset de zona horaria (ej. `-05:00`) |

### 5.2 Atributos que debes escribir a mano (Streamax / N9M / JT1078)

Estos son específicos de la integración Streamax y no tienen selector propio todavía — se agregan como atributo personalizado escribiendo el nombre exacto:

| Atributo | Valor | Cuándo usarlo |
|---|---|---|
| `mdvrMode` | `n9m` | Equipo Streamax que reporta conteo/video por N9M — ver GUIA_INTEGRACION_MDVR.md |
| `n9mSerial` | El SERIAL/DSNO del equipo | Junto con `mdvrMode=n9m`, obligatorio |
| `apc.forceDoor` | `front` o `rear` | Solo si el conteo automático asigna mal la puerta |
| `mdvrChannel` | Número de canal | Canal de video por defecto en la descarga de clips (frontend) |
| `mdvrMode` | `jt1078` | Modo alterno de descarga de video vía JT808/JT1078 (no usa N9M) |
| `mdvrIp` / `mdvrUser` / `mdvrPass` | IP / usuario / contraseña | Solo para el modo antiguo de descarga directa por WiFi local — **no aplica** si ya usas `mdvrMode=n9m` o `mdvrMode=jt1078` |

### 5.3 Sobre ISUP (video Hikvision en cámaras sin IP alcanzable)

`isupDeviceId`/`isupCamera1`/`isupCamera2` son para un módulo **experimental**: permite descargar/ver video de una cámara Hikvision que está detrás de una SIM restringida (sin IP pública alcanzable), aprovechando que la cámara abre la conexión hacia afuera por el protocolo ISUP. Requiere un componente adicional (`isup-gateway`) corriendo aparte del servidor principal — **no viene desplegado por defecto** en `docker-compose.yml`. Si no tienes ese componente corriendo, deja estos atributos vacíos: la plataforma simplemente no intentará usar ISUP y usará el video normal por RTSP/LAN si `hikIp` está configurado.

## 6. Páginas principales

| Página | Ruta | Menú | Quién la ve |
|---|---|---|---|
| Eventos de conteo Streamax | `/reports/counting/events` | Informes → Streamax Eventos de Conteo | Según permisos del dispositivo |
| Eventos de conteo Hikvision | `/reports/hikvision/counting` | Informes → Hikvision Eventos de Conteo | Según permisos del dispositivo |
| Vista en vivo | `/stream?deviceId=…` | Clic derecho sobre el vehículo en el mapa → "Live Video" | Requiere que el vehículo tenga una posición reciente por JT808 |
| Estado de SIM (consumo de datos) | `/settings/sim-status` | Configuración → Estado de SIM | Administrador, `admin_empresa`, `supervisor`, `supervisor_global` |
| Empresas | `/settings/companies` | Configuración → Empresas | Administrador |
| Alta guiada de vehículo | `/settings/vehicles/new` | Configuración → Vehículos → Alta guiada | Administrador |

### Origen de cada evento de conteo

En **Streamax Eventos de Conteo**, la columna **Origen** indica cómo llegó cada evento:

| Etiqueta | Significado |
|---|---|
| **En vivo** | Llegó en tiempo real, mientras el equipo estaba conectado |
| **Recuperado** | El equipo lo reenvió tras recuperar conexión: rellena un hueco que dejó una caída. **Cuenta igual que un evento en vivo** |
| **Reenvío** | Llegó con la misma hora que otro evento ya registrado, pero con contenido distinto |
| **—** | Evento anterior a esta funcionalidad |

Los equipos MDVR reenvían eventos cuando recuperan conexión. La plataforma **descarta los
reenvíos idénticos** (no inflan los totales) pero **conserva los eventos que se habían perdido**,
marcándolos como "Recuperado". Así un tramo sin cobertura no significa conteo perdido: cuando el
vehículo vuelve a tener señal, esos eventos se rellenan solos.

### Descarga de clips de video

Tanto en Eventos de Conteo Streamax como Hikvision, cada fila de evento tiene un botón para descargar el clip de ese momento — la plataforma decide sola qué protocolo/ruta usar según los atributos del dispositivo (N9M, JT1078, RTSP directo, o ISUP si está configurado). Mientras descarga, verás un anillo de progreso con el porcentaje estimado (basado en tiempo de espera, no en bytes — la mayor parte de la espera ocurre mientras el equipo transmite el video, antes de que exista ningún archivo que transferir).

## 7. Modelo de conteo — Hikvision vs. Streamax

| | Hikvision | Streamax |
|---|---|---|
| Dispositivos por vehículo | 2 (uno por puerta) | 1 (centralizado) |
| Cómo cuenta | Cada cámara cuenta su propia puerta | El MDVR cuenta ambas puertas por canal, ver `apc.forceDoor` |
| Cómo llegan los eventos | La cámara empuja HTTP al servidor, o el servidor consulta la cámara periódicamente (ambos mecanismos activos a la vez si `hikIp` está configurado) | El equipo manda el evento por el canal de control N9M |
| Video | RTSP directo (LAN/VPN) o ISUP (experimental, SIM restringida) | N9M (ver GUIA_INTEGRACION_MDVR.md) |

Ver [GUIA_CAMARAS_HIKVISION_STREAMAX.md](GUIA_CAMARAS_HIKVISION_STREAMAX.md) para la configuración específica de cada tipo de cámara.
