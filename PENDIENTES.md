# Pendientes — Nodiklab CCTV

Trabajo acordado pero no implementado. Ordenado por valor para la venta real del servicio.

---

## P1 — Usuarios y asignación de vehículos

**Qué falta:** el rol "dueño de bus" (un usuario que ve *vehículos específicos*, no toda la empresa).

Traccar ya soporta esto nativamente mediante permisos usuario→dispositivo. Lo que no existe es la
**interfaz para que un administrador de empresa lo gestione**: hoy solo se puede dar acceso por grupo
completo.

Alcance a construir:

- Pantalla "Usuarios de mi empresa" para el rol `admin_empresa`.
- Crear usuario con rol limitado (`propietario`, `supervisor`) dentro de su propia empresa.
- Selector de vehículos **filtrado por el backend** (nunca cargar todos los dispositivos y filtrar
  en el navegador).
- Validación en backend: un admin de empresa no puede asignar equipos de otra empresa.
- Quitar una asignación sin borrar el usuario ni el historial del vehículo.
- Auditoría de quién asignó qué y cuándo.

**Criterio de aceptación:** un propietario del Bus 101 entra y ve únicamente el Bus 101, incluso
si escribe a mano la URL de otro vehículo.

---

## P2 — Cámara de conteo en puerta trasera

**Bloqueante para el producto de conteo.** Hoy solo hay cámara en la puerta delantera, así que solo
se registran ascensos (histórico: 3.088 subidas contra 84 bajadas).

Sin esto no se puede calcular:

- Ocupación a bordo (subidas − bajadas).
- Alerta de sobrecupo contra la capacidad del vehículo.
- Cualquier indicador de rotación real de pasajeros.

El campo `passengerCapacity` ya se captura en el alta de vehículo; queda sin uso hasta resolver esto.

---

## P3 — Consultas abiertas con Streamax

Enviadas en `informecliente/Informe_Integracion_Streamax_Nodiklab.docx`. Al recibir respuesta:

- ¿Existe un ACK que evite la retransmisión de eventos ya entregados?
- ¿El evento trae identificador único/secuencial propio del equipo? (hoy se deduplica por huella
  del payload).
- Formato real de `UPDATEIOSTATUSINFO` y de los mensajes de estado de puertas.
- Estructura de `DEVEMM/DISPATHERPROXYMSG` para configuración remota.

Completar además la **versión de firmware** del equipo en ese informe (marcada `[COMPLETAR]`).

---

## P4 — Catálogo permanente de video

Hoy los clips se generan en un archivo temporal y se entregan al navegador; no quedan almacenados.

Para un centro de incidentes o evidencia se necesita: tabla de catálogo (dispositivo, canal,
segmento, evento asociado, checksum, estado, retención) y almacenamiento de objetos (S3/MinIO).
Detalle en `mejoras.MD` §9.6.

---

## ~~P5 — Cobertura de pruebas del protocolo N9M~~ — HECHO

`N9mProtocolDecoderTest` con 8 casos: conteo + GPS del evento, huella de deduplicación, mapeo de
puerta delantera/trasera, DSNO desconocido, keepalive y tramas no-JSON.

Verificado que detectan regresiones reales: al quitar `streamax.raw` del decoder a propósito,
2 tests fallaron; al restaurarlo, verde de nuevo.

---

## ~~Sistema de respaldos~~ — HECHO

`scripts/backup.sh` y `scripts/restore.sh`, documentados en `RESPALDOS.md`. Cubren PostgreSQL,
los 4 archivos JSON fuera de la base de datos y la configuración.

Probado de extremo a extremo: se borraron los 1.557 eventos de conteo y se recuperaron íntegros.

Pendiente al desplegar en la nube: **programar el respaldo diario** (cron) y **copiar los
archivos fuera del servidor** (S3 u otro destino). Un respaldo en el mismo servidor no protege
contra la pérdida del servidor.

---

## Deuda menor detectada

- `HikClipResource` (`GET /api/hikclip`) es código muerto: el frontend nunca lo llama y usa el
  atributo `hikTimezone`, que no existe en la interfaz de atributos (la real es `hikTzOffset`).
  Sí valida permisos, así que no es un riesgo — pero conviene eliminarlo para que nadie lo use
  por error.
- `jt808.udp.port` y el mapeo `5263` en `docker-compose.yml` no tienen efecto (JT808 es solo TCP
  en este fork; JT1078 usa 8400). Son restos de configuración.

---

## Pospuesto deliberadamente

| Tema | Razón |
|---|---|
| Sincronización masiva por WiFi (`mejoras.MD` §9.5-9.7) | La detección de red por IP pública es frágil. Sin una medición real del problema de consumo, no justifica su complejidad. |
| Configuración remota del MDVR (`mejoras.MD` §11) | Depende de documentación del fabricante que aún no tenemos (`DISPATHERPROXYMSG` nunca se decodificó). |
| Indicadores de ocupación | Bloqueado por P2. Los números actuales no soportarían un informe de ocupación. |
