# Guía de Despliegue Local — Docker

## Requisitos previos

| Herramienta | Versión mínima | Verificar |
|---|---|---|
| Docker Desktop | 24+ | `docker --version` |
| Docker Compose | v2 | `docker compose version` |
| JDK | 17+ | `java -version` |
| WSL 2 (Windows) | — | `wsl --version` |

---

## Primer despliegue

### 1. Crear los volúmenes externos

```powershell
.\deploy-traccar\setup-local.ps1
```

Esto crea los volúmenes Docker externos `traccar_postgres_data` y `traccar_app_data` que persisten entre recreaciones del contenedor.

### 2. Compilar el backend (JAR)

Desde la raíz del proyecto (donde está `gradlew.bat`):

```powershell
# Windows
.\gradlew.bat assemble -x test
```

Resultado esperado: `target/tracker-server.jar`

### 3. Construir la imagen Docker

> **Importante:** usar `--no-cache` para forzar el rebuild del frontend React.

```powershell
docker build --no-cache -f deploy-traccar/Dockerfile -t traccar-personalizado:local .
```

### 4. Levantar el stack

```powershell
docker compose -f deploy-traccar/docker-compose.local.yml up -d
```

Esto levanta:
- **traccar_server** → web en http://localhost:8082
- **traccar-postgres** → PostgreSQL en localhost:5432

### 5. Verificar que está corriendo

```powershell
docker compose -f deploy-traccar/docker-compose.local.yml ps
docker logs traccar_server --tail=50 -f
```

---

## Actualizar la aplicación

Usar el script automatizado (recomendado):

```powershell
.\deploy-traccar\update-local.ps1
```

El script hace:
1. Verifica volúmenes externos
2. Backup de la BD antes de actualizar
3. Compila el JAR (`gradlew assemble -x test`)
4. Construye la imagen Docker
5. Push a GHCR (`ghcr.io/itguinlab-maker/traccar_personalizado:latest`)
6. Recrea **solo** el contenedor Traccar (PostgreSQL y datos intactos)

Para actualizar sin push a GHCR:
```powershell
.\deploy-traccar\update-local.ps1 -SkipPush
```

O manualmente paso a paso:
```powershell
# 1. Compilar
.\gradlew.bat assemble -x test

# 2. Construir imagen (--no-cache fuerza rebuild del frontend)
docker build --no-cache -f deploy-traccar/Dockerfile -t traccar-personalizado:local .

# 3. Reiniciar solo Traccar
docker compose -f deploy-traccar/docker-compose.local.yml up -d --no-deps --force-recreate traccar
```

---

## Acceder a la aplicación

| URL | Descripción |
|---|---|
| http://localhost:8082 | Interfaz web |
| http://192.168.1.X:8082 | Desde la red local |
| http://localhost:8082/api/server | Estado del servidor (JSON) |

Credenciales por defecto: `admin` / `admin` (cambiar inmediatamente).

---

## Persistencia de datos

| Volumen | Contenido |
|---|---|
| `traccar_app_data` | `vehicle_records.json`, `forwarding_groups.json`, logs |
| `traccar_postgres_data` | Base de datos PostgreSQL completa |

```powershell
docker volume ls
docker volume inspect traccar_app_data
```

---

## Backup de la base de datos

```powershell
# Crear backup
docker exec traccar-postgres pg_dump -U traccar traccar > "backup_$(Get-Date -Format 'yyyyMMdd_HHmmss').sql"

# Restaurar desde backup
Get-Content backup_FECHA.sql | docker exec -i traccar-postgres psql -U traccar traccar
```

---

## Puertos expuestos

| Puerto | Protocolo | Uso |
|---|---|---|
| 8082 | TCP | Interfaz web / API REST |
| 21081 | TCP | Protocolo JT808 (GPS Streamax) |
| 21081 | UDP | Protocolo JT808 (GPS Streamax) |
| 8400 | TCP | Protocolo JT1078 (vídeo streaming) |

---

## Solución de problemas comunes

**La web no carga:**
```powershell
docker logs traccar_server --tail=100
# Buscar líneas "ERROR" o "Exception"
```

**Páginas personalizadas no aparecen (frontend desactualizado):**
```powershell
# Usar --no-cache para forzar rebuild completo del frontend
docker build --no-cache -f deploy-traccar/Dockerfile -t traccar-personalizado:local .
docker compose -f deploy-traccar/docker-compose.local.yml up -d --no-deps --force-recreate traccar
```

**Traccar no conecta a PostgreSQL:**
```powershell
docker logs traccar_server 2>&1 | Select-String "postgres|database|connection"
docker compose -f deploy-traccar/docker-compose.local.yml ps
# Verificar que traccar-postgres esté en estado "healthy"
```

**Forzar reconstrucción completa:**
```powershell
docker compose -f deploy-traccar/docker-compose.local.yml down
docker rmi traccar-personalizado:local
docker build --no-cache -f deploy-traccar/Dockerfile -t traccar-personalizado:local .
docker compose -f deploy-traccar/docker-compose.local.yml up -d
```

---

## Deploy a producción (VM GCloud)

Una vez verificado localmente:

```powershell
# 1. Push a GHCR (ya incluido en update-local.ps1)
docker tag traccar-personalizado:local ghcr.io/itguinlab-maker/traccar_personalizado:latest
docker push ghcr.io/itguinlab-maker/traccar_personalizado:latest

# 2. En la VM (34.61.186.60) — via SSH
docker pull ghcr.io/itguinlab-maker/traccar_personalizado:latest
docker compose -f deploy-traccar/docker-compose.local.yml up -d --no-deps --force-recreate traccar
```
