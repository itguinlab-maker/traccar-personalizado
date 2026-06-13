# update-local.ps1 — Actualización segura de Traccar (NO toca PostgreSQL ni datos)
# Uso: .\deploy-traccar\update-local.ps1
# Uso (sin push a Docker Hub): .\deploy-traccar\update-local.ps1 -SkipPush
param(
    [switch]$SkipPush
)
$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path $PSScriptRoot -Parent

# ── Configuración Docker Hub ──────────────────────────────────
$DockerHubUser = "itguinlab-maker"     # ← tu usuario de Docker Hub
$ImageName     = "traccar_personalizado"
$RemoteImage   = "$DockerHubUser/$ImageName"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host " Traccar — Actualización segura" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# ── 1. Verificar volúmenes externos ──────────────────────────
foreach ($vol in @("traccar_postgres_data", "traccar_app_data")) {
    docker volume inspect $vol 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[ERROR] Volumen '$vol' no existe. Ejecuta setup-local.ps1 primero." -ForegroundColor Red
        exit 1
    }
}
Write-Host "[OK] Volúmenes externos verificados" -ForegroundColor Green

# ── 2. Backup antes de actualizar ────────────────────────────
$BackupDir = Join-Path $ProjectRoot "deploy-traccar\backups"
New-Item -ItemType Directory -Force -Path $BackupDir | Out-Null
$Timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$BackupFile = Join-Path $BackupDir "traccar_pre_update_$Timestamp.sql"

Write-Host "[BACKUP] Creando backup previo..." -ForegroundColor Yellow
try {
    $ErrorActionPreference = "Continue"
    docker exec traccar-postgres pg_dump -U traccar traccar 2>$null |
        Out-File -FilePath $BackupFile -Encoding utf8
    $ErrorActionPreference = "Stop"
    if ($LASTEXITCODE -eq 0 -and (Test-Path $BackupFile) -and (Get-Item $BackupFile).Length -gt 100) {
        Write-Host "[OK] Backup: $BackupFile" -ForegroundColor Green
    } else {
        if (Test-Path $BackupFile) { Remove-Item $BackupFile -Force }
        Write-Host "[WARN] Backup omitido - postgres no disponible" -ForegroundColor Yellow
    }
} catch {
    $ErrorActionPreference = "Stop"
    Write-Host "[WARN] Backup omitido" -ForegroundColor Yellow
}

# ── 3. Compilar el JAR ────────────────────────────────────────
Write-Host ""
Write-Host "[BUILD] Compilando JAR..." -ForegroundColor Yellow
Set-Location $ProjectRoot
& ".\gradlew.bat" assemble -x test
if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] Fallo al compilar." -ForegroundColor Red
    exit 1
}

# ── 4. Construir imagen Docker ────────────────────────────────
Write-Host "[BUILD] Construyendo imagen Docker..." -ForegroundColor Yellow
docker build -f deploy-traccar/Dockerfile -t traccar-personalizado:local .
if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] Fallo al construir la imagen." -ForegroundColor Red
    exit 1
}

# ── 5. Publicar imagen en Docker Hub ─────────────────────────
if (-not $SkipPush) {
    Write-Host ""
    Write-Host "[PUSH] Publicando imagen en Docker Hub..." -ForegroundColor Yellow

    $Tag = Get-Date -Format "yyyyMMdd_HHmmss"

    # Etiquetar con timestamp versionado y como latest
    docker tag traccar-personalizado:local "${RemoteImage}:${Tag}"
    docker tag traccar-personalizado:local "${RemoteImage}:latest"

    # Subir ambas etiquetas
    docker push "${RemoteImage}:${Tag}"
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[WARN] Push fallido. Verifica que hayas hecho 'docker login' previamente." -ForegroundColor Yellow
        Write-Host "       Continúa sin push..." -ForegroundColor Yellow
    } else {
        docker push "${RemoteImage}:latest"
        Write-Host "[OK] Imagen publicada:" -ForegroundColor Green
        Write-Host "     ${RemoteImage}:${Tag}" -ForegroundColor Green
        Write-Host "     ${RemoteImage}:latest" -ForegroundColor Green
        Write-Host ""
        Write-Host "     Para recuperar en otro servidor:" -ForegroundColor DarkGray
        Write-Host "     docker pull ${RemoteImage}:latest" -ForegroundColor DarkGray
    }
}

# ── 6. Recrear SOLO el contenedor de Traccar ─────────────────
Write-Host ""
Write-Host "[DEPLOY] Recreando contenedor traccar..." -ForegroundColor Yellow
docker compose -f deploy-traccar/docker-compose.local.yml `
    up -d --no-deps --force-recreate traccar

Write-Host ""
Write-Host "[VERIFY] Esperando arranque (15s)..."
Start-Sleep -Seconds 15
try {
    $resp = Invoke-WebRequest -Uri "http://localhost:8082/api/server" -TimeoutSec 5 -UseBasicParsing
    Write-Host "[OK] Traccar responde: HTTP $($resp.StatusCode)" -ForegroundColor Green
} catch {
    Write-Host "[WARN] Traccar aún arrancando, espera unos segundos más" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Green
Write-Host " Actualización completada." -ForegroundColor Green
Write-Host " PostgreSQL: intacto" -ForegroundColor Green
Write-Host " Datos app:  intactos" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green
