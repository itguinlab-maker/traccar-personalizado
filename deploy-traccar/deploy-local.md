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

> **REGLA OBLIGATORIA:** Antes de cualquier acción destructiva (recrear contenedores,
> cambiar volúmenes, `docker rm`, `docker compose down`, modificar la BD directamente)
> se debe hacer un backup. Sin excepción, incluso en local.

### Acciones que requieren backup previo

- `docker compose up --force-recreate`
- `docker rm` de contenedores con volúmenes de datos
- `docker compose down` (especialmente con `-v`)
- Cambios en la sección `volumes:` del `docker-compose.yml`
- Cualquier operación destructiva en producción

### Crear backup (local)

```powershell
# Formato binario (recomendado — más robusto para restore)
docker exec traccar-postgres pg_dump -U traccar -d traccar -Fc -f /tmp/backup.dump
docker cp traccar-postgres:/tmp/backup.dump ".\backup_local_$(Get-Date -Format 'yyyyMMdd_HHmmss').dump"

# Formato SQL legible (alternativa)
docker exec traccar-postgres pg_dump -U traccar traccar > "backup_$(Get-Date -Format 'yyyyMMdd_HHmmss').sql"
```

### Restaurar backup (local)

```powershell
# Desde formato binario
docker cp .\backup_local_FECHA.dump traccar-postgres:/tmp/restore.dump
docker exec traccar-postgres pg_restore -U traccar -d traccar -c /tmp/restore.dump

# Desde formato SQL
Get-Content backup_FECHA.sql | docker exec -i traccar-postgres psql -U traccar traccar
```

### Crear backup (producción — VM GCloud)

```powershell
# El archivo queda en ~/traccar/ dentro de la VM
ssh -i "$HOME\.ssh\google_compute_engine" USUARIO@34.61.186.60 `
  "docker exec traccar-postgres pg_dump -U traccar -d traccar -Fc > ~/traccar/backup_prod_`$(date +%Y%m%d_%H%M%S).dump"

# Verificar que se creó
ssh -i "$HOME\.ssh\google_compute_engine" USUARIO@34.61.186.60 "ls -lh ~/traccar/backup_prod_*.dump"
```

### Restaurar backup (producción)

```bash
# Copiar el backup al contenedor y restaurar
ssh -i "$HOME\.ssh\google_compute_engine" USUARIO@34.61.186.60 \
  "docker cp ~/traccar/backup_prod_FECHA.dump traccar-postgres:/tmp/restore.dump && \
   docker exec traccar-postgres pg_restore -U traccar -d traccar -c /tmp/restore.dump"
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

**VM:** `34.61.186.60` — GCloud  
**Usuario SSH:** `USUARIO`  
**Clave SSH:** `~/.ssh/google_compute_engine`  
**Docker Compose en VM:** `~/traccar/docker-compose.yml`  
**Imagen:** `ghcr.io/itguinlab-maker/traccar_personalizado:latest`

### Flujo completo (desde Windows)

```powershell
# 1. Compilar JAR
.\gradlew.bat assemble -x test

# 2. Construir imagen local
docker build --no-cache -f deploy-traccar/Dockerfile -t traccar-personalizado:local .

# 3. Etiquetar y subir a GHCR
docker tag traccar-personalizado:local ghcr.io/itguinlab-maker/traccar_personalizado:latest
docker push ghcr.io/itguinlab-maker/traccar_personalizado:latest

# 4. Conectarse a la VM y actualizar el contenedor
ssh -i "$HOME\.ssh\google_compute_engine" USUARIO@34.61.186.60 "cd ~/traccar && docker pull ghcr.io/itguinlab-maker/traccar_personalizado:latest && docker compose up -d --no-deps --force-recreate traccar"

# 5. Verificar estado
ssh -i "$HOME\.ssh\google_compute_engine" USUARIO@34.61.186.60 "docker ps --filter name=traccar_server --format '{{.Names}} {{.Status}}'"
```

> Los pasos 1–3 ya están automatizados en `update-local.ps1`. Solo se necesita ejecutar el paso 4 manualmente después.

### Solo actualizar contenedor (si la imagen ya está en GHCR)

```powershell
ssh -i "$HOME\.ssh\google_compute_engine" USUARIO@34.61.186.60 "cd ~/traccar && docker pull ghcr.io/itguinlab-maker/traccar_personalizado:latest && docker compose up -d --no-deps --force-recreate traccar"
```

### Verificar logs en producción

```powershell
ssh -i "$HOME\.ssh\google_compute_engine" USUARIO@34.61.186.60 "docker logs traccar_server --tail=50"
```

### Estructura de archivos en la VM

```
~/traccar/
├── docker-compose.yml   # stack (traccar_server + traccar-postgres)
└── traccar.xml          # configuración del servidor
```

Los datos persisten en volúmenes Docker nombrados (`traccar_app_data`, `traccar_postgres_data`), no en el sistema de archivos del host.
