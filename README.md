# Traccar Personalizado

> Fork de [Traccar](https://www.traccar.org) v6.13.3 con extensiones para flotas de transporte público con dispositivos **Streamax MDVR** (protocolo JT808 + APC de pasajeros).

---

## Personalizaciones implementadas

### 1. Soporte Streamax JT808 con conteo de pasajeros (APC)

- Decodificación del protocolo propietario de Streamax sobre JT808
- Atributos de posición: `passengersOn`, `passengersOff`, `passengersOnFront`, `passengersOffFront`, `passengersOnRear`, `passengersOffRear`, `streamax.doorId`, `streamax.status`
- Propagación automática de atributos entre posiciones consecutivas (`processing.copyAttributes`)
- Timezone configurable por instalación (`jt808.timezone=America/Bogota`)
- Soporte JT1078 para transmisión de vídeo (puerto 8400)

### 2. Registro de Vehículos (`/api/vehiclerecords`)

Nueva entidad `VehicleRecord` con CRUD REST completo:

| Campo | Descripción |
|---|---|
| `plate` | Placa del vehículo (obligatorio) |
| `company` | Empresa operadora |
| `internalNumber` | Número interno de flota |
| `vehicleType` | Bus / Buseta / Microbus / Van / Otro |
| `manufacturer` / `line` | Fabricante y línea/modelo |
| `year` | Año de fabricación |
| `engineNumber` | Número de motor |
| `chassisNumber` | Número de chasis |
| `passengerCapacity` | Capacidad de pasajeros |
| `color` | Color del vehículo |
| `matricula` | Número de matrícula |
| `insuranceExpiry` | Vencimiento del seguro (ISO date) |
| `deviceId` | ID del dispositivo Traccar vinculado |
| `wifiIp` | IP WiFi del MDVR para descarga de vídeos |

Datos persistidos en `data/vehicle_records.json`.

**Importación masiva desde Excel** (`.xlsx`, `.xls`, `.csv`):
- Mapeo flexible de cabeceras (insensible a tildes y mayúsculas)
- Campos obligatorios en el Excel: PLACA, N° Motor, N° Chasis, Vencimiento Seguro
- Diálogo de vista previa con validación fila por fila antes de importar
- Barra de progreso durante la importación masiva

### 3. Envío Externo de Conteo (`/api/externalforwarding`)

Módulo `ExternalForwardingManager` que agrupa dispositivos por empresa y reenvía eventos de conteo de pasajeros a APIs externas:

- Los grupos se crean y sincronizan automáticamente desde el Registro de Vehículos
- Cada grupo almacena: endpoint URL, usuario y contraseña (Basic Auth)
- Página de configuración en **Reportes → Envío Externo de Conteo**
- Datos persistidos en `data/forwarding_groups.json`

### 4. Descarga de vídeo MDVR (`/api/mdvrclip`)

Endpoint para descargar clips de vídeo directamente desde dispositivos Streamax MDVR:

- Flujo completo: autenticación → consulta de período → descarga H.264 → filtro NAL Streamax → transcodificación ffmpeg → respuesta MP4
- Atributos del dispositivo: `mdvrIp`, `mdvrUser`, `mdvrPass`, `mdvrTimezone`
- Parámetro `ip` de override: permite usar la IP WiFi del vehículo para descargas por red local (sin consumir datos móviles)

### 5. Página Eventos de Conteo (`Reportes → Eventos de Conteo`)

- Tabla + mapa de eventos de subida/bajada de pasajeros
- Filtros por rango de fecha, puerta (delantera/trasera) y orden cronológico
- Botón de descarga de vídeo por evento:
  - Usa la IP WiFi del vehículo (`wifiIp`) configurada en el Registro de Vehículos
  - Si no hay IP configurada, muestra el botón en naranja con tooltip explicativo
  - Ventana de clip: 60 s antes del evento + 5 s después (65 s total)

### 6. Infraestructura de despliegue (`deploy-traccar/`)

| Archivo | Descripción |
|---|---|
| `Dockerfile` | Build multietapa de producción (Node 20 + JRE 21 + ffmpeg) |
| `docker-entrypoint.sh` | Genera `traccar.xml` desde variables de entorno (`envsubst`) |
| `docker-compose.local.yml` | Stack completo para desarrollo local |
| `k8s/00-namespace.yaml` | Namespace `traccar` |
| `k8s/01-secrets.yaml` | Credenciales de base de datos |
| `k8s/02-configmap.yaml` | Template de `traccar.xml` |
| `k8s/03-06-*-pvc.yaml` | PVCs para PostgreSQL (20 GB) y datos Traccar (10 GB) |
| `k8s/04-05-postgres-*.yaml` | Deployment + Service de PostgreSQL 16 |
| `k8s/07-08-traccar-*.yaml` | Deployment + Service web de Traccar |
| `k8s/09-*-protocols-service.yaml` | LoadBalancer público para JT808 (TCP/UDP 21081) y JT1078 (8400) |
| `k8s/10-cert-issuer.yaml` | cert-manager ClusterIssuers (Let's Encrypt staging + prod) |
| `k8s/11-ingress.yaml` | nginx Ingress con TLS, WebSocket y redirect HTTPS |
| `deploy-local.md` | Guía de despliegue en Docker local |
| `deploy-gke.md` | Guía de despliegue en GKE (Google Kubernetes Engine) |

---

## Compilar y desplegar localmente

```bash
# 1. Compilar el JAR
.\gradlew.bat assemble -x test          # Windows
./gradlew assemble -x test              # Linux/WSL

# 2. Levantar el stack (Traccar + PostgreSQL)
docker compose -f deploy-traccar/docker-compose.local.yml up -d

# Interfaz web: http://localhost:8082
```

Ver guía completa en [deploy-traccar/deploy-local.md](deploy-traccar/deploy-local.md).
Ver guía de producción en [deploy-traccar/deploy-gke.md](deploy-traccar/deploy-gke.md).

---

## Puertos

| Puerto | Protocolo | Uso |
|---|---|---|
| 8082 | TCP | Interfaz web / API REST |
| 21081 | TCP | Protocolo JT808 (GPS Streamax) |
| 21081 | UDP | Protocolo JT808 (GPS Streamax) |
| 8400 | TCP | Protocolo JT1078 (vídeo) |

---

## Stack tecnológico

- **Backend:** Java 21, Jersey 4, Guice, Netty 4, PostgreSQL 16
- **Frontend:** React 18, Vite, Material UI v5
- **Vídeo:** ffmpeg (transcodificación H.264 → MP4)
- **Contenedores:** Docker, docker-compose, Kubernetes (GKE)
- **TLS:** cert-manager + Let's Encrypt

---

## Traccar original

Traccar es un sistema de rastreo GPS de código abierto con soporte para más de 200 protocolos y 2000 modelos de dispositivos. REST API disponible en [traccar.org/traccar-api](https://www.traccar.org/traccar-api/).

## Build

Please read [build from source documentation](https://www.traccar.org/build/) on the official website.

## Team

- Anton Tananaev ([anton@traccar.org](mailto:anton@traccar.org))
- Andrey Kunitsyn ([andrey@traccar.org](mailto:andrey@traccar.org))

## License

    Apache License, Version 2.0

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
