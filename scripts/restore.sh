#!/bin/sh
# =============================================================================
# Restauración de un respaldo de Nodiklab CCTV
#
#   ./restore.sh backups/nodiklab_20260912_143000.tar.gz
#
# SOBRESCRIBE los datos actuales del servidor. Antes de hacerlo crea un
# respaldo de seguridad del estado actual, para poder volver atrás si la
# restauración resulta ser la equivocada.
# =============================================================================
set -e

# Ver nota en backup.sh: evita que Git Bash traduzca rutas del contenedor Linux.
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

ARCHIVE="$1"
DB_CONTAINER="${DB_CONTAINER:-traccar-postgres}"
APP_CONTAINER="${APP_CONTAINER:-traccar_server}"
DB_NAME="${DB_NAME:-traccar}"
DB_USER="${DB_USER:-traccar}"

log() { echo "[$(date +%H:%M:%S)] $*"; }
fail() { echo "ERROR: $*" >&2; exit 1; }

[ -n "$ARCHIVE" ] || fail "uso: ./restore.sh <archivo.tar.gz>"
[ -f "$ARCHIVE" ] || fail "no existe el archivo: $ARCHIVE"

docker ps --format '{{.Names}}' | grep -q "^${DB_CONTAINER}$" \
    || fail "el contenedor '$DB_CONTAINER' no está corriendo"

# --- extraer ----------------------------------------------------------------
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
tar -xzf "$ARCHIVE" -C "$TMP"
SRC=$(find "$TMP" -maxdepth 1 -type d -name 'nodiklab_*' | head -1)
[ -n "$SRC" ] || fail "el archivo no tiene la estructura esperada"

echo ""
echo "===================== CONTENIDO DEL RESPALDO ====================="
cat "$SRC/MANIFIESTO.txt" 2>/dev/null || echo "(sin manifiesto)"
echo "================================================================="
echo ""
echo "Esto SOBRESCRIBE los datos actuales del servidor."
printf "Escribe RESTAURAR para continuar: "
read -r CONFIRM
[ "$CONFIRM" = "RESTAURAR" ] || fail "cancelado por el usuario"

# --- respaldo de seguridad del estado actual --------------------------------
# Si la restauración es la equivocada, esto permite deshacerla.
SAFETY="./backups/antes_de_restaurar_$(date +%Y%m%d_%H%M%S).dump"
mkdir -p ./backups
log "guardando estado actual en $SAFETY ..."
docker exec "$DB_CONTAINER" pg_dump -U "$DB_USER" -d "$DB_NAME" -Fc -f /tmp/safety.dump
docker cp "$DB_CONTAINER:/tmp/safety.dump" "$SAFETY" >/dev/null
docker exec "$DB_CONTAINER" rm -f /tmp/safety.dump
log "    estado actual guardado"

# --- restaurar base de datos ------------------------------------------------
log "restaurando base de datos..."
# `docker cp` hacia el contenedor traduce la ruta destino en Git Bash (C:\tmp\...).
# Enviar el archivo por stdin evita por completo esa conversión.
docker exec -i "$DB_CONTAINER" sh -c 'cat > /tmp/restore.dump' < "$SRC/database.dump"
# --clean --if-exists: reemplaza el contenido sin fallar si un objeto no existía.
docker exec "$DB_CONTAINER" pg_restore -U "$DB_USER" -d "$DB_NAME" \
    --clean --if-exists --no-owner /tmp/restore.dump 2>&1 | grep -vi "^pg_restore: warning" || true
docker exec "$DB_CONTAINER" rm -f /tmp/restore.dump

# --- restaurar archivos JSON ------------------------------------------------
if [ -d "$SRC/data" ]; then
    log "restaurando fichas de vehículos y eventos Hikvision..."
    # Mismo motivo que arriba: se envía cada archivo por stdin en vez de `docker cp`.
    for f in "$SRC"/data/*.json; do
        [ -f "$f" ] || continue
        name=$(basename "$f")
        docker exec -i "$APP_CONTAINER" sh -c "cat > /opt/traccar/data/$name" < "$f"
        log "    $name"
    done
fi

# --- reiniciar y verificar --------------------------------------------------
log "reiniciando servidor..."
docker restart "$APP_CONTAINER" >/dev/null
sleep 12

POSITIONS=$(docker exec "$DB_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -t -A \
    -c "SELECT COUNT(*) FROM tc_positions;" 2>/dev/null || echo "?")
COUNTING=$(docker exec "$DB_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -t -A \
    -c "SELECT COUNT(*) FROM tc_positions WHERE attributes LIKE '%counting_event%';" 2>/dev/null || echo "?")
USERS=$(docker exec "$DB_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -t -A \
    -c "SELECT COUNT(*) FROM tc_users;" 2>/dev/null || echo "?")

echo ""
echo "===================== RESTAURACIÓN COMPLETA ====================="
echo "  Estado restaurado:  posiciones=$POSITIONS  conteo=$COUNTING  usuarios=$USERS"
echo ""
echo "  Compara estos números con el MANIFIESTO de arriba."
echo "  Si algo no cuadra, el estado anterior está en: $SAFETY"
echo "================================================================"
