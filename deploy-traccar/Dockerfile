# ============================================================
# Dockerfile de PRODUCCIÓN — Traccar Personalizado
# Uso desde la raíz del proyecto:
#   docker build -f deploy-traccar/Dockerfile -t traccar-personalizado:latest .
# ============================================================

# --- Etapa 1: compilar el frontend React/Vite ---
FROM node:20-slim AS frontend-builder
WORKDIR /src/traccar-web
COPY traccar-web/package*.json ./
RUN npm install --legacy-peer-deps
COPY traccar-web/ .
RUN npm run build

# --- Etapa 2: imagen final de producción ---
FROM eclipse-temurin:21-jre-jammy

# ffmpeg para transcodificación de vídeo MDVR
# gettext-base para el comando envsubst (sustitución de variables en traccar.xml)
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg gettext-base netcat-openbsd \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/traccar
RUN mkdir -p conf data

# Artefactos Java compilados por Gradle (./gradlew assemble -x test)
COPY target/tracker-server.jar .
COPY target/lib ./lib
COPY schema ./schema
COPY templates ./templates

# Frontend Traccar (necesario para rutas /simple y similares)
COPY traccar-web ./traccar-web

# Build de Vite como raíz web
RUN rm -rf ./web
COPY --from=frontend-builder /src/traccar-web/build ./web

# Entrypoint que genera traccar.xml desde variables de entorno
COPY deploy-traccar/docker-entrypoint.sh /docker-entrypoint.sh
RUN chmod +x /docker-entrypoint.sh

# 8082  → interfaz web
# 21081 → protocolo JT808 (TCP y UDP)
# 8400  → protocolo JT1078 (vídeo)
EXPOSE 8082 21081 21081/udp 8400

ENTRYPOINT ["/docker-entrypoint.sh"]
