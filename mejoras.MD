# Mejoras de la plataforma Traccar para flotas de buses

## 1. Objetivo

Convertir Traccar en una plataforma multiempresa para administrar buses equipados con MDVR Streamax y, cuando corresponda, cámaras Hikvision.

La plataforma debe recibir y presentar:

- Posición GPS y recorrido.
- Estado de ignición del vehículo.
- Estado de puertas y entradas/salidas digitales.
- Conteo de pasajeros por puerta.
- Estado de cámaras y almacenamiento.
- Video en vivo.
- Descarga de video histórico asociada a eventos.
- Estado de conectividad y consumo de datos.
- Alarmas y telemetría adicional disponible en el MDVR.

La privacidad es un requisito principal: cada empresa solo puede consultar sus vehículos, posiciones, eventos, videos y configuraciones.

## 2. Principios de diseño

1. **La empresa se representa mediante un grupo Traccar.**
2. **Cada dispositivo pertenece a un único grupo operativo.**
3. **Cada usuario recibe acceso por grupo o por dispositivos explícitos.**
4. **El servidor nunca debe confiar en un `deviceId` enviado por el navegador para autorizar acceso.** Primero debe comprobar que el usuario tiene permiso sobre ese dispositivo.
5. **JT808 y N9M son canales complementarios, no fuentes intercambiables sin control.**
6. **Los contadores deben conservar el evento original y sus atributos de trazabilidad.**
7. **Las operaciones destructivas o de escritura sobre el MDVR requieren permisos elevados, confirmación y registro de auditoría.**
8. **Toda modificación de base de datos debe tener respaldo previo y procedimiento de recuperación.**

## 3. Arquitectura de comunicaciones

```text
MDVR Streamax
  |
  |-- JT808 sobre TCP/TLS 6556
  |      GPS continuo, ignición, estados generales
  |
  |-- N9M control TCP 21083
  |      conteo APC, keepalive, comandos y control de video
  |
  |-- N9M media TCP 21720
         video en vivo y reproducción histórica

Servidor Traccar
  |-- decoders JT808 y N9M
  |-- pipeline de posiciones y filtros
  |-- PostgreSQL
  |-- API REST autenticada
  |-- interfaz web
  |-- almacenamiento de colas y configuración externa
```

En producción, los puertos deben ser accesibles desde la red móvil del MDVR. `n9m.serverHost` debe ser una IP pública o un nombre DNS resoluble y alcanzable por el equipo. No debe ser una IP privada de la red local.

El despliegue vigente documentado es Docker Compose en la raíz del proyecto. Los manifiestos Kubernetes antiguos no incluyen N9M y no deben usarse sin actualizarlos.

## 4. Identidad del dispositivo

Un mismo MDVR puede identificarse de forma diferente en cada protocolo:

| Propósito | Campo |
|---|---|
| Identidad JT808 | `uniqueId` del dispositivo Traccar |
| Identidad N9M | Atributo `n9mSerial`, equivalente al DSNO |
| Modo de video y conteo | `mdvrMode=n9m` |
| Zona horaria | `decoder.timezone` |
| Canal predeterminado de video | `mdvrChannel` |

Para un MDVR N9M deben validarse al menos estos atributos:

```json
{
  "mdvrMode": "n9m",
  "n9mSerial": "DSNO_DEL_EQUIPO",
  "decoder.timezone": "America/Bogota",
  "mdvrChannel": 1
}
```

El DSNO debe coincidir exactamente, incluyendo ceros iniciales y mayúsculas. N9M resuelve el dispositivo mediante `n9mSerial`, no mediante el `uniqueId` JT808.

## 5. Telemetría GPS

### 5.1 Fuente recomendada

JT808 debe seguir siendo la fuente principal del GPS continuo cuando el equipo lo soporte. Esto permite:

- Posición frecuente.
- Ignición/ACC.
- Estado en línea.
- Historial de recorridos.
- Geocercas y reportes estándar de Traccar.

N9M debe utilizarse para los eventos de conteo y las funciones de video. Si un equipo funciona únicamente por N9M, el decoder debe heredar la última ubicación conocida o usar el GPS incluido en el evento de conteo.

### 5.2 Atributos esperados

Los atributos de posición deben conservar, cuando existan:

- `latitude`, `longitude`, `speed`, `course`, `altitude`.
- `ignition`.
- `motion`.
- `valid`.
- `streamax.source`.
- `streamax.status`.
- `streamax.raw` cuando el protocolo entregue payload crudo.

La zona horaria debe configurarse con `decoder.timezone`. La clave `jt808.timezone` no debe usarse como sustituto.

## 6. Estado de puertas

El estado físico de puertas puede llegar como entrada digital, alarma o campo propietario según firmware. Debe normalizarse a atributos consistentes:

```text
door.front.open
 door.rear.open
door.front.alarm
door.rear.alarm
```

Cuando el protocolo solo entregue un identificador de puerta durante un evento APC, se deben conservar ambos valores:

- `streamax.doorId`: identificador original recibido.
- `streamax.doorEffective`: puerta normalizada (`front` o `rear`).

No se debe sobrescribir el identificador original. Esto permite auditar errores de configuración del MDVR.

La correspondencia entre cámara, canal y puerta debe ser configurable por vehículo. No debe asumirse universalmente que el canal 1 siempre es la puerta delantera.

## 7. Ignición y estado del vehículo

JT808 debe alimentar como mínimo:

- Ignición encendida/apagada.
- Movimiento.
- Última comunicación.
- Posición válida.
- Velocidad.
- Estado de conexión.

N9M puede informar estados adicionales mediante mensajes de telemetría o `UPDATEIOSTATUSINFO`, pero este último debe considerarse provisional hasta confirmar el formato real del firmware.

La interfaz debe mostrar estados claros:

- En línea.
- Sin comunicación.
- Ignición encendida.
- Ignición apagada.
- GPS inválido.
- Sin señal o almacenamiento con falla.

## 8. Conteo de pasajeros

### 8.1 Fuente y prioridad

Para equipos configurados con `mdvrMode=n9m`:

- N9M es la fuente preferida del conteo.
- JT808 no debe publicar el mismo evento APC si N9M ya es la fuente activa.
- El filtro de transporte debe evitar duplicar el evento, pero no debe eliminar eventos distintos.

Para equipos que todavía operan por JT808:

- El evento `0x0B02` representa el delta físico del evento.
- El mensaje `0x0B19` es estadístico/referencial y no debe volver a sumar pasajeros.

### 8.2 Atributos normalizados

Cada evento debe conservar:

```json
{
  "streamax.status": "counting_event",
  "streamax.source": "n9m",
  "streamax.doorId": 1,
  "streamax.doorEffective": "front",
  "streamax.eventOn": 2,
  "streamax.eventOff": 1,
  "passengersOn": 2,
  "passengersOff": 1,
  "passengersOnFront": 2,
  "passengersOffFront": 1
}
```

El frontend debe sumar deltas, no contadores acumulados. La página general debe deduplicar retransmisiones usando `streamax.raw` cuando exista y, como respaldo, una clave compuesta por tiempo, puerta y fuente.

### 8.3 Filtros

El filtro de duplicados debe distinguir como mínimo:

- `passengersOn`.
- `passengersOff`.
- `streamax.doorId`.
- `streamax.raw`.

Dos eventos con igual hora y mismo conteo, pero de puertas diferentes, deben conservarse ambos. Los eventos retransmitidos idénticos deben almacenarse una sola vez o marcarse de forma trazable, pero nunca deben inflar el total mostrado.

## 9. Video N9M

### 9.1 Video en vivo

El flujo debe:

1. Confirmar que el dispositivo tiene un canal N9M activo.
2. Enviar `MEDIASTREAMMODEL/REQUESTALIVEVIDEO`.
3. Registrar el canal pendiente y el nombre de stream.
4. Recibir la conexión media inversa del MDVR.
5. Publicar el stream mediante el reproductor web.
6. Cerrar la sesión al salir de la página o después del tiempo máximo de seguridad.

La sesión debe tener un límite de tiempo para evitar que un MDVR quede ocupado indefinidamente.

### 9.2 Video histórico

El flujo debe:

1. Consultar segmentos reales con `STORM/QUERYFILELIST`.
2. Elegir el segmento que contiene el evento.
3. Enviar `MEDIASTREAMMODEL/REQUESTREMOTEPLAYBACK`.
4. Recibir el stream inverso por N9M.
5. Asociar el video con la hora, puerta y evento de conteo.
6. Cerrar la sesión y limpiar el estado pendiente.

La ventana de video debe incluir margen antes y después del evento. El servidor debe evitar descargar segmentos completos innecesariamente para controlar el consumo de la SIM.

### 9.3 Privacidad del video

Antes de enviar cualquier comando de video, la API debe comprobar:

- Que el usuario tiene acceso al dispositivo.
- Que el dispositivo pertenece a un grupo permitido.
- Que el canal solicitado pertenece al vehículo autorizado.
- Que el usuario tiene permiso para ver o descargar video.

Los nombres de stream, direcciones internas y comandos N9M no deben quedar expuestos al navegador salvo que sea estrictamente necesario.

### 9.4 Modelo de transferencia por red

El comportamiento deseado es:

| Red del MDVR | Uso permitido | Política |
|---|---|---|
| WiFi del depósito | Sincronización masiva de grabaciones | Descargar todo lo pendiente, sin duplicar |
| Red celular | Video bajo demanda | Descargar solo el clip solicitado |
| Red celular | Video en vivo | Permitir la sesión mientras el usuario la mantenga abierta |

El servidor no debe iniciar sincronizaciones masivas por celular. Una descarga de segmentos completos puede consumir el plan de datos y ocupar el único recurso de reproducción del MDVR.

### 9.5 Cómo detectar WiFi

N9M permite identificar la conexión TCP activa y algunos estados de comunicación, pero no se debe asumir que la dirección remota por sí sola identifica el tipo de red. El operador móvil puede usar NAT y el WiFi también puede salir por NAT.

La clasificación debe implementarse por niveles:

1. **Preferido:** el MDVR informa explícitamente el canal de comunicación activo o el tipo de red en un mensaje de estado N9M.
2. **Práctico:** clasificar la conexión por una lista de IP públicas o rangos de las redes WiFi de los depósitos. Esta lista debe ser configurable por empresa o por vehículo.
3. **Más robusto:** instalar un agente o colector dentro de la red WiFi del depósito. El agente detecta al MDVR por LAN y coordina la descarga sin depender de la SIM.
4. **Fallback:** si la red no puede clasificarse con seguridad, tratarla como celular y permitir únicamente demanda o video en vivo.

No se debe activar la sincronización masiva solo porque el MDVR esté conectado. La conexión debe tener estado `WIFI_CONFIRMED` antes de crear trabajos de descarga masiva.

Se recomienda registrar:

```text
networkType: WIFI_CONFIRMED | CELLULAR | UNKNOWN
networkEvidence: PUBLIC_IP | PRIVATE_IP | DEVICE_REPORT | LOCAL_AGENT
networkObservedAt
```

La evidencia debe registrarse para auditoría, pero no debe exponerse a usuarios que no tengan permiso de soporte.

### 9.6 Catálogo permanente de videos

Los archivos no deben conservarse únicamente en el directorio temporal usado por `MdvrClipResource`. Se necesita un catálogo separado de los objetos de video.

Metadatos mínimos:

| Campo | Uso |
|---|---|
| `id` | Identificador interno del video |
| `tenantId` / `groupId` | Empresa propietaria |
| `deviceId` | MDVR de origen |
| `vehicleId` | Vehículo asociado |
| `channel` | Canal o puerta |
| `segmentStart` | Inicio real de la grabación |
| `segmentEnd` | Fin real de la grabación |
| `eventId` | Evento de conteo relacionado, si existe |
| `objectKey` | Ubicación en el almacenamiento |
| `sha256` | Huella para detectar duplicados |
| `sizeBytes` | Tamaño del objeto |
| `status` | `PENDING`, `DOWNLOADING`, `READY`, `FAILED`, `EXPIRED` |
| `sourceNetwork` | WiFi o celular usado para descargar |
| `createdAt` | Fecha de descubrimiento |
| `storedAt` | Fecha de almacenamiento |
| `expiresAt` | Fecha de retención |

La clave idempotente recomendada es:

```text
deviceId + channel + segmentStart + segmentEnd + fileType + storage
```

Antes de crear un trabajo se debe consultar esa clave. Durante la descarga se debe escribir a un archivo temporal o a una clave provisional con sufijo `.part`. El objeto pasa a `READY` solo después de validar tamaño y checksum. Si dos procesos intentan descargar el mismo segmento, uno debe ganar mediante una restricción única o un bloqueo distribuido.

La huella `sha256` permite detectar el mismo archivo aunque el equipo lo anuncie más de una vez. No se debe usar únicamente la hora del evento, porque varios eventos pueden pertenecer al mismo segmento.

### 9.7 Flujo de sincronización WiFi

```text
Detector de red WIFI_CONFIRMED
  -> consulta STORM/QUERYFILELIST
  -> normaliza segmentos reales
  -> elimina segmentos ya READY o en progreso
  -> crea VideoSyncJob pendientes
  -> descarga uno por vez por MDVR
  -> convierte a MP4 si es necesario
  -> calcula checksum
  -> sube a almacenamiento permanente
  -> marca READY
```

Reglas operativas:

- Un solo trabajo de reproducción por MDVR, porque el equipo puede responder `TASK FULL`.
- Límite de trabajos simultáneos por red WiFi para no saturar el depósito.
- Reanudación después de reinicio del servidor.
- Reintentos con backoff y límite de intentos.
- No borrar el archivo `.part` de otro proceso activo.
- Pausar automáticamente cuando la conexión deje de estar confirmada como WiFi.
- No cambiar automáticamente a celular para completar una cola WiFi.
- Priorizar segmentos relacionados con eventos de conteo y después completar el histórico pendiente.

Para sincronizar "todo lo que alcance a enviar" se recomienda trabajar por ventanas de tiempo y guardar un cursor por dispositivo, canal y almacenamiento. El cursor evita consultar indefinidamente los mismos días, mientras que el catálogo idempotente protege frente a retransmisiones o reinicios.

### 9.8 Flujo celular

En celular solo se permiten dos tipos de trabajos:

1. **Demanda:** el usuario solicita un clip de un evento o intervalo concreto.
2. **En vivo:** el usuario abre una sesión temporal de video.

La API debe calcular el costo estimado antes de iniciar una descarga celular y aplicar límites de duración, tamaño y frecuencia por usuario y empresa. Si existe una copia `READY` en el almacenamiento permanente, se debe entregar esa copia sin volver a consultar el MDVR.

Si no existe una copia y el usuario tiene permiso, se crea un trabajo de prioridad alta. El resultado se almacena en el catálogo para que la siguiente solicitud no vuelva a consumir datos.

### 9.9 Opciones de almacenamiento

#### Opción A: almacenamiento de objetos S3 compatible, recomendada

Usar Amazon S3, Google Cloud Storage, Azure Blob Storage o MinIO compatible con S3.

Ventajas:

- Escala con el crecimiento de la flota.
- Permite URLs firmadas con expiración.
- No expone el bucket públicamente.
- Tiene políticas de retención y ciclo de vida.
- Separa almacenamiento de video y base de datos.
- Puede replicarse o respaldarse.

Clave sugerida:

```text
videos/{tenantId}/{vehicleId}/{yyyy}/{MM}/{dd}/ch-{channel}/{segmentStart}-{segmentEnd}.mp4
```

La web debe solicitar al backend una URL firmada de corta duración. El navegador nunca debe recibir credenciales del bucket.

#### Opción B: MinIO en el mismo entorno

Adecuado para una primera instalación o una nube privada. Debe usar un volumen persistente separado del volumen de PostgreSQL y contar con respaldo externo.

No se debe guardar video importante únicamente dentro del contenedor Docker ni en `/tmp`.

#### Opción C: NAS o servidor de archivos en el depósito

Puede utilizarse como almacenamiento temporal de alta velocidad durante la sincronización WiFi. El servidor central debe recibir después los metadatos y, si se requiere acceso remoto, una copia en almacenamiento de objetos.

No debe ser la única copia si el depósito no tiene respaldo.

#### Opción D: volumen local del servidor

Es válido solo para una prueba pequeña. Requiere controlar espacio, permisos, retención, respaldo y crecimiento. No es la opción recomendada para varias empresas.

### 9.10 Retención y acceso del cliente

La retención debe ser configurable por empresa y tipo de video, por ejemplo:

- Eventos de conteo: 90 días.
- Video solicitado manualmente: 30 días.
- Video histórico sincronizado: según contrato de la empresa.
- Video marcado como evidencia: retención indefinida o protegida contra borrado automático.

La eliminación debe ejecutarse mediante una tarea controlada que marque primero el registro como `EXPIRED` y elimine el objeto solo cuando no esté asociado a una evidencia o investigación.

El cliente debe ver únicamente:

- Videos de vehículos de sus grupos autorizados.
- Eventos y canales autorizados.
- Estado de disponibilidad (`pendiente`, `listo`, `fallido` o `expirado`).
- Fecha, duración y tamaño, sin rutas internas del almacenamiento.

## 10. Multiempresa y grupos

### 10.1 Modelo

```text
SuperAdmin
  |-- Empresa A / grupo A
  |     |-- buses y MDVR de A
  |     |-- usuarios de A
  |
  |-- Empresa B / grupo B
        |-- buses y MDVR de B
        |-- usuarios de B
```

Cada vehículo debe tener:

- Una empresa.
- Un grupo Traccar correspondiente.
- Uno o más dispositivos asociados.
- Una ficha operativa con placa, número interno y capacidad.

### 10.2 Roles

| Rol | Acceso esperado |
|---|---|
| SuperAdmin | Todas las empresas y configuración global |
| `admin_empresa` | Gestión completa de su empresa |
| `supervisor_global` | Consulta de todas las empresas, sin edición |
| `supervisor` | Consulta de su empresa |
| `propietario` | Solo dispositivos asignados explícitamente |
| `auditor` | Reportes autorizados, sin mapa ni configuración |

El permiso efectivo debe salir de la combinación de rol y relación Traccar usuario-grupo/dispositivo. El atributo `company` por sí solo no debe considerarse suficiente para autorizar posiciones o video.

### 10.3 Reglas de privacidad

- Las listas de dispositivos deben venir filtradas por autorización Traccar.
- Los reportes deben recibir solo IDs de dispositivos accesibles.
- Los endpoints custom deben repetir la comprobación de permisos.
- No se deben aceptar `company`, `groupId` o `deviceId` como autorización enviada por el cliente.
- Los exportes Excel/CSV deben incluir únicamente vehículos autorizados.
- Las descargas de video deben registrar usuario, vehículo, canal, fecha y resultado.
- Los logs no deben imprimir contraseñas, tokens ni URLs con credenciales.

### 10.4 Roles de empresa y propietarios de buses

Se deben distinguir dos relaciones diferentes:

| Actor | Alcance | Administración |
|---|---|---|
| Dueño o administrador de empresa | Todos los vehículos del grupo de su empresa | Puede crear usuarios de su empresa y asignarles vehículos |
| Dueño del bus / propietario | Uno o varios vehículos asignados explícitamente | Puede consultar sus vehículos, reportes, telemetría y video autorizado |
| Supervisor de empresa | Todos o un subconjunto definido por la empresa | Solo lectura, salvo permisos adicionales |
| SuperAdmin | Todas las empresas | Configuración global y soporte |

El administrador de una empresa, por ejemplo TRSC, debe poder:

- Crear usuarios pertenecientes únicamente a TRSC.
- Editar o desactivar esos usuarios.
- Restablecer su contraseña según las reglas de seguridad.
- Asignar uno o varios vehículos concretos.
- Retirar una asignación sin eliminar el vehículo.
- Elegir el rol de cada usuario dentro del límite permitido.
- Consultar una vista de usuarios, vehículos asignados y último acceso.

El propietario de un bus debe poder consultar la información del vehículo que le fue asignado. La asignación no debe convertirlo en miembro de toda la empresa ni darle acceso a los demás buses.

### 10.5 Modelo de asignaciones

La relación entre usuarios y vehículos debe ser muchos-a-muchos:

```text
Empresa TRSC
  |
  |-- Usuario administrador TRSC
  |-- Usuario propietario Bus 101
  |-- Usuario propietario Bus 102
  |
  |-- Bus 101 / Device 1
  |-- Bus 102 / Device 2
```

Traccar ya dispone de relaciones de permisos entre usuarios, grupos y dispositivos. La implementación debe usarlas como autorización efectiva y puede mantener una ficha de asignación funcional adicional con:

```json
{
  "userId": 25,
  "deviceId": 101,
  "company": "TRSC",
  "permissions": ["view_map", "view_reports", "view_video"],
  "assignedBy": 7,
  "assignedAt": "2026-09-12T18:00:00Z"
}
```

Reglas obligatorias:

- El usuario y el dispositivo deben pertenecer a la misma empresa.
- Un administrador de empresa no puede asignar dispositivos de otra empresa.
- El cliente no puede enviar `company` como mecanismo de autorización.
- Toda consulta de posiciones, eventos o video debe validar la relación vigente.
- Desactivar un usuario debe retirar su acceso sin borrar el historial del vehículo.
- Las asignaciones deben quedar auditadas.

### 10.6 Vistas recomendadas para el propietario del bus

El propietario no necesita la interfaz administrativa completa. Su página inicial debe ser un resumen del vehículo asignado:

```text
Vehículo / placa
  Estado: en línea o sin comunicación
  Ubicación actual
  Ignición
  Puertas
  Último conteo
  Cámaras disponibles
  Acciones: ver mapa, ver video, consultar eventos
```

Vistas recomendadas:

| Vista | Contenido |
|---|---|
| Resumen del vehículo | Estado actual, ubicación, ignición, puertas y última comunicación |
| Mapa y recorrido | Posición actual, historial y reproducción del recorrido |
| Conteo Streamax | Eventos, subidas, bajadas, puerta, hora y video asociado |
| Video | En vivo y clips históricos autorizados |
| Telemetría | Señal, almacenamiento, cámaras, temperatura, voltaje y alarmas disponibles |
| Incidencias | Eventos de puerta, pérdida de video, desconexión y fallas del MDVR |
| Perfil | Datos personales y cambio de contraseña |

El propietario no debe ver:

- Otros vehículos de la empresa.
- Configuración de grupos.
- Credenciales o atributos internos del MDVR.
- Configuración de forwarding.
- Estadísticas globales.
- Auditoría de otros usuarios.
- Opciones de configuración del servidor.

### 10.7 Menú de reportes basado en capacidades

El menú no debe estar definido únicamente por el rol. También debe construirse a partir de las capacidades de los dispositivos visibles para el usuario.

Ejemplo de capacidades:

```json
{
  "deviceId": 101,
  "capabilities": {
    "gps": true,
    "ignition": true,
    "streamaxCounting": true,
    "hikvisionCounting": false,
    "liveVideo": true,
    "historicalVideo": true,
    "doorTelemetry": true
  }
}
```

Reglas para las vistas:

- Mostrar **Streamax Eventos de Conteo** solo si el usuario tiene al menos un dispositivo con `streamaxCounting=true`.
- Mostrar **Hikvision Eventos de Conteo** solo si el usuario tiene al menos una cámara Hikvision autorizada.
- Si el usuario tiene ambos tipos, mostrar ambas vistas, pero filtrar cada selector por el tipo de dispositivo compatible.
- Si el usuario no tiene Hikvision, no mostrar el enlace Hikvision y bloquear también el acceso directo a la ruta.
- Si el usuario no tiene video autorizado, no mostrar botones de video y rechazar la API con `403`.
- Si un vehículo no tiene una capacidad concreta, la ficha debe ocultar esa tarjeta en vez de mostrar valores vacíos o engañosos.

La autorización debe aplicarse en dos capas:

1. **Frontend:** ocultar menús, tarjetas y acciones que no correspondan.
2. **Backend:** volver a comprobar empresa, dispositivo, tipo de equipo y permiso antes de entregar datos.

Ocultar un enlace en React no es una medida de seguridad suficiente.

### 10.8 Vista de administración de usuarios de empresa

La página del administrador de empresa debe incluir:

| Columna | Información |
|---|---|
| Usuario | Nombre y correo |
| Rol | Propietario, supervisor u otro rol permitido |
| Estado | Activo, bloqueado o pendiente |
| Vehículos | Cantidad y placas asignadas |
| Último acceso | Fecha y hora |
| Acciones | Editar, asignar vehículos, desactivar y restablecer acceso |

El flujo para agregar un propietario debe ser:

1. El administrador de TRSC selecciona **Usuarios de mi empresa**.
2. Crea el usuario y elige el rol `propietario`.
3. Selecciona uno o varios vehículos de TRSC.
4. Selecciona permisos opcionales: mapa, reportes, video en vivo, clips históricos y conteo.
5. Guarda la asignación.
6. El usuario recibe acceso únicamente a esos vehículos.

La selección de vehículos debe salir del backend ya filtrado por la empresa. Nunca se deben cargar todos los dispositivos para filtrar solo en el navegador.

### 10.9 Acceso compartido entre dueño de empresa y dueño de bus

El dueño de la empresa y el dueño del bus pueden consultar el mismo vehículo porque ambos tienen una relación autorizada con él:

```text
Administrador TRSC
  -> acceso por grupo TRSC

Propietario del Bus 101
  -> acceso explícito al Device 101
```

Esto no duplica posiciones ni crea dispositivos adicionales. Solo crea dos caminos de autorización sobre el mismo recurso.

Cuando el bus cambia de empresa:

- El administrador de la empresa anterior pierde el acceso por grupo.
- Los propietarios de la empresa anterior pierden sus asignaciones, salvo que el cambio sea autorizado explícitamente.
- El nuevo grupo recibe el vehículo.
- Debe registrarse el cambio en auditoría.

Cuando un propietario deja de administrar un bus, basta con eliminar su asignación. No se debe borrar el usuario ni el historial del dispositivo.

## 11. Configuración remota futura

La configuración remota del MDVR debe implementarse por fases.

### Fase 1: solo lectura

- Estado del MDVR.
- Estado de cámaras.
- Canales configurados.
- FPS y resolución.
- Estado de almacenamiento.
- Estado de comunicaciones.
- Configuración APC.

### Fase 2: cambios de bajo riesgo

- Nombre del vehículo.
- Parámetros de reporte no críticos.
- Configuración de canales de cámara.
- Horarios de grabación, con confirmación.

### Fase 3: cambios críticos

- IP, puerto y servidor.
- TLS.
- Credenciales.
- Borrado o formateo de almacenamiento.
- Reinicio del MDVR.

Los mensajes `DEVEMM/DISPATHERPROXYMSG` deben reconstruirse con documentación oficial, SDK o una captura histórica. No se debe aceptar un JSON arbitrario del navegador y reenviarlo directamente al MDVR.

Toda operación de escritura debe incluir:

- Usuario que la solicitó.
- Empresa.
- Dispositivo.
- Parámetro anterior y nuevo.
- Fecha y zona horaria.
- Respuesta del MDVR.
- Resultado y código de error.
- Confirmación explícita para operaciones críticas.

## 12. Observabilidad y diagnóstico

El sistema debe exponer métricas y registros para diferenciar fallas de red, protocolo y datos:

- Último `CONNECT` N9M por DSNO.
- Último `KEEPALIVE` N9M.
- Último `UPPSTATISTICS` recibido.
- Último mensaje JT808 recibido.
- Cantidad de eventos por fuente: `jt808` y `n9m`.
- Eventos descartados por duplicación.
- Eventos marcados como retransmitidos.
- Comandos enviados y respuestas recibidas.
- Sesiones de video activas y fallidas.
- Errores de `TASK FULL`.
- Estado de cola de reenvío externo.

La pantalla de diagnóstico debe ser visible solo para SuperAdmin y, opcionalmente, para personal de soporte autorizado. No debe mostrar credenciales ni payloads sensibles completos.

## 13. Base de datos y respaldos

Antes de cualquier migración, limpieza o corrección de posiciones:

1. Crear un dump PostgreSQL completo.
2. Verificar que el dump pueda abrirse con `pg_restore -l`.
3. Registrar fecha, tamaño y ubicación del respaldo.
4. Ejecutar cambios dentro de una transacción cuando sea posible.
5. Validar conteos antes y después.
6. Conservar el respaldo hasta completar la verificación funcional.

Las correcciones de contadores deben preferir una vista o proceso de reconstrucción auditable antes que modificar silenciosamente las posiciones originales.

## 14. Reenvío externo

El reenvío de eventos debe ejecutarse después de que el evento haya pasado los filtros y haya sido guardado correctamente.

Cada trabajo debe incluir:

- Empresa.
- Dispositivo.
- Placa.
- Puerta.
- Subidas y bajadas.
- Hora del evento.
- Coordenadas.
- Identificador de evento o payload crudo.

Debe existir una cola persistente con reintentos, límite de intentos y registro por empresa. Un usuario de una empresa no debe poder consultar, editar o eliminar la configuración de forwarding de otra empresa.

## 15. Orden recomendado de implementación

### Prioridad 1: estabilidad de datos

- Mantener JT808 para GPS e ignición.
- Confirmar recepción N9M de `UPPSTATISTICS`.
- Finalizar deduplicación por payload y puerta.
- Mostrar fuente del evento en reportes.
- Añadir métricas de eventos por protocolo.

### Prioridad 2: operación diaria

- Estado de puertas.
- Estado de cámaras y almacenamiento.
- Panel de salud del MDVR.
- Diagnóstico de conexión N9M.
- Permisos específicos para video.

### Prioridad 3: administración remota

- Capturar o conseguir especificación de `DISPATHERPROXYMSG`.
- Implementar consultas de solo lectura.
- Añadir auditoría y confirmaciones.
- Implementar escritura controlada.

### Prioridad 4: escalabilidad

- Índices y consultas por `deviceId`, `fixTime` y fuente.
- Retención configurable de payloads crudos.
- Métricas agregadas por empresa.
- Alertas de desconexión, almacenamiento lleno y cámaras sin señal.

## 16. Criterios de aceptación

La mejora se considera funcional cuando:

- Un usuario de Empresa A no puede ver dispositivos, posiciones, reportes ni videos de Empresa B.
- Un propietario solo ve los dispositivos asignados explícitamente.
- JT808 entrega GPS e ignición de forma continua.
- N9M entrega conteo sin duplicar los eventos JT808 equivalentes.
- Dos puertas con eventos simultáneos no pierden datos.
- Una retransmisión idéntica no infla los totales.
- La página muestra subidas, bajadas, puerta, fuente y hora del evento.
- El video en vivo funciona sin IP pública en la SIM del MDVR.
- La descarga histórica usa el segmento real de grabación.
- Los comandos de video y futuros comandos de configuración se auditan.
- Existe un respaldo verificable antes de cada cambio de datos.
- Los errores de conexión, autenticación o protocolo son visibles para soporte sin exponer secretos.

## 17. Oportunidades adicionales frente a plataformas similares

La comparación con plataformas de gestión de flotas y video telemático muestra que el valor no está solamente en mostrar un mapa. Las funciones más útiles combinan telemetría, seguridad, mantenimiento, operación y evidencia.

### 17.1 Prioridad alta: centro de incidentes con video

Samsara y otras plataformas de video telemático presentan un centro de incidentes que relaciona el evento con el video, la ubicación y el estado del vehículo. Esta función encaja directamente con el MDVR Streamax.

Debe permitir:

- Lista de incidentes por empresa y vehículo.
- Filtros por fecha, tipo, severidad, conductor, puerta y cámara.
- Video asociado antes y después del evento.
- Posición GPS en el momento del incidente.
- Estado: nuevo, revisando, confirmado, descartado o cerrado.
- Comentarios y responsables.
- Descarga de evidencia con checksum.
- Exportación controlada para aseguradora o autoridad.
- Retención protegida cuando el incidente sea evidencia.

Eventos iniciales:

- Exceso de velocidad.
- Frenado o aceleración brusca.
- Colisión o alarma del MDVR.
- Apertura de puerta fuera de zona u horario.
- Pérdida de video.
- Desconexión del equipo.
- Conteo anómalo.
- Vehículo fuera de ruta.

### 17.2 Prioridad alta: mantenimiento preventivo

Las plataformas de flota suelen incluir mantenimiento por kilometraje, tiempo y horas de motor. Para los buses se propone:

- Planes de mantenimiento por tipo de vehículo.
- Odómetro y horas de motor.
- Vencimientos por fecha o kilometraje.
- Órdenes de trabajo.
- Costos, repuestos y proveedor.
- Evidencias y facturas.
- Estado: programado, en proceso, completado o vencido.
- Alertas al administrador de la empresa.

El odómetro debe tener una fuente y calidad registradas. No se debe mezclar silenciosamente el odómetro GPS, el del MDVR y valores digitados manualmente.

### 17.3 Prioridad alta: alertas accionables

Traccar ya ofrece eventos y notificaciones; el producto puede agregar una capa operativa orientada a buses:

- Vehículo detenido con ignición encendida.
- Ignición encendida fuera de horario.
- Puerta abierta mientras el vehículo está en movimiento.
- Puerta abierta fuera de una parada autorizada.
- Pérdida de conexión JT808.
- Pérdida de conexión N9M.
- Almacenamiento del MDVR bajo o lleno.
- Cámara sin señal.
- Conteo sin GPS válido.
- Diferencia anormal entre entradas y salidas.
- Equipo conectado por celular cuando debería estar en WiFi.

Cada alerta debe tener severidad, destinatarios, horario de silencio, repetición limitada y estado de confirmación. Evitar alertas sin límite, porque terminan ignorándose.

### 17.4 Prioridad media: puntuación de seguridad del conductor

Se puede construir una puntuación por recorrido usando eventos ya disponibles:

- Excesos de velocidad.
- Frenadas bruscas.
- Aceleraciones bruscas.
- Curvas agresivas.
- Tiempo detenido con motor encendido.
- Conducción fuera de horario.
- Incidentes asociados a video.

El resultado debe ser una herramienta de prevención, no un castigo automático. Se recomienda mostrar la evidencia y permitir que el supervisor agregue observaciones antes de cerrar un caso.

### 17.5 Prioridad media: operación de rutas y cumplimiento

Para empresas de buses agrega valor comparar el recorrido real con el plan:

- Rutas y variantes.
- Paradas esperadas.
- Ventanas horarias.
- Llegada, salida y permanencia en parada.
- Desvíos.
- Vueltas incompletas.
- Tiempo de recorrido.
- Comparación entre buses y días.

La primera versión puede trabajar con geocercas y secuencias de paradas. No es necesario construir un sistema completo de despacho desde el inicio.

### 17.6 Prioridad media: panel operativo de empresa

El administrador de empresa debería tener un panel que responda rápidamente:

- Cuántos buses están en línea.
- Cuántos están en ruta.
- Cuántos tienen ignición encendida.
- Cuántos presentan fallas.
- Cuántos tienen cámaras sin señal.
- Qué videos o incidentes están pendientes.
- Qué mantenimientos vencen pronto.
- Qué equipos no han reportado en el tiempo esperado.

El panel debe respetar el grupo de empresa y no revelar estadísticas de otras compañías.

### 17.7 Prioridad media: aplicación móvil o vista móvil del propietario

El propietario del bus y el administrador no necesitan la misma aplicación. La vista móvil debe priorizar:

- Estado del bus.
- Ubicación.
- Video en vivo.
- Eventos recientes.
- Incidentes.
- Alertas críticas.
- Solicitud de clip histórico.

Las descargas grandes deben pasar a una cola y notificar cuando estén listas, en lugar de mantener abierta la pantalla durante toda la transferencia.

### 17.8 Prioridad media: API e integraciones

Una API documentada aumenta el valor del servicio y evita integraciones manuales por cliente:

- Vehículos autorizados.
- Posiciones actuales e históricas.
- Eventos y contadores.
- Estado de conexión.
- Incidentes.
- Estado de videos.
- Mantenimientos.
- Webhooks para eventos nuevos.

La API debe usar tokens por empresa, permisos por alcance y límites de frecuencia. Nunca debe entregar credenciales N9M ni rutas internas del almacenamiento.

### 17.9 Prioridad baja: indicadores de pasajeros

Con los contadores Streamax se pueden generar indicadores adicionales:

- Pasajeros por viaje.
- Pasajeros por parada o geocerca.
- Hora pico.
- Ocupación estimada frente a capacidad.
- Diferencia entre entradas y salidas.
- Comparación entre rutas y vehículos.

Estos indicadores requieren definir claramente qué significa un viaje, cómo se reinicia el acumulado y cómo se tratan eventos atrasados o retransmitidos.

### 17.10 Prioridad baja: inventario y costos de conectividad

Para una operación multiempresa conviene registrar:

- SIM instalada y operador.
- Consumo mensual.
- Último cambio de SIM.
- Plan contratado.
- Costo aproximado por vehículo.
- Uso WiFi frente a uso celular.
- Costo estimado de video descargado.

Esto permite demostrar el ahorro de la sincronización por WiFi y detectar equipos que consumen datos de forma anormal.

## 18. Matriz de priorización

| Función | Valor para el cliente | Dependencia del DVR | Esfuerzo | Prioridad |
|---|---:|---:|---:|---:|
| Centro de incidentes con video | Muy alto | Media | Medio | P1 |
| Alertas operativas | Muy alto | Baja/media | Medio | P1 |
| Mantenimiento preventivo | Alto | Baja | Medio | P1 |
| Panel operativo de empresa | Alto | Baja | Medio | P1 |
| API y webhooks | Alto | Baja | Medio | P1 |
| Puntuación de conducción | Alto | Media | Medio | P2 |
| Rutas y cumplimiento | Alto | Baja/media | Alto | P2 |
| App o vista móvil | Medio/alto | Baja | Medio | P2 |
| Indicadores de pasajeros | Alto para transporte | Alta en calidad del conteo | Medio | P2 |
| Inventario y costos SIM | Medio | Baja | Bajo | P2 |
| Configuración remota N9M | Alto para soporte | Alta | Alto | P3 |
| Analítica avanzada o IA | Variable | Alta | Alto | P3 |

## 19. Modelo comercial y de servicio

Estas funciones permiten ofrecer módulos sin obligar a todas las empresas a contratar todo:

| Módulo | Contenido |
|---|---|
| Seguimiento | GPS, mapa, recorridos, ignición y alertas básicas |
| Seguridad | Incidentes, video asociado y puntuación de conducción |
| Pasajeros | Conteo por puerta, reportes y ocupación estimada |
| Video | En vivo, clips bajo demanda y sincronización WiFi |
| Mantenimiento | Planes, órdenes, costos y vencimientos |
| Operación | Rutas, paradas, cumplimiento y panel de despacho |
| Integraciones | API, webhooks y reenvío externo |

Cada módulo debe respetar la misma empresa, grupos, usuarios y asignaciones de vehículos. Activar un módulo nunca debe ampliar por sí solo el alcance de datos de un usuario.

## 20. Orden recomendado de implementación revisado

1. Seguridad de permisos por grupo y asignación individual.
2. Menú y vistas dinámicas por capacidades del vehículo.
3. Centro de incidentes con video y almacenamiento permanente.
4. Alertas operativas de conexión, puertas, cámaras y conducción.
5. Panel operativo por empresa.
6. Mantenimiento preventivo.
7. API documentada y webhooks.
8. Rutas y cumplimiento.
9. Indicadores de pasajeros y ocupación.
10. Configuración remota N9M.
11. Analítica avanzada.

La razón de este orden es que primero protege los datos y resuelve los flujos diarios de mayor valor. La configuración remota y la analítica avanzada deben construirse después de estabilizar telemetría, video, permisos y auditoría.

## 21. Fuentes públicas consultadas

- [Traccar: documentación y permisos](https://www.traccar.org/documentation/)
- [Samsara: productos de telemática, video, mantenimiento y operaciones](https://www.samsara.com/products)
- [Geotab: soluciones de gestión de flotas](https://www.geotab.com/fleet-management-solutions/)
- [Samsara: industria de transporte de pasajeros](https://www.samsara.com/industries/passenger-transit)

Estas fuentes se usaron para identificar categorías de funciones. La implementación debe adaptarse a las capacidades reales del MDVR Streamax y a los permisos de cada empresa.

## 22. Referencias internas

- [USER_MANAGEMENT.md](USER_MANAGEMENT.md)
- [MANUAL_USUARIO.md](MANUAL_USUARIO.md)
- [GUIA_INTEGRACION_MDVR.md](GUIA_INTEGRACION_MDVR.md)
- [GUIA_DESPLIEGUE_NUBE.md](GUIA_DESPLIEGUE_NUBE.md)
- [STREAMAX_APC_DOCUMENTATION.md](STREAMAX_APC_DOCUMENTATION.md)
