#!/bin/sh
# =============================================================================
# Respaldo completo de Nodiklab CCTV
#
# Respalda TODO lo que es irrecuperable si se pierde el servidor:
#
#   1. PostgreSQL  -> conteo de pasajeros, posiciones, usuarios, grupos,
#                     dispositivos, permisos y eventos.
#   2. Archivos JSON de /opt/traccar/data -> fichas de vehículos, eventos
#                     Hikvision, grupos de reenvío y consumo de datos SIM.
#                     ESTOS NO ESTÁN EN LA BASE DE DATOS: un respaldo que solo
#                     haga pg_dump los pierde.
#   3. Configuración del servidor (traccar.xml / debug.xml).
#
# Uso:
#   ./backup.sh                    respaldo en ./backups
#   ./backup.sh /ruta/destino      respaldo en otra ruta
#
# Para respaldo automático diario, ver RESPALDOS.md
# =============================================================================
set -e

# En Git Bash (Windows) las rutas absolutas dentro de `docker exec` se traducen a rutas
# de Windows y rompen los comandos que corren DENTRO del contenedor Linux. Esto lo evita.
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

DEST="${1:-./backups}"
DB_CONTAINER="${DB_CONTAINER:-traccar-postgres}"
APP_CONTAINER="${APP_CONTAINER:-traccar_server}"
DB_NAME="${DB_NAME:-traccar}"
DB_USER="${DB_USER:-traccar}"
RETENTION_DAYS="${RETENTION_DAYS:-30}"

STAMP=$(date +%Y%m%d_%H%M%S)
WORK="$DEST/nodiklab_$STAMP"

log() { echo "[$(date +%H:%M:%S)] $*"; }
fail() { echo "ERROR: $*" >&2; exit 1; }

# --- comprobaciones previas -------------------------------------------------
docker ps --format '{{.Names}}' | grep -q "^${DB_CONTAINER}$" \
    || fail "el contenedor de base de datos '$DB_CONTAINER' no está corriendo"

mkdir -p "$WORK"
log "respaldo en: $WORK"

# --- 1. base de datos -------------------------------------------------------
# Formato custom (-Fc): comprimido y restaurable por tabla con pg_restore.
log "1/4 base de datos PostgreSQL..."
docker exec "$DB_CONTAINER" pg_dump -U "$DB_USER" -d "$DB_NAME" -Fc -f /tmp/db.dump \
    || fail "pg_dump falló"
docker cp "$DB_CONTAINER:/tmp/db.dump" "$WORK/database.dump" >/dev/null
docker exec "$DB_CONTAINER" rm -f /tmp/db.dump

# Verificación real: un dump que no se puede listar no sirve como respaldo.
docker exec "$DB_CONTAINER" sh -c "true" >/dev/null 2>&1
OBJECTS=$(docker run --rm -v "$(cd "$WORK" && pwd):/b" postgres:16 \
    pg_restore -l /b/database.dump 2>/dev/null | grep -c ';' || echo 0)
[ "$OBJECTS" -gt 0 ] || fail "el dump no se puede leer con pg_restore — respaldo INVÁLIDO"
log "    dump verificado ($OBJECTS objetos)"

# --- 2. archivos de datos JSON ---------------------------------------------
# vehicle_records.json, hikvision_events.json, forwarding_groups.json, data_usage.json
log "2/4 archivos de datos (vehículos, eventos Hikvision, consumo SIM)..."
if docker exec "$APP_CONTAINER" sh -c "ls /opt/traccar/data/*.json" >/dev/null 2>&1; then
    mkdir -p "$WORK/data"
    docker cp "$APP_CONTAINER:/opt/traccar/data/." "$WORK/data/" >/dev/null
    COUNT=$(find "$WORK/data" -name '*.json' | wc -l | tr -d ' ')
    log "    $COUNT archivos JSON respaldados"
else
    log "    ADVERTENCIA: no se encontraron archivos JSON en /opt/traccar/data"
fi

# --- 3. configuración -------------------------------------------------------
log "3/4 configuración del servidor..."
mkdir -p "$WORK/config"
for f in conf/traccar.xml debug.xml; do
    docker cp "$APP_CONTAINER:/opt/traccar/$f" "$WORK/config/" >/dev/null 2>&1 || true
done
ls "$WORK/config" >/dev/null 2>&1 && log "    configuración respaldada"

# --- 4. manifiesto y verificación ------------------------------------------
log "4/4 manifiesto..."

# Conteos de control: permiten verificar la restauración contra el origen.
POSITIONS=$(docker exec "$DB_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -t -A \
    -c "SELECT COUNT(*) FROM tc_positions;" 2>/dev/null || echo "?")
COUNTING=$(docker exec "$DB_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -t -A \
    -c "SELECT COUNT(*) FROM tc_positions WHERE attributes LIKE '%counting_event%';" 2>/dev/null || echo "?")
DEVICES=$(docker exec "$DB_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -t -A \
    -c "SELECT COUNT(*) FROM tc_devices;" 2>/dev/null || echo "?")
USERS=$(docker exec "$DB_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -t -A \
    -c "SELECT COUNT(*) FROM tc_users;" 2>/dev/null || echo "?")
GROUP_COUNT=$(docker exec "$DB_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -t -A \
    -c "SELECT COUNT(*) FROM tc_groups;" 2>/dev/null || echo "?")

cat > "$WORK/MANIFIESTO.txt" <<EOF
Respaldo Nodiklab CCTV
======================
Fecha:            $(date '+%Y-%m-%d %H:%M:%S %Z')
Servidor:         $(hostname)

Contenido verificado al momento del respaldo
--------------------------------------------
Posiciones totales:        $POSITIONS
Eventos de conteo:         $COUNTING
Dispositivos:              $DEVICES
Usuarios:                  $USERS
Grupos (empresas):         $GROUP_COUNT
Objetos en el dump:        $OBJECTS

Archivos
--------
database.dump     PostgreSQL completo (pg_restore -Fc)
data/             Fichas de vehículos, eventos Hikvision, reenvío, consumo SIM
config/           Configuración del servidor

Restauración
------------
Ver RESPALDOS.md. Resumen:
  docker cp database.dump $DB_CONTAINER:/tmp/
  docker exec $DB_CONTAINER pg_restore -U $DB_USER -d $DB_NAME --clean --if-exists /tmp/database.dump
  docker cp data/. $APP_CONTAINER:/opt/traccar/data/
  docker restart $APP_CONTAINER

Tras restaurar, comparar los conteos de arriba con los del servidor restaurado.
EOF

# --- comprimir --------------------------------------------------------------
ARCHIVE="$DEST/nodiklab_$STAMP.tar.gz"
tar -czf "$ARCHIVE" -C "$DEST" "nodiklab_$STAMP"
rm -rf "$WORK"

SIZE=$(du -h "$ARCHIVE" | cut -f1)
log "listo: $ARCHIVE ($SIZE)"

# --- retención --------------------------------------------------------------
# Solo borra respaldos propios (patrón nodiklab_*.tar.gz), nunca otros archivos.
OLD=$(find "$DEST" -maxdepth 1 -name 'nodiklab_*.tar.gz' -mtime +"$RETENTION_DAYS" 2>/dev/null | wc -l | tr -d ' ')
if [ "$OLD" -gt 0 ]; then
    find "$DEST" -maxdepth 1 -name 'nodiklab_*.tar.gz' -mtime +"$RETENTION_DAYS" -delete
    log "retención: $OLD respaldo(s) de más de $RETENTION_DAYS días eliminados"
fi

echo ""
echo "  Posiciones: $POSITIONS | Conteo: $COUNTING | Dispositivos: $DEVICES | Usuarios: $USERS"
echo "  Archivo:    $ARCHIVE"
