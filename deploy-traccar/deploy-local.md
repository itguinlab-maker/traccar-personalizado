# Guía de Despliegue Local — Docker

## Requisitos previos

| Herramienta | Versión mínima | Verificar |
|---|---|---|
| Docker Desktop | 24+ | `docker --version` |
| Docker Compose | v2 | `docker compose version` |
| JDK | 17+ | `java -version` |
| Node.js | 20+ | `node --version` |
| WSL 2 (Windows) | — | `wsl --version` |

---

## 1. Compilar el backend (JAR)

Desde la raíz del proyecto (donde está `gradlew.bat`):

```powershell
# Windows
.\gradlew.bat assemble -x test

# Linux / WSL
./gradlew assemble -x test
```

Resultado esperado: `target/tracker-server.jar`

---

## 2. Construir la imagen Docker

Desde la raíz del proyecto:

```bash
docker build \
  -f deploy-traccar/Dockerfile \
  -t traccar-personalizado:local \
  .
```

> El Dockerfile compila el frontend React automáticamente (stage 1).
> El backend debe estar pre-compilado con Gradle (paso anterior).

---

## 3. Levantar el stack

```bash
docker compose -f deploy-traccar/docker-compose.local.yml up -d
```

Esto levanta:
- **traccar_server** → web en http://localhost:8082
- **traccar-postgres** → PostgreSQL en localhost:5432

### Verificar que está corriendo

```bash
docker compose -f deploy-traccar/docker-compose.local.yml ps
docker logs traccar_server --tail=50 -f
```

---

## 4. Acceder a la aplicación

| URL | Descripción |
|---|---|
| http://localhost:8082 | Interfaz web de Traccar |
| http://localhost:8082/api/server | Estado del servidor (JSON) |

Credenciales por defecto de Traccar:
- Usuario: `admin`
- Contraseña: `admin`

> **Cambiar la contraseña de admin inmediatamente** en Configuración → Cuenta.

---

## 5. Persistencia de datos

Los volúmenes de Docker guardan:

| Volumen | Contenido |
|---|---|
| `traccar_data` | `/opt/traccar/data/vehicle_records.json`, `forwarding_groups.json`, etc. |
| `postgres_data` | Base de datos PostgreSQL completa |

Para inspeccionar:

```bash
docker volume ls
docker volume inspect deploy-traccar_postgres_data
```

---

## 6. Backup de la base de datos

```bash
# Crear backup
docker exec traccar-postgres \
  pg_dump -U traccar traccar > backup_$(date +%Y%m%d_%H%M%S).sql

# Restaurar desde backup
docker exec -i traccar-postgres \
  psql -U traccar traccar < backup_FECHA.sql
```

---

## 7. Actualizar la aplicación

```bash
# 1. Compilar el nuevo JAR
.\gradlew.bat assemble -x test

# 2. Reconstruir la imagen
docker build -f deploy-traccar/Dockerfile -t traccar-personalizado:local .

# 3. Reiniciar solo el contenedor de Traccar (sin tocar la BD)
docker compose -f deploy-traccar/docker-compose.local.yml up -d --no-deps traccar
```

---

## 8. Detener el stack

```bash
# Solo detener (sin borrar volúmenes)
docker compose -f deploy-traccar/docker-compose.local.yml down

# Detener y BORRAR los volúmenes (¡pierde los datos!)
docker compose -f deploy-traccar/docker-compose.local.yml down -v
```

---

## 9. Puertos expuestos

| Puerto | Protocolo | Uso |
|---|---|---|
| 8082 | TCP | Interfaz web / API REST |
| 21081 | TCP | Protocolo JT808 (GPS) |
| 21081 | UDP | Protocolo JT808 (GPS) |
| 8400 | TCP | Protocolo JT1078 (vídeo) |

---

## 10. Solución de problemas comunes

**La web no carga:**
```bash
docker logs traccar_server --tail=100
# Buscar líneas "ERROR" o "Exception"
```

**Traccar no conecta a PostgreSQL:**
```bash
docker logs traccar_server 2>&1 | grep -i "postgres\|database\|connection"
# Verificar que traccar-postgres esté en estado "healthy"
docker compose -f deploy-traccar/docker-compose.local.yml ps
```

**Error de permisos en volumen (Linux):**
```bash
sudo chown -R 1000:1000 ./data
```

**Forzar reconstrucción completa:**
```bash
docker compose -f deploy-traccar/docker-compose.local.yml down
docker rmi traccar-personalizado:local
docker build -f deploy-traccar/Dockerfile -t traccar-personalizado:local .
docker compose -f deploy-traccar/docker-compose.local.yml up -d
```
