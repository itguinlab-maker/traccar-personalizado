# Traccar Personalizado

> Fork de [Traccar](https://www.traccar.org) v6.13.3 con extensiones para flotas de transporte público con dispositivos **Streamax MDVR** (protocolo JT808 + APC) y cámaras **Hikvision**.

---

## Personalizaciones implementadas

### 1. Soporte Streamax JT808 con conteo de pasajeros (APC)

- Decodificación del protocolo propietario de Streamax sobre JT808
- Eventos de conteo vía mensaje propietario `0x0B02` con desglose por puerta
- Atributos de posición generados:

| Atributo | Descripción |
|---|---|
| `passengersOn` / `passengersOff` | Totales de subida / bajada |
| `passengersOnFront` / `passengersOffFront` | Puerta delantera (doorId ≤ 1) |
| `passengersOnRear` / `passengersOffRear` | Puerta trasera (doorId ≥ 2) |
| `streamax.doorId` | ID de puerta (0–n) |
| `streamax.status` | `counting_event` cuando es evento de conteo |
| `streamax.raw` | Payload crudo del evento (clave de deduplicación) |

- Propagación automática entre posiciones consecutivas (`processing.copyAttributes`)
- Timezone configurable por instalación (`jt808.timezone=America/Bogota`)
- **Deduplicación:** el MDVR retransmite el mismo evento dos veces; `streamax.raw` es la clave de deduplicación en todas las páginas de conteo

### 2. Registro de Vehículos (`/api/vehiclerecords`)

Nueva entidad `VehicleRecord` con CRUD REST completo. Página en **Configuración → Vehículos**.

| Campo | Descripción |
|---|---|
| `plate` | Placa (obligatorio) |
| `company` | Empresa operadora |
| `internalNumber` | Número interno de flota |
| `vehicleType` | Bus / Buseta / Microbus / Van / Otro |
| `manufacturer` / `line` | Fabricante y línea/modelo |
| `year` | Año de fabricación |
| `engineNumber` / `chassisNumber` | Números de motor y chasis |
| `passengerCapacity` | Capacidad de pasajeros |
| `color` | Color |
| `matricula` | Número de matrícula |
| `insuranceExpiry` | Vencimiento del seguro (chip con color según días restantes) |
| `deviceId` | ID del dispositivo Traccar vinculado |
| `wifiIp` | IP WiFi del MDVR para descarga directa de vídeo |

Datos persistidos en `data/vehicle_records.json`.

### 3. Envío Externo de Conteo (`/api/externalforwarding`)

Módulo `ExternalForwardingManager` que agrupa dispositivos por empresa y reenvía eventos APC a APIs externas:

- Los grupos de forwarding se crean automáticamente **solo cuando un administrador** registra el primer vehículo de una empresa. Los usuarios no-admin solo actualizan grupos existentes.
- Cada grupo almacena: endpoint URL, usuario y contraseña (Basic Auth)
- Página de configuración en **Reportes → Envío Externo de Conteo** (`/reports/counting/external`) — visible solo para SuperAdmin y Admin de Empresa
- Datos persistidos en `data/forwarding_groups.json`

### 4. Descarga de vídeo MDVR (`/api/mdvrclip`)

Endpoint para descargar clips desde dispositivos Streamax. Soporta dos modos:

#### Modo WiFi (red local)
Cuando el vehículo está en WiFi y tiene `wifiIp` configurado en el Registro de Vehículos:
- Conecta directamente al MDVR HTTP API (`/devapi/v1/basic/...`)
- Flujo: autenticación → períodos → descarga H.264 → filtro NAL Streamax → ffmpeg → MP4
- Atributos del dispositivo: `mdvrIp` (default 192.168.1.11), `mdvrUser`, `mdvrPass`, `mdvrTimezone`

#### Modo Celular / JT808 (SIM TIGO / sin IP pública)
Cuando el vehículo está en red móvil y la SIM no es accesible desde internet:
- Envía comando JT808 `0x9202` al dispositivo a través de la conexión TCP existente
- El MDVR hace streaming de vuelta via JT1078 (device-initiated, NAT-friendly)
- Requiere atributo `mdvrMode = jt1078` en el dispositivo Traccar
- Convierte MPEG-TS → MP4 via ffmpeg `-c copy`

### 5. Páginas de Reportes

#### Conteo General de Pasajeros (`/reports/counting`)
- Descarga posiciones de **todos** los dispositivos en paralelo (5 workers concurrentes)
- Deduplicación por `streamax.raw`
- Tabla con desglose por puerta: Delantera (Sub./Baj.) | Trasera (Sub./Baj.) | Totales
- Exportación a `.xlsx` real (via `exceljs`)
- Barra de progreso (N/total vehículos cargados)
- Atajos: Hoy, Ayer, Esta semana, Este mes
- AbortController para cancelar peticiones al desmontar la página

#### Streamax Eventos de Conteo (`/reports/counting/events`)
- Selector de **un vehículo** con Autocomplete (búsqueda por nombre)
- Mapa integrado (tabla 40% izquierda + mapa MapLibre 60% derecha)
- Los eventos se pintan como círculos en el mapa; el seleccionado resalta en rojo
- Filtro por puerta: Todas / Delantera / Trasera
- Orden cronológico invertible (▼/▲)
- Deduplicación por `streamax.raw`
- Botón de descarga de vídeo por evento:
  - **Con WiFi:** tooltip muestra IP y ventana de 65 s; conecta directo al MDVR
  - **Sin WiFi:** tooltip "Descargar por red móvil (JT808/JT1078)"; usa conexión JT808 activa
  - Ventana de clip: `eventTime − 60 s` a `eventTime + 5 s` (65 s total)

#### Envío Externo de Conteo (`/reports/counting/external`)
- Lista de grupos de reenvío (uno por empresa)
- Editar endpoint URL, usuario y contraseña (Basic Auth) por grupo
- Los grupos se auto-gestionan desde el Registro de Vehículos

#### Hikvision Eventos de Conteo (`/reports/hikvision/counting`)
- Selector de cámara + atajos de rango (HOY, AYER, ÚLTIMAS 24H, ESTA SEMANA)
- Filtro Canal: Todos / Delantera / Trasera
- **Contadores correctos:** las cámaras Hikvision envían totales acumulados; la página calcula el delta entre eventos consecutivos por canal para mostrar el incremento real por evento
- Chips resumen: N eventos, ↑ Subidas, ↓ Bajadas, Neto ±N
- Descarga de clip de vídeo por evento (±30 s)

### 6. Gestión de usuarios y roles multiempresa

La plataforma soporta múltiples empresas con separación total de datos. Ver guía completa en [USER_MANAGEMENT.md](USER_MANAGEMENT.md).

**Roles disponibles** (configurados en `user.attributes`):

| Rol | `attributes.role` | Acceso |
|---|---|---|
| SuperAdmin | *(administrator = true)* | Todo |
| Admin de Empresa | `admin_empresa` | Solo su empresa — CRUD vehículos, reportes, forwarding |
| Supervisor | `supervisor` | Solo lectura — vehículos y reportes de su empresa |
| Propietario | `propietario` | Solo dispositivos explícitamente asignados |
| Auditor | `auditor` | Solo reportes, sin editar ni mapa |

**Principios de separación:**
- **Dispositivos:** Traccar filtra nativamente por permisos de grupo (`tc_user_group`)
- **Vehículos:** `GET /api/vehiclerecords` filtra por `user.attributes.company`
- **Forwarding:** `GET /api/externalforwarding` filtra por `user.attributes.company`
- **Grupos Traccar:** solo el admin puede crear grupos — los no-admin seleccionan de los grupos existentes a los que pertenecen

**Flujo de alta de una empresa:**
1. SuperAdmin crea el grupo Traccar (ej. `TRSC`)
2. SuperAdmin crea el usuario con Rol = `admin_empresa` y Empresa = `TRSC`
3. SuperAdmin vincula el usuario al grupo `TRSC` (Configuración → Usuarios → Conexiones)
4. Admin de empresa registra sus vehículos seleccionando el grupo `TRSC`
5. Los dispositivos quedan asignados al grupo automáticamente

### 7. Identidad visual (CountinG&KLAB)

- Nombre de plataforma: **CountinG&KLAB v1.0.2**
- Logo: `gnklab01.png` en pantalla de login y app
- Paleta: `#5B8DB8` (primary), `#78909C` (secondary)
- Modo oscuro forzado: `bg #121212`, paper `#1E1E1E`
- Sidebar del login con gradiente azul oscuro

### 8. Infraestructura de despliegue (`deploy-traccar/`)

| Archivo | Descripción |
|---|---|
| `Dockerfile` | Build multietapa (Node 20 → React, JRE 21 + ffmpeg) |
| `docker-entrypoint.sh` | Genera `traccar.xml` desde variables de entorno |
| `docker-compose.local.yml` | Stack local (Traccar + PostgreSQL) |
| `update-local.ps1` | Build + push GHCR + restart local |
| `setup-local.ps1` | Primera configuración (volúmenes externos) |
| `deploy-local.md` | Guía de despliegue Docker local |
| `deploy-gke.md` | Guía de despliegue en GKE |
| `k8s/` | Manifiestos Kubernetes |

**Registro de imágenes:** `ghcr.io/itguinlab-maker/traccar_personalizado:latest`

---

## Rutas del frontend

| Ruta | Página | Roles con acceso |
|---|---|---|
| `/reports/counting` | Conteo General de Pasajeros (APC) | Todos |
| `/reports/counting/events` | Streamax Eventos de Conteo | Todos |
| `/reports/counting/external` | Envío Externo de Conteo | SuperAdmin, Admin Empresa |
| `/reports/hikvision/counting` | Hikvision Eventos de Conteo | Todos |
| `/settings/vehicles` | Registro de Vehículos | SuperAdmin, Admin Empresa, Supervisor (solo ver) |
| `/settings/devices` | Dispositivos | SuperAdmin, Admin Empresa |
| `/settings/groups` | Grupos | SuperAdmin únicamente |

---

## Compilar y desplegar localmente

```powershell
# 1. Compilar el JAR
.\gradlew.bat assemble -x test

# 2. Construir imagen con --no-cache (obligatorio para forzar rebuild del frontend)
docker build --no-cache -f deploy-traccar/Dockerfile -t traccar-personalizado:local .

# 3. Reiniciar solo el contenedor Traccar (sin tocar BD)
docker compose -f deploy-traccar/docker-compose.local.yml up -d --no-deps --force-recreate traccar
```

O todo en uno con el script:
```powershell
.\deploy-traccar\update-local.ps1
```

Interfaz web: `http://localhost:8082`  
Ver guía completa en [deploy-traccar/deploy-local.md](deploy-traccar/deploy-local.md).

---

## Deploy a producción (GCloud VM)

```powershell
# 1. Build + push a GHCR
.\deploy-traccar\update-local.ps1   # incluye push si estás logueado en ghcr.io

# 2. En la VM de producción (34.61.186.60)
ssh USUARIO@34.61.186.60
docker pull ghcr.io/itguinlab-maker/traccar_personalizado:latest
docker compose -f deploy-traccar/docker-compose.local.yml up -d --no-deps --force-recreate traccar
```

---

## Puertos

| Puerto | Protocolo | Uso |
|---|---|---|
| 8082 | TCP | Interfaz web / API REST |
| 21081 | TCP/UDP | Protocolo JT808 (GPS Streamax) |
| 8400 | TCP | Protocolo JT1078 (vídeo streaming) |

---

## Atributos de dispositivo relevantes

| Atributo | Valores | Descripción |
|---|---|---|
| `mdvrMode` | `jt1078` | Activa descarga de vídeo via JT808/JT1078 (red móvil) |
| `mdvrIp` | IP | IP del MDVR en WiFi (default: 192.168.1.11) |
| `mdvrUser` | string | Usuario MDVR (default: admin) |
| `mdvrPass` | string | Contraseña MDVR (default: admin) |
| `mdvrTimezone` | TZ ID | Timezone del MDVR (default: GMT-5) |
| `mdvrChannel` | número | Canal por defecto para clips |

---

## Stack tecnológico

- **Backend:** Java 21, Jersey 4, Guice, Netty 4, PostgreSQL 16
- **Frontend:** React 19, Vite, Material UI v9, MapLibre GL
- **Vídeo:** ffmpeg (filtro NAL Streamax, transcodificación H.264 → MP4, MPEG-TS → MP4)
- **Contenedores:** Docker, docker-compose
- **Registro:** GitHub Container Registry (GHCR)

---

## Traccar original

Traccar es un sistema de rastreo GPS de código abierto con soporte para más de 200 protocolos. REST API: [traccar.org/traccar-api](https://www.traccar.org/traccar-api/).

## License

    Apache License, Version 2.0
