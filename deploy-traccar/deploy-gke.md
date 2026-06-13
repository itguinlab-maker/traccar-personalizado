# Guía de Despliegue en Producción — GKE (Google Kubernetes Engine)

## Requisitos previos

| Herramienta | Instalación |
|---|---|
| `gcloud` CLI | https://cloud.google.com/sdk/docs/install |
| `kubectl` | `gcloud components install kubectl` |
| `helm` v3 | https://helm.sh/docs/intro/install/ |
| Docker | Para construir y publicar la imagen |
| JDK 17+ | Para compilar el JAR |

---

## Fase 1 — Preparación del proyecto GCP

### 1.1 Autenticarse y configurar el proyecto

```bash
gcloud auth login
gcloud config set project TU_PROYECTO_GCP
gcloud config set compute/region us-central1    # ← ajustar a tu región
gcloud config set compute/zone us-central1-a
```

### 1.2 Habilitar APIs necesarias

```bash
gcloud services enable \
  container.googleapis.com \
  artifactregistry.googleapis.com \
  compute.googleapis.com
```

### 1.3 Crear el repositorio en Artifact Registry

```bash
gcloud artifacts repositories create traccar \
  --repository-format=docker \
  --location=us-central1 \
  --description="Traccar personalizado"

# Autenticar Docker con Artifact Registry
gcloud auth configure-docker us-central1-docker.pkg.dev
```

---

## Fase 2 — Construir y publicar la imagen Docker

### 2.1 Compilar el backend

```powershell
# Windows
.\gradlew.bat assemble -x test
```

### 2.2 Construir y publicar la imagen

```bash
# Definir variables (ajustar)
PROJECT_ID="TU_PROYECTO_GCP"
REGION="us-central1"
TAG="v1.0.0"  # usar versionado semántico; nunca 'latest' en producción

IMAGE="${REGION}-docker.pkg.dev/${PROJECT_ID}/traccar/traccar-personalizado:${TAG}"

# Construir
docker build -f deploy-traccar/Dockerfile -t "${IMAGE}" .

# Publicar
docker push "${IMAGE}"
```

### 2.3 Actualizar la referencia de imagen en el Deployment

Editar [k8s/07-traccar-deployment.yaml](k8s/07-traccar-deployment.yaml) y reemplazar:
```
image: REGION-docker.pkg.dev/TU_PROYECTO_GCP/traccar/traccar-personalizado:latest
```
por:
```
image: us-central1-docker.pkg.dev/TU_PROYECTO_GCP/traccar/traccar-personalizado:v1.0.0
```

---

## Fase 3 — Crear el cluster GKE

```bash
gcloud container clusters create traccar-cluster \
  --zone us-central1-a \
  --num-nodes 2 \
  --machine-type e2-standard-2 \
  --disk-size 50 \
  --enable-autoscaling --min-nodes 1 --max-nodes 3

# Obtener credenciales para kubectl
gcloud container clusters get-credentials traccar-cluster --zone us-central1-a
```

> Para producción con costo mínimo, `e2-medium` (2 vCPU, 4 GB) es suficiente para empezar.

---

## Fase 4 — Instalar componentes del cluster

### 4.1 nginx-ingress-controller

```bash
helm repo add ingress-nginx https://kubernetes.github.io/ingress-nginx
helm repo update

helm install ingress-nginx ingress-nginx/ingress-nginx \
  --namespace ingress-nginx \
  --create-namespace \
  --set controller.service.type=LoadBalancer \
  --set controller.service.externalTrafficPolicy=Local

# Obtener la IP pública del nginx (usarla para el DNS del dominio web)
kubectl get svc -n ingress-nginx ingress-nginx-controller
# Anotar EXTERNAL-IP → apuntar traccar.tudominio.com a esa IP
```

### 4.2 cert-manager (Let's Encrypt)

```bash
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.15.3/cert-manager.yaml

# Esperar a que esté listo (~60s)
kubectl wait --for=condition=available --timeout=180s \
  deployment/cert-manager \
  deployment/cert-manager-webhook \
  deployment/cert-manager-cainjector \
  -n cert-manager
```

---

## Fase 5 — Configurar antes de aplicar los manifiestos

### 5.1 Definir la contraseña de la base de datos

Editar [k8s/01-secrets.yaml](k8s/01-secrets.yaml) y cambiar:
```yaml
db-password: CAMBIA_ESTA_PASSWORD_ANTES_DE_APLICAR
```

O mejor, crear el Secret directamente (no queda en archivos):
```bash
kubectl apply -f deploy-traccar/k8s/00-namespace.yaml

kubectl create secret generic traccar-db-secret \
  --from-literal=db-user=traccar \
  --from-literal=db-password=TU_PASSWORD_SEGURA \
  --from-literal=db-name=traccar \
  --namespace traccar
```

### 5.2 Configurar el dominio

Editar [k8s/11-ingress.yaml](k8s/11-ingress.yaml):
```yaml
# Reemplazar traccar.tudominio.com por tu dominio real
```

### 5.3 Configurar el email para Let's Encrypt

Editar [k8s/10-cert-issuer.yaml](k8s/10-cert-issuer.yaml):
```yaml
email: admin@tudominio.com   # ← tu email real
```

### 5.4 Reservar IP estática para los dispositivos GPS (recomendado)

```bash
# Reservar IP estática para el LoadBalancer de protocolos GPS
gcloud compute addresses create traccar-protocols-ip \
  --region us-central1

# Ver la IP reservada
gcloud compute addresses describe traccar-protocols-ip --region us-central1
# Anotar la IP → configurarla en todos los dispositivos GPS

# Descomentar en k8s/09-traccar-protocols-service.yaml:
# kubernetes.io/ingress.global-static-ip-name: "traccar-protocols-ip"
```

---

## Fase 6 — Aplicar los manifiestos

```bash
# Aplicar en orden (los números garantizan el orden correcto)
kubectl apply -f deploy-traccar/k8s/00-namespace.yaml
kubectl apply -f deploy-traccar/k8s/01-secrets.yaml      # omitir si ya se creó manualmente
kubectl apply -f deploy-traccar/k8s/02-configmap.yaml
kubectl apply -f deploy-traccar/k8s/03-postgres-pvc.yaml
kubectl apply -f deploy-traccar/k8s/04-postgres-deployment.yaml
kubectl apply -f deploy-traccar/k8s/05-postgres-service.yaml
kubectl apply -f deploy-traccar/k8s/06-traccar-pvc.yaml
kubectl apply -f deploy-traccar/k8s/07-traccar-deployment.yaml
kubectl apply -f deploy-traccar/k8s/08-traccar-web-service.yaml
kubectl apply -f deploy-traccar/k8s/09-traccar-protocols-service.yaml
kubectl apply -f deploy-traccar/k8s/10-cert-issuer.yaml
kubectl apply -f deploy-traccar/k8s/11-ingress.yaml
```

O aplicar todo de una vez:
```bash
kubectl apply -f deploy-traccar/k8s/
```

---

## Fase 7 — Verificar el despliegue

```bash
# Ver estado de todos los pods
kubectl get pods -n traccar -w

# Ver servicios y sus IPs
kubectl get svc -n traccar

# Ver el Ingress y su IP/host
kubectl get ingress -n traccar

# Logs de Traccar
kubectl logs -n traccar deployment/traccar -f

# Logs de PostgreSQL
kubectl logs -n traccar deployment/traccar-postgres -f
```

### Verificar que el certificado TLS se emitió

```bash
kubectl get certificate -n traccar
# Debe aparecer READY: True (puede tardar 2-5 minutos)

kubectl describe certificate traccar-tls-cert -n traccar
```

---

## Fase 8 — Configurar DNS

| Registro DNS | Tipo | Valor |
|---|---|---|
| `traccar.tudominio.com` | A | IP del nginx-ingress LoadBalancer |

Una vez configurado el DNS, acceder a:
`https://traccar.tudominio.com`

---

## Actualizar la aplicación en producción

```bash
# 1. Compilar nuevo JAR
.\gradlew.bat assemble -x test

# 2. Construir y publicar nueva imagen con nuevo tag
TAG="v1.1.0"
IMAGE="us-central1-docker.pkg.dev/${PROJECT_ID}/traccar/traccar-personalizado:${TAG}"
docker build -f deploy-traccar/Dockerfile -t "${IMAGE}" .
docker push "${IMAGE}"

# 3. Actualizar la imagen en el Deployment
kubectl set image deployment/traccar \
  traccar="${IMAGE}" \
  -n traccar

# 4. Ver el rollout
kubectl rollout status deployment/traccar -n traccar

# Revertir si algo falla
kubectl rollout undo deployment/traccar -n traccar
```

---

## Backup y restauración de la base de datos en GKE

### Crear backup

```bash
kubectl exec -n traccar deployment/traccar-postgres -- \
  pg_dump -U traccar traccar > backup_$(date +%Y%m%d_%H%M%S).sql
```

### Restaurar backup

```bash
# Copiar el archivo al pod
kubectl cp backup_FECHA.sql traccar/$(kubectl get pod -n traccar -l app=traccar-postgres -o jsonpath='{.items[0].metadata.name}'):/tmp/backup.sql

# Restaurar
kubectl exec -n traccar deployment/traccar-postgres -- \
  psql -U traccar traccar < /tmp/backup.sql
```

### Backup automático con CronJob (recomendado)

```bash
# Crear un bucket GCS para backups
gsutil mb gs://TU_PROYECTO-traccar-backups

# Aplicar el CronJob (crear el archivo si es necesario)
# Ver sección "Extras" al final de esta guía
```

---

## Migración desde Docker local a GKE

Si ya tenías datos en el Docker local, migrar la base de datos antes de desplegar:

```bash
# 1. Exportar desde Docker local
docker exec traccar-postgres \
  pg_dump -U traccar traccar > migration.sql

# 2. Esperar a que el pod de postgres en GKE esté listo
kubectl wait --for=condition=ready pod \
  -l app=traccar-postgres -n traccar --timeout=120s

# 3. Importar en GKE
kubectl exec -n traccar deployment/traccar-postgres -- \
  psql -U traccar traccar < migration.sql

# 4. Migrar el volumen de datos (vehicle_records.json, forwarding_groups.json)
# Copiar desde el contenedor local al pod de GKE
TRACCAR_POD=$(kubectl get pod -n traccar -l app=traccar -o jsonpath='{.items[0].metadata.name}')
docker cp traccar_server:/opt/traccar/data/vehicle_records.json ./vehicle_records.json
kubectl cp vehicle_records.json traccar/${TRACCAR_POD}:/opt/traccar/data/vehicle_records.json
```

---

## Monitoreo y costos en GCP

### Ver uso de recursos

```bash
kubectl top pods -n traccar
kubectl top nodes
```

### Costos estimados (zona us-central1)

| Recurso | Tipo | Costo aprox/mes |
|---|---|---|
| 2x e2-medium (GKE) | Compute | ~$60 USD |
| 20 GB SSD (PostgreSQL) | Storage | ~$3.40 USD |
| 10 GB SSD (datos Traccar) | Storage | ~$1.70 USD |
| 2x LoadBalancer IPs | Networking | ~$14 USD |
| Egress (tráfico) | Variable | ~$5-20 USD |
| **Total estimado** | | **~$85-100 USD/mes** |

> Para reducir costos: usar Autopilot GKE, Spot nodes, o una VM e2-medium individual con Docker Compose.

---

## Solución de problemas en GKE

**Pod en CrashLoopBackOff:**
```bash
kubectl describe pod -n traccar -l app=traccar
kubectl logs -n traccar -l app=traccar --previous
```

**Certificado TLS no se emite:**
```bash
kubectl describe clusterissuer letsencrypt-prod
kubectl describe certificaterequest -n traccar
# Verificar que el DNS del dominio apunta a la IP correcta del nginx-ingress
```

**No llegan datos de los GPS:**
```bash
# Verificar la IP del LoadBalancer de protocolos
kubectl get svc traccar-protocols -n traccar
# Asegurarse de que esa IP está configurada en los dispositivos GPS
# Verificar puertos abiertos en el firewall de GCP
gcloud compute firewall-rules list --filter="name~traccar"
```

**Abrir puertos en el firewall de GCP si fuera necesario:**
```bash
gcloud compute firewall-rules create allow-traccar-protocols \
  --allow tcp:21081,udp:21081,tcp:8400 \
  --direction INGRESS \
  --priority 1000 \
  --description "Protocolos GPS Traccar"
```
