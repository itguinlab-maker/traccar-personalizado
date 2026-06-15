# setup-local.ps1 — Inicialización única del entorno de desarrollo local
# Ejecutar UNA VEZ antes del primer despliegue.
# Seguro de re-ejecutar: verifica antes de crear.
# Uso (sin push a Docker Hub): .\deploy-traccar\setup-local.ps1 -SkipPush
param(
    [switch]$SkipPush
)
$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path $PSScriptRoot -Parent

# ── Configuración GitHub Container Registry ──────────────────
$GithubUser  = "itguinlab-maker"
$ImageName   = "traccar_personalizado"
$RemoteImage = "ghcr.io/$GithubUser/$ImageName"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host " Traccar — Setup de volúmenes externos" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# ── 1. Crear volúmenes externos ───────────────────────────────
foreach ($vol in @("traccar_postgres_data", "traccar_app_data")) {
    docker volume inspect $vol 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "[OK] Volumen '$vol' ya existe" -ForegroundColor Green
    } else {
        Write-Host "[CREAR] Volumen '$vol'..." -ForegroundColor Yellow
        docker volume create $vol
        Write-Host "[OK] Creado" -ForegroundColor Green
    }
}

# ── 2. Compilar el JAR ────────────────────────────────────────
Write-Host ""
Write-Host "[BUILD] Compilando JAR con Gradle..." -ForegroundColor Yellow
Set-Location $ProjectRoot
& ".\gradlew.bat" assemble -x test
if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] Fallo al compilar. Revisa los errores de Gradle." -ForegroundColor Red
    exit 1
}
Write-Host "[OK] JAR compilado en target/tracker-server.jar" -ForegroundColor Green

# ── 3. Construir imagen Docker ────────────────────────────────
Write-Host ""
Write-Host "[BUILD] Construyendo imagen Docker..." -ForegroundColor Yellow
docker build -f deploy-traccar/Dockerfile -t traccar-personalizado:local .
if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] Fallo al construir la imagen Docker." -ForegroundColor Red
    exit 1
}
Write-Host "[OK] Imagen traccar-personalizado:local lista" -ForegroundColor Green

# ── 4. Publicar imagen en Docker Hub ─────────────────────────
if (-not $SkipPush) {
    Write-Host ""
    Write-Host "[PUSH] Publicando imagen en Docker Hub..." -ForegroundColor Yellow

    $Tag = Get-Date -Format "yyyyMMdd_HHmmss"

    docker tag traccar-personalizado:local "${RemoteImage}:${Tag}"
    docker tag traccar-personalizado:local "${RemoteImage}:latest"

    docker push "${RemoteImage}:${Tag}"
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[WARN] Push fallido. Ejecuta 'docker login ghcr.io' y vuelve a intentar." -ForegroundColor Yellow
    } else {
        docker push "${RemoteImage}:latest"
        Write-Host "[OK] Imagen publicada:" -ForegroundColor Green
        Write-Host "     ${RemoteImage}:${Tag}" -ForegroundColor Green
        Write-Host "     ${RemoteImage}:latest" -ForegroundColor Green
    }
}

# ── 5. Levantar el stack ──────────────────────────────────────
Write-Host ""
Write-Host "[INICIAR] Levantando el stack..." -ForegroundColor Yellow
docker compose -f deploy-traccar/docker-compose.local.yml up -d

Write-Host ""
Write-Host "========================================" -ForegroundColor Green
Write-Host " Setup completo." -ForegroundColor Green
Write-Host " Web: http://localhost:8082" -ForegroundColor Green
Write-Host " Volúmenes protegidos:" -ForegroundColor Green
Write-Host "   traccar_postgres_data  (base de datos)" -ForegroundColor Green
Write-Host "   traccar_app_data       (datos de la app)" -ForegroundColor Green
if (-not $SkipPush) {
    Write-Host " Imagen en Docker Hub:" -ForegroundColor Green
    Write-Host "   docker pull ${RemoteImage}:latest" -ForegroundColor Green
}
Write-Host "========================================" -ForegroundColor Green
