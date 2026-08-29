# Guía de despliegue en la nube

Cómo poner el servidor CountinG&KLAB a correr en una VM en la nube, desde cero, usando el flujo **actual** del repo (raíz: `Dockerfile` + `docker-compose.yml`, con TLS de JT808 y N9M ya integrados).

> **Nota sobre otros documentos de despliegue en este repo**: `deploy-traccar/deploy-gke.md`, `deploy-traccar/deploy-local.md` y los manifiestos en `deploy-traccar/k8s/` describen una ruta de despliegue **más vieja y desactualizada** — usan el puerto JT808 21081 (hoy es 6556), no tienen TLS, y no incluyen N9M en absoluto. Esta guía documenta la ruta **actual** (`Dockerfile`/`docker-compose.yml` en la raíz del repo, tocados por última vez el 2026-08-18). No mezcles los dos flujos.

---

## Requisitos previos

**En tu máquina de desarrollo:**

| Herramienta | Versión mínima | Para qué |
|---|---|---|
| JDK | 17+ (probado con 21) | Compilar el backend (`gradlew assemble`) |
| Docker | cualquiera reciente | Construir y probar la imagen |
| Git | cualquiera reciente | Clonar el repo — **con submódulos**, ver abajo |

No necesitas Node.js instalado — el frontend se compila **dentro** del build de Docker (etapa `frontend-builder` del `Dockerfile`), no en tu máquina.

**En la VM de la nube:** solo Docker. Nada más.

### ⚠️ El repo tiene un submódulo — clónalo correctamente

`traccar-web/` (el frontend) es un **submódulo git independiente**, no una carpeta normal del repo. Si clonas con un `git clone` común, esa carpeta queda **vacía** y el build de Docker falla de inmediato (no encuentra `package.json`). Clona así:

```
git clone --recurse-submodules https://github.com/itguinlab-maker/traccar-personalizado.git
```

Si ya clonaste sin ese flag (la carpeta `traccar-web/` está vacía o solo tiene un archivo `.git`):

```
git submodule update --init --recursive
```

---

## 0. Arquitectura del despliegue

Dos partes:

- **Tu máquina de desarrollo** (donde ya compilas y pruebas hoy): compila el backend, genera la imagen Docker, la sube a un registro de contenedores.
- **La VM en la nube**: solo necesita Docker instalado — descarga (`pull`) la imagen ya construida y la levanta. No necesita Java, Gradle, ni Node instalados.

```
tu PC                              VM en la nube
──────                             ─────────────
gradlew assemble                   docker pull imagen
docker build                  →    docker compose up -d
docker push (a un registro)        (sirve en :8082, :6556, :21083, :21720, :8400)
```

---

## 1. En tu máquina — compilar y publicar la imagen

### 1.1 Compilar el backend

```
.\gradlew.bat assemble -x test
```

Esto genera `target/tracker-server.jar` y `target/lib/` — el `Dockerfile` los copia directo, no compila Java dentro del build de Docker.

### 1.2 Generar el keystore TLS de JT808 (si no existe ya)

El `Dockerfile` copia `jt808-keystore.p12` desde la raíz del repo — este archivo **no está en git** (está en `.gitignore` por ser una clave privada), así que si es la primera vez que compilas en esta máquina, o si nunca se generó, créalo:

```
keytool -genkeypair -alias traccar-jt808 -keyalg RSA -keysize 2048 -validity 3650 ^
  -keystore jt808-keystore.p12 -storetype PKCS12 ^
  -storepass <elige-una-clave> -keypass <elige-una-clave> ^
  -dname "CN=traccar-jt808, O=CountinGKLAB, C=CO"
```

Colócalo en la raíz del repo (junto a `Dockerfile`). Si usas una contraseña distinta a `traccar123`, actualiza también `JAVA_TOOL_OPTIONS` en `docker-compose.yml` (ver 1.4).

### 1.3 Construir la imagen

```
docker build -f Dockerfile -t traccar-personalizado:latest .
```

Esto compila el frontend (React/Vite) dentro del build (etapa `frontend-builder`) y arma la imagen final con el JAR, el keystore, `ffmpeg` y `python3` ya instalados.

### 1.4 Revisar `JAVA_TOOL_OPTIONS` en `docker-compose.yml`

Ya viene configurado así — solo confírmalo si cambiaste la contraseña del keystore en el paso 1.2:

```yaml
environment:
  JAVA_TOOL_OPTIONS: >-
    -Djavax.net.ssl.keyStore=/opt/traccar/jt808-keystore.p12
    -Djavax.net.ssl.keyStorePassword=traccar123
    -Djavax.net.ssl.keyStoreType=PKCS12
```

### 1.5 Etiquetar y subir a un registro de contenedores

Usa el registro que prefieras (este proyecto ya usa GitHub Container Registry para el flujo viejo, es un buen default si no tienes otro):

```
docker tag traccar-personalizado:latest ghcr.io/<tu-usuario>/traccar-personalizado:latest
docker login ghcr.io
docker push ghcr.io/<tu-usuario>/traccar-personalizado:latest
```

---

## 2. En la VM de la nube

### 2.1 Crear la VM

Cualquier proveedor sirve — estos pasos usan `gcloud` (Google Cloud) como ejemplo concreto, porque es lo que ya se usa en este proyecto, pero el resto de la guía (Docker, puertos, compose) aplica igual en cualquier proveedor.

```
gcloud compute instances create traccar-prod \
  --zone=us-central1-a \
  --machine-type=e2-small \
  --image-family=debian-12 --image-project=debian-cloud \
  --boot-disk-size=30GB \
  --tags=traccar-server
```

> Usa al menos `e2-small` (no `e2-micro`) para un servidor de producción real — la conversión de video con ffmpeg y varios equipos conectados a la vez consumen más que lo mínimo.

### 2.2 Reservar una IP pública **estática**

A diferencia de una prueba temporal, un servidor de producción necesita una IP que no cambie (los DVR/cámaras la tienen configurada de forma fija):

```
gcloud compute addresses create traccar-prod-ip --region=us-central1
gcloud compute instances add-access-config traccar-prod \
  --zone=us-central1-a \
  --address=$(gcloud compute addresses describe traccar-prod-ip --region=us-central1 --format="value(address)")
```

### 2.3 Abrir los puertos necesarios

| Puerto | Protocolo | Uso |
|---|---|---|
| 8082 | TCP | Interfaz web / API |
| 6556 | TCP | JT808 (GPS + ignición, con TLS) |
| 8400 | TCP | JT1078 (si se usa) |
| 21083 | TCP | N9M control (conteo + comandos de video) |
| 21720 | TCP | N9M video |

```
gcloud compute firewall-rules create traccar-prod-ports \
  --network=default --direction=INGRESS --action=ALLOW \
  --rules=tcp:8082,tcp:6556,tcp:8400,tcp:21083,tcp:21720 \
  --source-ranges=0.0.0.0/0 --target-tags=traccar-server
```

> No hace falta abrir `6556/udp` ni `5263` — ambos son restos de configuración sin uso real en este despliegue (JT808 es solo TCP; 5263 es el puerto por defecto de JT1078 en Traccar, pero este proyecto usa 8400 explícitamente).

### 2.4 Instalar Docker

```
sudo apt-get update && sudo apt-get install -y docker.io docker-compose-plugin
sudo systemctl enable --now docker
sudo usermod -aG docker $USER
```
(Cierra sesión y vuelve a entrar para que el grupo `docker` tome efecto.)

### 2.5 Preparar el volumen y la red externos

`docker-compose.yml` espera que ambos ya existan (`external: true`) — Compose se niega a arrancar si no:

```
docker volume create traccar_data
docker network create traccar-network
```

### 2.6 Traer los archivos de despliegue

Copia a la VM (`scp` o `git clone` del repo si prefieres tenerlo todo):
- `docker-compose.yml`
- `.env` (opcional, ver 2.7)
- Un `traccar.xml` de **producción real** (ver 2.8 — **no uses `debug.xml` en producción**)

### 2.7 Variables de entorno (opcional, para correo saliente)

Copia `.env.example` a `.env` y llena tus credenciales SMTP reales:

```
SMTP_HOST=smtp.gmail.com
SMTP_PORT=587
SMTP_USERNAME=tu-correo@gmail.com
SMTP_PASSWORD=tu-contraseña-de-aplicación
SMTP_FROM=tu-correo@gmail.com
```

Sin este archivo el servidor arranca igual, solo sin envío de correo (recuperación de contraseña, alertas, etc.).

### 2.8 IMPORTANTE — usa un `traccar.xml` de producción, no `debug.xml`

La imagen trae `debug.xml` incluido, pero es explícitamente modo desarrollo (`web.debug=true`, `web.console=true`, logging `TRACE` de cada consulta SQL) — el propio script de arranque del contenedor imprime una advertencia si termina usándolo. Para producción, crea un `traccar.xml` propio junto a `docker-compose.yml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE properties SYSTEM 'http://java.sun.com/dtd/properties.dtd'>
<properties>
    <entry key='web.path'>./web</entry>
    <entry key='web.url'>http://<IP-PUBLICA-O-DOMINIO>:8082</entry>

    <entry key='logger.console'>true</entry>
    <entry key='logger.level'>info</entry>

    <entry key='database.driver'>org.postgresql.Driver</entry>
    <entry key='database.url'>jdbc:postgresql://traccar-postgres:5432/traccar</entry>
    <entry key='database.user'>traccar</entry>
    <entry key='database.password'>traccar123</entry>

    <entry key='jt808.port'>6556</entry>
    <entry key='jt808.ssl'>true</entry>

    <entry key='jt1078.port'>8400</entry>

    <entry key='n9m.port'>21083</entry>
    <entry key='n9mmedia.port'>21720</entry>
    <entry key='n9m.serverHost'>&lt;IP-PUBLICA-O-DOMINIO&gt;</entry>

    <entry key='decoder.timezone'>America/Bogota</entry>
</properties>
```

Reemplaza `<IP-PUBLICA-O-DOMINIO>` por la IP estática del paso 2.2 (o un dominio si le apuntas uno). Cambia también las contraseñas de base de datos si no vas a usar `traccar123` en producción.

Luego agrega el montaje de este archivo en `docker-compose.yml`, dentro de `volumes:` del servicio `traccar_server`:

```yaml
    volumes:
      - traccar_data:/opt/traccar/data
      - ./traccar.xml:/opt/traccar/conf/traccar.xml:ro
```

Sin este paso, el servidor sigue funcionando pero corre en modo desarrollo indefinidamente.

### 2.9 Descargar la imagen y levantar

```
docker login ghcr.io
docker pull ghcr.io/<tu-usuario>/traccar-personalizado:latest
docker tag ghcr.io/<tu-usuario>/traccar-personalizado:latest traccar_personalizado:latest
docker compose up -d
```

### 2.10 Verificar

```
docker compose logs -f traccar_server
docker compose ps
```

Deberías ver el arranque sin la advertencia de `debug.xml`, y `http://<IP-PUBLICA>:8082` debe cargar la plataforma. `docker compose ps` debe mostrar exactamente **2 contenedores corriendo**: `traccar_server` y `traccar-postgres` — eso es todo lo que este despliegue necesita, no falta ningún tercer contenedor.

> **Sobre el módulo ISUP (video Hikvision por SIM restringida)**: existe un componente adicional (`isup-gateway`) para esa función específica, pero **no forma parte de este despliegue** ni se puede agregar automáticamente — su SDK viene bajo licencia/NDA directo de Hikvision (no es software libre redistribuible) y su código vive fuera de este repositorio. Si tu operación no necesita descargar video de cámaras Hikvision sin IP pública, no lo necesitas — el conteo y el video normal de Hikvision (RTSP directo) y todo lo de Streamax funcionan sin él. Si sí lo necesitas, es un despliegue aparte que requiere gestionar la licencia del SDK con Hikvision directamente; ver [GUIA_CAMARAS_HIKVISION_STREAMAX.md](GUIA_CAMARAS_HIKVISION_STREAMAX.md) sección 1.5.

---

## 3. Actualizar el servidor más adelante

Cada vez que haya cambios de código:

**En tu máquina:**
```
docker build -f Dockerfile -t traccar-personalizado:latest .
docker tag traccar-personalizado:latest ghcr.io/<tu-usuario>/traccar-personalizado:latest
docker push ghcr.io/<tu-usuario>/traccar-personalizado:latest
```

**En la VM:**
```
docker pull ghcr.io/<tu-usuario>/traccar-personalizado:latest
docker tag ghcr.io/<tu-usuario>/traccar-personalizado:latest traccar_personalizado:latest
docker compose up -d --force-recreate traccar_server
```

Esto no toca la base de datos ni el volumen de datos — solo reemplaza el contenedor de la aplicación.

---

## 4. Checklist de seguridad antes de ir a producción real

- [ ] Cambiar la contraseña de PostgreSQL (`traccar123` es la que trae el repo por defecto — cámbiala en `docker-compose.yml` y en tu `traccar.xml`).
- [ ] Cambiar la contraseña del keystore JT808 (`traccar123` por defecto — ver 1.2/1.4).
- [ ] Usar HTTPS real para la interfaz web (esta guía deja `web.url` en `http://`; para HTTPS hace falta un proxy inverso como nginx/Caddy con un certificado de Let's Encrypt delante del puerto 8082 — no cubierto aquí, es una capa adicional sobre esta VM).
- [ ] Confirmar que estás usando el `traccar.xml` de producción (paso 2.8), no `debug.xml`.
- [ ] Reservar la IP como estática (paso 2.2) — si es efímera, cambia al reiniciar la VM y rompe la configuración de cada DVR/cámara.

---

## 5. Referencia rápida

| Elemento | Valor |
|---|---|
| Build de la imagen | `docker build -f Dockerfile -t traccar-personalizado:latest .` (raíz del repo, **no** `deploy-traccar/Dockerfile`) |
| Compose | `docker-compose.yml` (raíz, **no** `deploy-traccar/docker-compose.local.yml`) |
| Volumen externo requerido | `traccar_data` |
| Red externa requerida | `traccar-network` |
| Puertos a abrir | 8082, 6556 (TCP), 8400, 21083, 21720 |
| Config de producción | Montar un `traccar.xml` propio — no depender de `debug.xml` |
| Archivos que debes generar tú (no están en git) | `jt808-keystore.p12`, `.env` (opcional), `traccar.xml` de producción |
