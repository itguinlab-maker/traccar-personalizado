# Respaldos — Nodiklab CCTV

Sistema de respaldo y restauración de todos los datos del cliente. Probado de extremo a extremo:
se borraron 1.557 eventos de conteo y se recuperaron íntegros desde el respaldo.

---

## Qué se respalda

Hay datos críticos **fuera** de PostgreSQL. Un respaldo que solo haga `pg_dump` los pierde.

| Origen | Contenido |
|---|---|
| PostgreSQL | Conteo de pasajeros, posiciones, usuarios, grupos (empresas), dispositivos, permisos, eventos |
| `/opt/traccar/data/*.json` | Fichas de vehículos, eventos Hikvision, grupos de reenvío externo, consumo de datos SIM |
| `/opt/traccar/*.xml` | Configuración del servidor |

---

## Uso diario

### Crear un respaldo

```sh
sh scripts/backup.sh
```

Genera `backups/nodiklab_AAAAMMDD_HHMMSS.tar.gz` (~1,6 MB con los datos actuales).

Cada respaldo incluye un `MANIFIESTO.txt` con los conteos al momento de crearlo — posiciones,
eventos de conteo, dispositivos, usuarios y empresas. Eso permite verificar después que una
restauración quedó completa.

El script **verifica el dump antes de darlo por bueno**: si `pg_restore` no puede leerlo, falla
en vez de dejar un archivo inservible.

Destino alternativo:

```sh
sh scripts/backup.sh /ruta/a/disco-externo
```

### Restaurar

```sh
sh scripts/restore.sh backups/nodiklab_20260912_142358.tar.gz
```

Muestra el manifiesto, pide escribir `RESTAURAR` para confirmar, y **antes de sobrescribir nada
guarda el estado actual** en `backups/antes_de_restaurar_*.dump`. Si restauras el respaldo
equivocado, puedes volver atrás.

Al terminar imprime los conteos restaurados para compararlos con el manifiesto.

---

## Respaldo automático diario

### Linux (servidor en la nube)

```sh
crontab -e
```

```
0 3 * * * cd /ruta/al/proyecto/traccar && sh scripts/backup.sh >> /var/log/nodiklab-backup.log 2>&1
```

Respalda todos los días a las 3:00. Revisar el log periódicamente: un respaldo que falla en
silencio es peor que no tenerlo.

### Windows (equipo local)

Programador de tareas → Crear tarea básica → Diaria → Iniciar un programa:

- Programa: `C:\Program Files\Git\bin\sh.exe`
- Argumentos: `scripts/backup.sh`
- Iniciar en: `C:\Users\USUARIO\Documents\Programacion\Traccar_personalizado\traccar`

---

## Retención

Por defecto se conservan **30 días** de respaldos; los más antiguos se eliminan solos. Solo se
borran archivos con el patrón `nodiklab_*.tar.gz`, nunca otros contenidos de la carpeta.

Para cambiarlo:

```sh
RETENTION_DAYS=90 sh scripts/backup.sh
```

---

## Copia fuera del servidor (bucket) — al subir a la nube

Un respaldo en el mismo servidor no protege contra la pérdida del servidor, que es exactamente
lo que ya ocurrió una vez en este proyecto. **Este paso se activa en el momento del despliegue
en la nube.**

### Paso 1 — Crear el bucket

El script funciona con cualquier almacenamiento compatible con S3. Elegir uno:

```sh
# Google Cloud Storage (si el servidor va en GCP)
gcloud storage buckets create gs://nodiklab-respaldos \
  --location=us-central1 \
  --uniform-bucket-level-access

# AWS S3
aws s3api create-bucket --bucket nodiklab-respaldos --region us-east-1
```

**Configurar en el bucket, antes de usarlo:**

| Ajuste | Valor | Por qué |
|---|---|---|
| Acceso público | **Bloqueado** | Los respaldos contienen datos de clientes |
| Versionado | **Activado** | Protege contra sobrescritura o borrado accidental |
| Ciclo de vida | Eliminar a los 90 días | Controla el costo sin perder histórico reciente |
| Cifrado | Activado (por defecto en GCS/S3) | Datos en reposo protegidos |

Regla de ciclo de vida en GCS:

```sh
echo '{"rule":[{"action":{"type":"Delete"},"condition":{"age":90}}]}' > lifecycle.json
gcloud storage buckets update gs://nodiklab-respaldos --lifecycle-file=lifecycle.json
```

### Paso 2 — Credenciales del servidor

**No usar credenciales personales.** Crear una identidad de servicio con permiso únicamente de
escritura sobre ese bucket:

```sh
# GCP: cuenta de servicio con acceso solo a este bucket
gcloud iam service-accounts create nodiklab-backup
gcloud storage buckets add-iam-policy-binding gs://nodiklab-respaldos \
  --member="serviceAccount:nodiklab-backup@PROYECTO.iam.gserviceaccount.com" \
  --role=roles/storage.objectCreator
```

`objectCreator` permite subir pero **no borrar**: si alguien compromete el servidor, no puede
destruir los respaldos anteriores.

### Paso 3 — Subida automática

Añadir al final del cron diario:

```sh
0 3 * * * cd /ruta/al/proyecto/traccar && sh scripts/backup.sh && \
  gcloud storage cp "$(ls -t backups/nodiklab_*.tar.gz | head -1)" gs://nodiklab-respaldos/ \
  >> /var/log/nodiklab-backup.log 2>&1
```

Con AWS:

```sh
aws s3 cp "$(ls -t backups/nodiklab_*.tar.gz | head -1)" s3://nodiklab-respaldos/
```

### Paso 4 — Verificar que realmente sube

Una semana después del despliegue, comprobar que hay 7 archivos:

```sh
gcloud storage ls -l gs://nodiklab-respaldos/
```

Si el cron falla en silencio, esto es lo único que lo detecta. Vale la pena revisarlo
periódicamente o añadir una alerta si no aparece un archivo nuevo en 48 horas.

---

## Prueba de recuperación

Un respaldo no verificado no es un respaldo. **Cada cierto tiempo hay que probar la restauración
completa**, preferiblemente en un entorno aparte:

1. Crear un respaldo.
2. Restaurarlo.
3. Comparar los conteos finales contra el `MANIFIESTO.txt`.
4. Entrar a la plataforma y confirmar que los eventos de conteo y los vehículos están.

---

## Antes de cualquier cambio riesgoso

Regla del proyecto: **respaldo previo obligatorio** antes de migraciones, limpiezas de datos,
recrear contenedores o cambiar volúmenes.

```sh
sh scripts/backup.sh
```

---

## Variables de configuración

| Variable | Por defecto | Uso |
|---|---|---|
| `DB_CONTAINER` | `traccar-postgres` | Nombre del contenedor de base de datos |
| `APP_CONTAINER` | `traccar_server` | Nombre del contenedor de la aplicación |
| `DB_NAME` / `DB_USER` | `traccar` | Base de datos y usuario |
| `RETENTION_DAYS` | `30` | Días de respaldos a conservar |

Útil si en la nube los contenedores tienen otros nombres:

```sh
DB_CONTAINER=prod-postgres APP_CONTAINER=prod-traccar sh scripts/backup.sh
```
