# Nodiklab CCTV

> Plataforma de **CCTV con auditoría de pasajeros** para flotas de transporte público.
> Fork de [Traccar](https://www.traccar.org) v6.13.3 con soporte para **Streamax MDVR**
> (protocolos N9M + JT808) y cámaras **Hikvision**.

---

## 📚 Documentación — empieza aquí

Guías operativas, en el orden en que normalmente se necesitan al instalar/configurar una flota nueva:

| # | Documento | Para qué sirve |
|---|---|---|
| 1 | [GUIA_DESPLIEGUE_NUBE.md](GUIA_DESPLIEGUE_NUBE.md) | Levantar el servidor desde cero en una VM en la nube — todas las dependencias, todos los contenedores Docker |
| 2 | [RESPALDOS.md](RESPALDOS.md) | Respaldo y restauración de datos, cron diario y bucket externo. **Configurar al desplegar** |
| 3 | [GUIA_INTEGRACION_MDVR.md](GUIA_INTEGRACION_MDVR.md) | Conectar un MDVR Streamax al servidor (protocolos N9M + JT808) y darlo de alta en la plataforma |
| 4 | [GUIA_CAMARAS_HIKVISION_STREAMAX.md](GUIA_CAMARAS_HIKVISION_STREAMAX.md) | Configurar cámaras Hikvision y los canales del MDVR para que manden conteo y video a la plataforma |
| 5 | [MANUAL_USUARIO.md](MANUAL_USUARIO.md) | Uso de la plataforma: empresas/roles, alta de vehículos, referencia de atributos de dispositivo, páginas principales |
| 6 | [USER_MANAGEMENT.md](USER_MANAGEMENT.md) | Detalle fino de permisos por rol (qué ve/edita cada uno, menú por menú) |
| 7 | [PENDIENTES.md](PENDIENTES.md) | Trabajo acordado y no implementado, con su prioridad y motivo |
| 8 | [STREAMAX_APC_DOCUMENTATION.md](STREAMAX_APC_DOCUMENTATION.md) | Referencia técnica a nivel de protocolo/backend del conteo APC — para quien vaya a tocar el código, no para instalar |

Todo lo demás en este README es un resumen general del proyecto; para instalar o configurar algo puntual, usa la tabla de arriba.

---

## Estado del sistema

| Área | Estado |
|---|---|
| Conteo por N9M | Operativo, con deduplicación de retransmisiones y marcado de origen |
| GPS + ignición por JT808 | Operativo, sobre TCP con TLS |
| Video en vivo y por evento | Operativo sobre SIM celular, sin IP pública en el vehículo |
| Respaldos | Script probado de extremo a extremo; **falta activar cron y bucket al desplegar** |
| Pruebas automatizadas | 580 tests, incluidos 8 del decoder N9M y 3 del marcado de origen |
| Aislamiento entre empresas | Endpoints de conteo y video validan permiso sobre el dispositivo |

### Precisión del conteo — cómo se protege

Los MDVR **reenvían eventos ya entregados** cuando recuperan conexión. Sin control, eso infla los
totales: medido en campo, **+49%** (3.088 registrados contra 2.076 reales).

La plataforma lo maneja en dos capas:

1. **Huella del evento** (`streamax.raw`): guarda el payload exacto que mandó el equipo, lo que
   permite saber si dos tramas son la misma.
2. **Filtros** (`filter.duplicate` y `filter.duplicateStored`, activos en la configuración): un
   reenvío idéntico se descarta; un evento distinto **nunca** se pierde.

Cada evento guardado queda marcado en `streamax.ingest` con su origen:

| Valor | Significado | ¿Cuenta? |
|---|---|---|
| `live` | Llegó en tiempo real | Sí |
| `backfill` | Se perdió en vivo y el equipo lo reenvió al reconectar — **rellena el hueco** | Sí |
| `retransmitted` | Misma hora que otro evento, contenido distinto | Sí, marcado |
| *(descartado)* | Reenvío idéntico | No |

Visible en la columna **Origen** de Streamax Eventos de Conteo.

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
- Timezone configurable por instalación (`decoder.timezone=America/Bogota` — **no** `jt808.timezone`, esa clave no tiene efecto)
- **Deduplicación:** el MDVR retransmite el mismo evento dos veces; `streamax.raw` es la clave de deduplicación en todas las páginas de conteo

### 1.1 Protocolo N9M (conteo + video, además de JT808)

Además del conteo por JT808 (arriba), el fork soporta el protocolo propio N9M de Streamax para el mismo MDVR — canal separado para conteo de pasajeros (con GPS exacto del instante del evento) y video (vista en vivo + descarga histórica), corriendo en paralelo con JT808 (que sigue siendo la fuente de GPS continuo e ignición). Ver **[GUIA_INTEGRACION_MDVR.md](GUIA_INTEGRACION_MDVR.md)** para la configuración completa. Activación: atributo `mdvrMode = n9m` + `n9mSerial` en el dispositivo — con esto, la plataforma automáticamente evita contar el mismo evento dos veces por los dos canales.

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

#### Modo Celular / JT808+JT1078 (SIM / sin IP pública)
Cuando el vehículo está en red móvil y la SIM no es accesible desde internet:
- Envía comando JT808 `0x9202` al dispositivo a través de la conexión TCP existente
- El MDVR hace streaming de vuelta via JT1078 (device-initiated, NAT-friendly)
- Requiere atributo `mdvrMode = jt1078` en el dispositivo Traccar
- Convierte MPEG-TS → MP4 via ffmpeg `-c copy`

#### Modo N9M (recomendado para MDVR con ambos protocolos activos)
- Mismo mecanismo device-initiated, pero por el canal de control N9M en vez de JT808/JT1078
- Requiere atributo `mdvrMode = n9m` + `n9mSerial` en el dispositivo Traccar
- Ver [GUIA_INTEGRACION_MDVR.md](GUIA_INTEGRACION_MDVR.md) para la configuración completa (puertos, TLS de JT808, atributos)

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
| Supervisor Global | `supervisor_global` | Todas las empresas, solo lectura |
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

### 7. Identidad visual (Nodiklab)

- Nombre de plataforma: **CCTV-NODIKLAB v1.0.2** (definido en `OverrideTextFilter.java`; el
  administrador puede sobrescribirlo con los atributos `title`/`description` del servidor)
- Logo: `traccar-web/src/resources/images/nodiklab.png` — PNG con fondo transparente, usado en
  login y app. Se dimensiona **por ancho** (`LogoImage.jsx`): es apaisado (≈3:2) y con
  `height:100%` quedaba aplastado en el panel lateral vertical
- Paleta: `#5B8DB8` (primary), `#78909C` (secondary)
- Modo oscuro forzado: `bg #121212`, paper `#1E1E1E`
- Sidebar del login con gradiente azul oscuro

### 8. Infraestructura de despliegue

⚠️ **Hay dos rutas de despliegue en este repo, no las mezcles:**

- **Actual** (recomendada, con TLS de JT808 y N9M): `Dockerfile` + `docker-compose.yml` en la **raíz** del repo. Ver **[GUIA_DESPLIEGUE_NUBE.md](GUIA_DESPLIEGUE_NUBE.md)** para el paso a paso completo.
- **Anterior** (sin TLS, sin N9M, puerto JT808 21081 en vez de 6556), todo bajo `deploy-traccar/`:

  | Archivo | Descripción |
  |---|---|
  | `Dockerfile` | Build multietapa más viejo, sin keystore JT808 |
  | `docker-entrypoint.sh` | Genera `traccar.xml` desde variables de entorno (K8s) |
  | `docker-compose.local.yml` | Stack local viejo (Traccar + PostgreSQL) |
  | `update-local.ps1` / `setup-local.ps1` | Scripts de build/push/despliegue de la ruta vieja |
  | `deploy-local.md` / `deploy-gke.md` | Guías de la ruta vieja — **desactualizadas**, ver GUIA_DESPLIEGUE_NUBE.md en su lugar |
  | `k8s/` | Manifiestos Kubernetes de la ruta vieja (tampoco tienen TLS/N9M) |

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

## Compilar y desplegar

Ver **[GUIA_DESPLIEGUE_NUBE.md](GUIA_DESPLIEGUE_NUBE.md)** para el flujo completo y actual (build local → push a registro → VM en la nube), incluyendo requisitos previos, generación del keystore TLS, y creación de todos los contenedores Docker necesarios (`traccar_server` + `traccar-postgres`).

Resumen rápido para desarrollo local (usa el `Dockerfile`/`docker-compose.yml` de la **raíz**, no los de `deploy-traccar/`):

```powershell
git clone --recurse-submodules <url-del-repo>
cd traccar
.\gradlew.bat assemble -x test
docker volume create traccar_data
docker network create traccar-network
docker compose up -d --build
```

Interfaz web: `http://localhost:8082`

---

## Puertos

| Puerto | Protocolo | Uso |
|---|---|---|
| 8082 | TCP | Interfaz web / API REST |
| 6556 | TCP | JT808 — GPS + ignición (con TLS) |
| 8400 | TCP | JT1078 — vídeo streaming |
| 21083 | TCP | N9M — canal de control (conteo + comandos de video) |
| 21720 | TCP | N9M — canal de video |

---

## Atributos de dispositivo relevantes

Referencia completa (todos los atributos, incluyendo Hikvision/ISUP) en **[MANUAL_USUARIO.md](MANUAL_USUARIO.md)**, sección 5. Los más comunes:

| Atributo | Valores | Descripción |
|---|---|---|
| `mdvrMode` | `n9m` | Conteo + video por el protocolo N9M (recomendado) |
| `mdvrMode` | `jt1078` | Descarga de vídeo vía JT808/JT1078 (sin N9M) |
| `n9mSerial` | SERIAL/DSNO del equipo | Obligatorio junto con `mdvrMode=n9m` |
| `apc.forceDoor` | `front` / `rear` | Fuerza el mapeo de puerta si el automático falla |
| `decoder.timezone` | TZ ID | Timezone del equipo (ej. `America/Bogota`) |
| `mdvrIp` / `mdvrUser` / `mdvrPass` | IP / string / string | Solo para el modo antiguo de descarga directa por WiFi (sin `mdvrMode`) |
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
