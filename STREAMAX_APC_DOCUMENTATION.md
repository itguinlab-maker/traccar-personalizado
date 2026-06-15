# Documentación Técnica: Integración Streamax APC + Hikvision en Traccar

Este documento describe la ingeniería inversa, implementación y personalización del sistema de conteo de pasajeros para dispositivos Streamax MDVR (JT808/APC) y cámaras Hikvision integrados en Traccar.

---

## 1. Hardware y Protocolos

| Dispositivo | Protocolo base | Notas |
|---|---|---|
| Streamax MDVR | JT808 extendido | Conteo APC vía mensaje propietario `0x0B02` |
| Cámaras Hikvision | API HTTP propia | Contadores acumulativos por canal |

**Conectividad de los MDVR:**
- SIM TIGO (red móvil): no tienen IP pública accesible desde internet
- WiFi en depósito: IP local accesible para descarga directa de vídeo
- La conexión JT808 TCP es **device-initiated** y permanece abierta → es el canal de control para descargas por celular

---

## 2. Protocolo Streamax APC (JT808)

### 2.1 Trama de Posición Estándar (0x0200)

El MDVR inyecta extensiones al final de la trama GPS:

| ID | Bytes | Atributo Traccar | Descripción |
|---|---|---|---|
| `0x41` | 2 | `streamax.odometerOn` | Acumulado global de entradas |
| `0x42` | 2 | `streamax.odometerOff` | Acumulado global de salidas |
| `0x30`/`0x31` | — | Ignorados | Estados de IA/señal, no conteo real |

**Prioridad de atributos:** el decoder prioriza `passengersOn` calculado por Traccar (via `CacheManager`) sobre `streamax.odometerOn` del hardware, para evitar que los GPS periódicos sobreescriban los incrementos reales del evento `0x0B02`.

### 2.2 Eventos de Conteo (0x0B02)

Reportes instantáneos generados al detectar movimiento en las puertas:

```
Estructura útil (12 bytes al final):
  [BCD Date (6 bytes)] [DoorID (1)] [On (2)] [Type (1)] [Off (2)]
```

| Campo | Valor | Interpretación |
|---|---|---|
| `DoorID` | 0 ó 1 | Puerta **Delantera** → atributos `Front` |
| `DoorID` | ≥ 2 | Puerta **Trasera** → atributos `Rear` |
| `On` | uint16 | Pasajeros subidos en este evento |
| `Off` | uint16 | Pasajeros bajados en este evento |

**Atributos generados:**

| Atributo Traccar | Descripción |
|---|---|
| `streamax.status = counting_event` | Marca el evento como conteo APC |
| `streamax.doorId` | ID de puerta raw |
| `streamax.raw` | Payload hex del evento (clave de deduplicación) |
| `passengersOn` / `passengersOff` | Totales del evento |
| `passengersOnFront` / `passengersOffFront` | Delantera |
| `passengersOnRear` / `passengersOffRear` | Trasera |

**Fix de flags:** se detectó comportamiento de "flags" (ej. 257 en lugar de 1); solucionado aislando el byte bajo con `getUnsignedByte`.

### 2.3 Deduplicación

El MDVR retransmite el mismo evento dos veces (doble ACK). Para evitar doble conteo:

```javascript
const key = a['streamax.raw'] || `${p.fixTime}|${a['streamax.doorId']}`;
if (seen.has(key)) return;
seen.add(key);
```

Aplicado en `GeneralCountingPage` y `CountingEventsPage`.

---

## 3. Backend Java

### 3.1 Decoder (`Jt808ProtocolDecoder.java`)

**Mejoras implementadas:**
1. **BCD Date Fix:** `buf.slice(startIndex, 6)` para evitar desalineación del puntero de lectura
2. **CacheManager lookup:** prioriza atributos calculados sobre los reportados en `0x0200`
3. **Retorno inteligente:** devuelve `Position` directa si la lista tiene 1 elemento, `List<Position>` si son varios

### 3.2 Configuración (`traccar.xml`)

```xml
<!-- Propagar atributos APC entre posiciones consecutivas -->
<entry key='processing.copyAttributes'>
    passengersOn passengersOff
    passengersOnFront passengersOffFront
    passengersOnRear passengersOffRear
</entry>
```

### 3.3 Descarga de Vídeo MDVR (`MdvrClipResource.java`)

**Endpoint:** `GET /api/mdvrclip?deviceId=N&channel=N&from=ISO&to=ISO&plate=X&door=X[&ip=X]`

#### Modo WiFi (parámetro `ip` presente o atributo `mdvrIp` del dispositivo)

```
1. Auth     → GET /devapi/v1/basic/key?autoLogin=1…
2. Períodos → GET /devapi/v1/basic/periodrecord?startDate=yy-MM-dd%20HH:mm:ss…
3. Download → POST /devapi/v1/basic/videodownload?periodId=hddId-rootId-segId
4. Filtro   → skipToSps() elimina header propietario (~672 bytes)
              streamFilteredNals() descarta fake-PPS blocks (pps_id > 255)
5. ffmpeg   → H.264 Annex B → MP4 (-c:v copy, -movflags +faststart)
```

**Atributos del dispositivo:**

| Atributo | Default | Descripción |
|---|---|---|
| `mdvrIp` | 192.168.1.11 | IP del MDVR en red WiFi |
| `mdvrUser` | admin | Usuario del MDVR |
| `mdvrPass` | admin | Contraseña del MDVR |
| `mdvrTimezone` | GMT-5 | Timezone del reloj interno del MDVR |
| `mdvrChannel` | 1 | Canal por defecto |

#### Modo Celular / JT808 (atributo `mdvrMode = jt1078`)

Cuando el MDVR está en red móvil sin IP pública:

```
1. Crear sesión en VideoClipManager (clipId + timeout)
2. Enviar Command JT808 TYPE_VIDEO_DOWNLOAD (0x9202) al dispositivo
   → KEY_INDEX = canal, KEY_START_TIME/END_TIME = ventana Unix
3. El MDVR inicia streaming JT1078 hacia el servidor (connection inversa)
4. VideoClipManager acumula frames H.264 hasta timeout o señal READY
5. ffmpeg MPEG-TS → MP4 (-c copy, -movflags +faststart)
6. Responde MP4 al cliente
```

**Activación:**
```
Dispositivo Traccar → Atributos → mdvrMode = jt1078
```

### 3.4 Hikvision (`HikvisionEventResource.java`)

**Endpoint:** `GET /api/hikvision/events?deviceId=N&from=ISO&to=ISO`

Las cámaras Hikvision devuelven **contadores acumulativos** (totales desde encendido o reset diario), no incrementos por evento. El frontend calcula los deltas.

---

## 4. Frontend — Páginas de Conteo

### 4.1 Conteo General de Pasajeros (`/reports/counting`)

**Componente:** `GeneralCountingPage.jsx`

- Consulta `/api/reports/route` para todos los dispositivos con filtro `streamax.status = counting_event`
- **Concurrencia:** 5 workers paralelos con cursor compartido (thread-safe en JS single-thread)
- **Deduplicación** por `streamax.raw`
- **Agregación por puerta:**

```javascript
row.frontIn  += Number(a.passengersOnFront)  || 0;
row.frontOut += Number(a.passengersOffFront) || 0;
row.rearIn   += Number(a.passengersOnRear)   || 0;
row.rearOut  += Number(a.passengersOffRear)  || 0;
```

- **Exportación Excel** real via `exceljs` + `file-saver`
- `LinearProgress` con N/total vehículos cargados
- `AbortController` cancela peticiones al desmontar

### 4.2 Streamax Eventos de Conteo (`/reports/counting/events`)

**Componente:** `CountingEventsPage.jsx`

- Selector único de vehículo (Autocomplete, búsqueda por nombre)
- Layout: tabla 40% | mapa MapLibre 60%
- Círculos en mapa (azul = normal, rojo = seleccionado), click sincroniza con tabla
- Filtros: rango de fechas + puerta (Delantera/Trasera/Todas) + orden ▼/▲
- Carga `VehicleRecord` por dispositivo para obtener `wifiIp`
- **Botón de vídeo:**
  - Con `wifiIp` → `&ip=wifiIp` → descarga WiFi directa; tooltip: "Descargar por WiFi · IP · 65 s"
  - Sin `wifiIp` → sin parámetro `ip` → backend usa JT808/JT1078 si `mdvrMode=jt1078`; tooltip: "Descargar por red móvil (JT808/JT1078)"
  - **El botón siempre está habilitado** (sin restricción de WiFi requerida)
- Ventana de clip: `eventTime − 60 000 ms` a `eventTime + 5 000 ms`

### 4.3 Envío Externo de Conteo (`/reports/counting/external`)

**Componente:** `ExternalForwardingPage.jsx`  
**Acceso:** SuperAdmin y Admin de Empresa únicamente

- Lista grupos via `GET /api/externalforwarding` (filtrado por empresa del usuario si no es admin)
- Editar configuración via `PUT /api/externalforwarding/{id}` (endpoint, usuario, contraseña)
- Los grupos de forwarding se crean automáticamente **solo cuando el admin** registra el primer vehículo de una empresa; un no-admin solo actualiza grupos existentes

### 4.4 Hikvision Eventos de Conteo (`/reports/hikvision/counting`)

**Componente:** `HikvisionCountingPage.jsx`

**Fix de contadores:** las cámaras envían acumulados. El cálculo correcto:

```javascript
// Ordenar por canal + tiempo ascendente
// Para cada evento: delta = max(0, actual − anterior del mismo canal)
// Primer evento de cada canal: delta = 0 (estado previo desconocido)
// Suma de deltas = max − min = conteo real del período
```

Esto convierte, por ejemplo, la secuencia `19, 20, 21, 22...` en deltas `0, 1, 1, 1...`

### 4.5 Registro de Vehículos (`/settings/vehicles`)

**Componente:** `VehicleRecordsPage.jsx`  
**Acceso:** SuperAdmin (CRUD completo), Admin Empresa (CRUD de su empresa), Supervisor (solo lectura)

- CRUD completo de vehículos con control por rol
- **Selector de Grupo/Empresa:** desplegable que carga `GET /api/groups` — Traccar filtra automáticamente mostrando solo los grupos a los que el usuario pertenece. El admin ve todos; un admin_empresa ve solo el suyo.
- Campos: placa, matrícula, grupo/empresa, número interno, tipo, fabricante/línea, año, motor/chasis, capacidad, color, vencimiento seguro, dispositivo Traccar, IP WiFi MDVR
- Chip de vencimiento de seguro con color (verde/amarillo/rojo según días restantes)
- Diálogo de descarga de vídeo directo por vehículo (selección de rango y canal)
- Al guardar, asigna el dispositivo al grupo Traccar y sincroniza el grupo de forwarding externo (solo crea el grupo de forwarding si es admin)

---

## 5. Configuración de Rutas

```
/reports/counting            → GeneralCountingPage
/reports/counting/events     → CountingEventsPage (Streamax)
/reports/counting/external   → ExternalForwardingPage
/reports/hikvision/counting  → HikvisionCountingPage
/settings/vehicles           → VehicleRecordsPage
```

---

## 6. Modos de Descarga de Vídeo — Resumen

| Situación | Configuración | Backend |
|---|---|---|
| Vehículo en WiFi depósito | `wifiIp` en VehicleRecord | HTTP directo al MDVR → filtro NAL → ffmpeg |
| Vehículo en red TIGO (SIM) | `mdvrMode = jt1078` en dispositivo | JT808 0x9202 → JT1078 stream → ffmpeg |
| Sin ninguna config | — | Intenta `mdvrIp` 192.168.1.11 (fallará si no accesible) |

---

## 7. Pruebas

```bash
# Compilar y ejecutar tests del decoder JT808
.\gradlew.bat clean test --tests Jt808ProtocolDecoderTest
```

Tests en `Jt808ProtocolDecoderTest.java` validan:
- Parsing correcto de `0x0B02` con distintos `doorId`
- Deduplicación de eventos
- Atributos `passengersOnFront`, `passengersOffFront`, `passengersOnRear`, `passengersOffRear`

---

## 8. Roles y acceso multiempresa

Ver documentación completa en [USER_MANAGEMENT.md](USER_MANAGEMENT.md).

Resumen de filtrado en los endpoints de conteo:

| Endpoint | Admin | No-admin con empresa |
|---|---|---|
| `GET /api/vehiclerecords` | Todos los registros | Solo registros de su empresa |
| `GET /api/externalforwarding` | Todos los grupos | Solo grupo de su empresa |
| `GET /api/groups` | Todos los grupos | Solo grupos vinculados al usuario (Traccar nativo) |
| `GET /api/reports/route` | Todos los dispositivos | Solo dispositivos de su grupo (Traccar nativo) |

---

*Última actualización: Junio 2026*
