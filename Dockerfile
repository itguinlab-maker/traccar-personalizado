# --- ETAPA 1: Compilar el Frontend en React (con Vite) ---
FROM node:20 AS frontend-builder
WORKDIR /src/traccar-web
COPY traccar-web/package*.json ./
RUN npm install --legacy-peer-deps
COPY traccar-web/ .
RUN npm run build

# --- ETAPA 2: Crear la Imagen Final Ligera ---
FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg python3 && rm -rf /var/lib/apt/lists/*
WORKDIR /opt/traccar

# 1. Copiamos los ejecutables y configuraciones de Java
COPY target/tracker-server.jar .
COPY target/lib ./lib
COPY debug.xml .
COPY schema ./schema
COPY templates ./templates

# 2. Copiamos la carpeta raíz de traccar-web para que Java encuentre /simple
COPY traccar-web ./traccar-web

# 3. CRUCIAL: Eliminamos cualquier residuo viejo en /web y montamos el build fresco de Vite
RUN rm -rf ./web
COPY --from=frontend-builder /src/traccar-web/build ./web

# 4. Entrypoint compartido con producción (inyecta SMTP desde env vars)
COPY deploy-traccar/docker-entrypoint.sh /docker-entrypoint.sh
RUN chmod +x /docker-entrypoint.sh

EXPOSE 8082 21081 21081/udp 8400
ENTRYPOINT ["/docker-entrypoint.sh"]
