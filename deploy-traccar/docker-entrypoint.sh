#!/bin/sh
# docker-entrypoint.sh
# Genera /opt/traccar/conf/traccar.xml sustituyendo variables de entorno,
# luego arranca Traccar con ese archivo de configuración.
set -e

CONFIG_TPL=/etc/traccar-config/traccar.xml.tpl
CONFIG_OUT=/opt/traccar/conf/traccar.xml

# Si viene un template de K8s ConfigMap → sustituir y usar
if [ -f "$CONFIG_TPL" ]; then
  echo "[entrypoint] Generando $CONFIG_OUT desde template..."
  envsubst < "$CONFIG_TPL" > "$CONFIG_OUT"
  echo "[entrypoint] Configuración generada OK"
  exec java -jar tracker-server.jar conf/traccar.xml "$@"
# Si ya hay un traccar.xml copiado directamente (docker-compose local)
elif [ -f "$CONFIG_OUT" ]; then
  echo "[entrypoint] Usando $CONFIG_OUT existente"
  exec java -jar tracker-server.jar conf/traccar.xml "$@"
# Fallback a debug.xml (desarrollo local sin volumen)
else
  echo "[entrypoint] ADVERTENCIA: usando debug.xml (modo desarrollo)"
  exec java -jar tracker-server.jar debug.xml "$@"
fi
