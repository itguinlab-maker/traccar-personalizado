#!/bin/sh
# docker-entrypoint.sh
# Genera /opt/traccar/conf/traccar.xml sustituyendo variables de entorno,
# luego arranca Traccar con ese archivo de configuración.
set -e

CONFIG_TPL=/etc/traccar-config/traccar.xml.tpl
CONFIG_OUT=/opt/traccar/conf/traccar.xml
DEBUG_XML=/opt/traccar/debug.xml

# Inyecta configuración SMTP desde variables de entorno en un XML de Traccar.
# Solo actúa si SMTP_HOST está definida y el archivo no tiene ya mail.smtp.host.
inject_smtp() {
  local file="$1"
  if [ -n "$SMTP_HOST" ] && ! grep -q "mail.smtp.host" "$file"; then
    python3 - "$file" << 'PYEOF'
import os, sys
file = sys.argv[1]
with open(file) as f:
    xml = f.read()
smtp = (
    "    <entry key='mail.smtp.host'>" + os.environ.get('SMTP_HOST', '') + "</entry>\n"
    "    <entry key='mail.smtp.port'>" + os.environ.get('SMTP_PORT', '587') + "</entry>\n"
    "    <entry key='mail.smtp.starttls.enable'>true</entry>\n"
    "    <entry key='mail.smtp.auth'>true</entry>\n"
    "    <entry key='mail.smtp.username'>" + os.environ.get('SMTP_USERNAME', '') + "</entry>\n"
    "    <entry key='mail.smtp.password'>" + os.environ.get('SMTP_PASSWORD', '') + "</entry>\n"
    "    <entry key='mail.smtp.from'>" + os.environ.get('SMTP_FROM', os.environ.get('SMTP_USERNAME', '')) + "</entry>\n"
)
xml = xml.replace('</properties>', smtp + '</properties>')
with open(file, 'w') as f:
    f.write(xml)
print('[entrypoint] SMTP configurado desde variables de entorno')
PYEOF
  fi
}

# Si viene un template de K8s ConfigMap → sustituir y usar
if [ -f "$CONFIG_TPL" ]; then
  echo "[entrypoint] Generando $CONFIG_OUT desde template..."
  envsubst < "$CONFIG_TPL" > "$CONFIG_OUT"
  echo "[entrypoint] Configuración generada OK"
  inject_smtp "$CONFIG_OUT"
  exec java -jar tracker-server.jar conf/traccar.xml "$@"
# Si ya hay un traccar.xml copiado directamente (docker-compose local)
elif [ -f "$CONFIG_OUT" ]; then
  echo "[entrypoint] Usando $CONFIG_OUT existente"
  inject_smtp "$CONFIG_OUT"
  exec java -jar tracker-server.jar conf/traccar.xml "$@"
# Fallback a debug.xml (desarrollo local sin volumen)
else
  echo "[entrypoint] ADVERTENCIA: usando debug.xml (modo desarrollo)"
  inject_smtp "$DEBUG_XML"
  exec java -jar tracker-server.jar debug.xml "$@"
fi
