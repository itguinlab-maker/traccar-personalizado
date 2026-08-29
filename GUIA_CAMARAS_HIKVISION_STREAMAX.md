# Configuración de cámaras — Hikvision y Streamax

Cómo configurar cada tipo de cámara para que mande conteo de pasajeros y video a la plataforma. Para dar de alta el vehículo/dispositivo en la plataforma primero, ver [MANUAL_USUARIO.md](MANUAL_USUARIO.md) sección 3.

---

## Parte 1 — Cámaras Hikvision (modelo de 2 cámaras, una por puerta)

Cada cámara es un dispositivo independiente en la plataforma, cuenta su propia puerta. Cámara de referencia usada en esta integración: **DS-2XM6825G0/C-IVS** (serie IVS, con conteo de personas integrado) — otros modelos Hikvision con conteo deberían funcionar igual siempre que soporten ISAPI y el evento de conteo de personas.

### 1.1 Atributos del dispositivo en la plataforma

Antes de tocar la cámara, deja listos estos atributos en `Configuración → Dispositivos → (la cámara) → Atributos`:

| Atributo | Valor |
|---|---|
| `hikIp` | IP de la cámara en tu red (LAN/VPN) |
| `hikUser` | Usuario admin de la cámara |
| `hikPass` | Contraseña de la cámara |
| `hikTzOffset` | Offset de zona horaria, ej. `-05:00` |

### 1.2 Conteo de pasajeros — dos mecanismos, ambos activos a la vez

La plataforma **no depende de un solo mecanismo** — en cuanto guardas `hikIp` en el dispositivo, el servidor automáticamente:
- Abre una conexión de escucha permanente a la cámara (`alertStream` de ISAPI), Y
- Consulta periódicamente (cada 30s) el estado de conteo de la cámara por ISAPI.

Esto significa que **el conteo puede empezar a llegar sin tocar nada en la cámara**, siempre que:
1. La cámara sea alcanzable en red desde el servidor (misma LAN, VPN, o red pública si aplica).
2. El usuario/contraseña en `hikUser`/`hikPass` tengan permiso de API.
3. El módulo de conteo de personas esté **activo en la cámara misma** (ver 1.3).

Adicionalmente (opcional, más robusto para escalar a muchas cámaras porque no depende de que el servidor mantenga la conexión abierta) puedes configurar la cámara para que **empuje** el evento directamente:

- En la interfaz de la cámara (ISAPI, vía `PUT /ISAPI/Event/notification/httpHosts/<id>`, o el equivalente en la interfaz web de la cámara — normalmente `Configuración → Red → Avanzado → Notificación → Servidor HTTP` o similar según el modelo/firmware):
  - **IP/dominio del servidor**: la IP o dominio de tu servidor Traccar.
  - **Puerto**: `8082`.
  - **Ruta (URL)**: `/api/hikvision/event?deviceId=<ID_DEL_DISPOSITIVO_EN_LA_PLATAFORMA>` — el `deviceId` es el ID numérico interno del dispositivo en la plataforma (visible en la URL al editarlo, o en la ficha del dispositivo).
  - **Protocolo**: HTTP (no HTTPS a menos que tu servidor lo tenga configurado).
  - Guarda y usa la opción de "Probar" / "Test" de la cámara si la tiene — debe responder OK. Si da "Device Error", revisa que el firewall del servidor permita conexiones entrantes al puerto 8082 desde la IP de la cámara.

> No se necesita ningún token/autenticación en esta URL — el endpoint está abierto intencionalmente para que la cámara pueda llamarlo directo. No expongas el puerto 8082 más de lo necesario en redes no confiables.

### 1.3 Activar el módulo de conteo de personas en la cámara

Esto varía según el modelo/firmware exacto de la cámara — en general está bajo algo como `Configuración → Evento inteligente` o `Configuración → VCA` → **Conteo de personas** (People Counting). Actívalo y confirma que la línea/zona de conteo esté correctamente calibrada apuntando a la puerta física. Consulta el manual específico de tu modelo de cámara para esta parte — no es algo que la plataforma controle.

### 1.4 Video

`GET /api/hikvision/clip` en la plataforma descarga un clip por RTSP directo desde la cámara usando `hikIp`/`hikUser`/`hikPass`/`hikTzOffset` — no necesita configuración adicional en la cámara más allá de tener el streaming RTSP habilitado (viene así por defecto en la mayoría de cámaras Hikvision). Solo funciona si la cámara es alcanzable en red desde el servidor (LAN/VPN) — no funciona si la cámara está detrás de una SIM sin IP pública.

### 1.5 Video con cámara en SIM restringida (ISUP) — experimental

Si tu cámara está en una SIM que solo recibe datos salientes (sin IP alcanzable desde el servidor), existe un módulo experimental basado en el protocolo ISUP de Hikvision que aprovecha que la cámara abre la conexión hacia afuera. **Este módulo no viene desplegado por defecto** — requiere correr un componente adicional (`isup-gateway`) aparte del servidor principal, algo que hoy solo existe como prueba de concepto funcional, no como parte del despliegue estándar (`docker-compose.yml`). Si tu instalación no tiene este componente corriendo, no configures `isupDeviceId`/`isupCamera1`/`isupCamera2` — usa la ruta normal de la sección 1.4 en su lugar. Si necesitas esta capacidad en producción, es un trabajo de despliegue adicional, no solo de configuración.

---

## Parte 2 — MDVR Streamax (equipo centralizado, varias cámaras/puertas)

El MDVR es un solo dispositivo en la plataforma que maneja todas las cámaras/puertas del vehículo. La configuración de **red y protocolos** (cómo el equipo se conecta al servidor) está completamente cubierta en **[GUIA_INTEGRACION_MDVR.md](GUIA_INTEGRACION_MDVR.md)** — no se repite aquí. Esta sección cubre específicamente la parte de **cámaras/canales** dentro del propio DVR.

### 2.1 Verificar los canales de cámara (IPC)

En la interfaz web del propio DVR (ver GUIA_INTEGRACION_MDVR.md sección 1.1 para acceder), busca la sección de configuración de cámaras — típicamente bajo `Conf. → Videovigil. → Config. IPC` y `Config cám` (los nombres exactos varían según la versión de firmware del equipo). Ahí confirma:

- **Cuántos canales están realmente en uso** — deben coincidir con las cámaras físicamente instaladas. Es común encontrar canales configurados con una IP asignada pero sin cámara física conectada (residuo de una instalación anterior) — esto no rompe nada, pero conviene limpiarlo para no confundir el diagnóstico si un canal falla.
- **Estado de "pérdida de video" (Video Loss) por canal** — confirma que la alarma de pérdida de video esté **activada** para los canales realmente en uso. En una auditoría de este tipo de equipo se encontró la alarma desactivada mientras dos de los canales configurados sí estaban en estado de pérdida de señal — sin la alarma activa, eso pasa desapercibido indefinidamente.
- **Resolución/FPS por canal** — no necesitas fijarlos manualmente para que N9M funcione: el servidor los **aprende automáticamente** la primera vez que el equipo se conecta (pide la configuración vía `CONFIGMODEL/GET` apenas se conecta) y los usa para calcular la velocidad de reproducción del video descargado. Si cambias la resolución/FPS de una cámara después, basta con que el equipo se reconecte una vez para que el servidor tome el valor nuevo.

### 2.2 Grabación (SD / almacenamiento interno)

Confirma en `Conf. → Videovigil. → Config. grab.` que la grabación esté activa para los canales en uso y que el modo de grabación (continua / por movimiento / por evento) sea el que tu operación necesita — la descarga de clips de un evento de conteo solo funciona si existe una grabación real que cubra ese momento exacto en la tarjeta SD del equipo (ver la sección de solución de problemas en GUIA_INTEGRACION_MDVR.md, "el video se descarga muy lento o trae varios minutos de más").

### 2.3 Conteo de pasajeros (APC)

Ver GUIA_INTEGRACION_MDVR.md sección 1.4 — se verifica en `Conf. → Recol. datos`, confirmando que el conteo esté activo y el número de puertas configurado corresponda a los sensores instalados.

### 2.4 Otras verificaciones recomendadas al configurar un equipo nuevo

De una auditoría real de este tipo de equipo, vale la pena revisar también:

- **Cuentas de usuario del DVR**: además de `admin`, algunos equipos traen una segunda cuenta de usuario habilitada de fábrica — confírmala y cámbiale la contraseña o desactívala si no la vas a usar.
- **IPs duplicadas en canales deshabilitados**: canales IPC que no están en uso pero conservan una IP configurada de una instalación anterior — no causan fallas activas, pero conviene limpiarlos para que el diagnóstico de red del equipo sea claro.

Estos puntos no bloquean el funcionamiento de N9M/JT808, son buenas prácticas de mantenimiento del equipo.
