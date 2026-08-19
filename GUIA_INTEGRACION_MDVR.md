# Integración MDVR N9M + JT808

Guía completa para conectar un MDVR Streamax (conteo de pasajeros + video + GPS) a la plataforma, y para dar de alta el equipo dentro de Traccar. Cubre la configuración del equipo, del servidor y de la plataforma, de principio a fin.

**Para quién es esta guía**: escrita para alguien que nunca ha usado la plataforma y necesita instalar y habilitar un equipo desde cero. No se asume ningún conocimiento previo — cada pantalla, campo y atributo se explica antes de pedirte que lo llenes.

## Arquitectura

```
                 ┌─────────────────────────────┐
                 │      MDVR Streamax (equipo)  │
                 │      cámaras · GPS · ACC     │
                 └───────────┬─────────┬────────┘
                              │         │
              N9M (Servidor 1)│         │JT808 (Servidor 2)
              conteo + video  │         │GPS continuo + ignición
              JSON plano/TCP  │         │binario/TCP, CON TLS
                              ▼         ▼
                 ┌─────────────────────────────┐
                 │        Servidor Traccar      │
                 │  n9m.port  n9mmedia.port  jt808.port │
                 └───────────┬─────────────────┘
                              ▼
                 ┌─────────────────────────────┐
                 │    Plataforma (navegador)    │
                 │  mapa · vista en vivo · conteo │
                 │  · consumo de datos SIM       │
                 └─────────────────────────────┘
```

> **Nota:** esta guía tiene tres roles distintos mezclados: la **Parte 1** la hace quien tiene el DVR en la mano; la **Parte 2** la hace quien administra el servidor (una sola vez, no por cada vehículo); la **Parte 3 y 4** las hace quien opera la plataforma día a día. Si tú solo vas a dar de alta vehículos, puedes saltar directo a la [Parte 3](#3--dar-de-alta-el-equipo-en-la-plataforma) — asumiendo que alguien ya hizo la 1 y la 2 para tu flota.

---

## 0. Antes de empezar

Reúne estos datos antes de tocar nada — te van a pedir todos en algún punto de la guía.

| Dato | Dónde se consigue |
|---|---|
| Usuario y contraseña admin del DVR | Vienen con el equipo, o los definió quien lo instaló |
| IP local del DVR | Se ve en la pantalla del propio equipo, o en la lista de clientes de tu router |
| Serial / `DSNO` del DVR | Pantalla de información del equipo, o etiqueta física — se vuelve a confirmar en el paso 1.2 |
| IP o dominio público del servidor Traccar | Te lo da quien administra el servidor |
| Acceso de administrador a la plataforma Traccar | Usuario con rol de administrador o `admin_empresa` |
| Acceso al servidor (para quien haga el paso 2) | Solo lo necesita la persona que despliega/mantiene el servidor, no quien da de alta vehículos |

---

## 1. Configurar el DVR

El equipo necesita apuntar sus dos protocolos al servidor: uno para conteo y video (N9M), otro para GPS continuo e ignición (JT808). Van en dos "servidores" distintos dentro de la configuración del propio DVR.

### 1.1 Acceder a la interfaz web del DVR

El DVR tiene su propia página de configuración, separada de la plataforma. Se accede desde un navegador en la misma red local que el equipo (por ejemplo, conectado al mismo WiFi/hotspot que el DVR durante la instalación).

1. **Abre la IP del equipo en el navegador** — escribe la IP local del DVR (paso 0) directamente en la barra de direcciones, por ejemplo `http://192.168.1.47`. Debe aparecer una pantalla de inicio de sesión.
2. **Inicia sesión** — usa el usuario y contraseña admin del equipo (normalmente `admin` + la contraseña que trae de fábrica o la que definió el instalador — no la reutilices de otro sitio).
3. **Entra a la configuración de red/servidores** — busca en el menú algo como `Conf. → Config Serv` o `Conf. → Config. red` (el nombre exacto varía un poco según la versión de firmware). Ahí vas a ver una lista de "servidores", normalmente dos, numerados.
   - Si los rótulos del menú no coinciden exactamente con estos nombres, busca la pantalla que tenga campos de **IP del servidor**, **puerto** y una casilla de **TLS** — es la que necesitas.

### 1.2 Configurar el Servidor N9M (conteo + video)

Este es el canal que manda los eventos de conteo de pasajeros y el video (en vivo e histórico). Suele ser el **Servidor 1**.

```
Config Serv → Servidor 1 → Config. red
  IP del servidor .... ‹IP o dominio del servidor Traccar›
  Puerto .............. 21083
  Habil. TLS .......... Desactivado
```

> **Importante:** el puerto `21083` es el que usa este despliegue para N9M — confírmalo con quien administra el servidor, porque es configurable (clave `n9m.port` en el servidor, ver 2.2). N9M **no** necesita TLS activado.

1. **Anota el SERIAL / DSNO del equipo** — en esta misma pantalla (o en "Información del sistema") vas a ver un identificador del equipo, normalmente etiquetado `SERIAL` o `DSNO` — algo como `00E4006A50`. Anótalo tal cual aparece: lo vas a necesitar exacto, letra por letra, en la Parte 3 para vincular el equipo dentro de la plataforma.
2. **Ingresa la IP/dominio y el puerto del servidor** — la IP o dominio público del servidor Traccar (paso 0) y el puerto que te haya dado el administrador (por defecto en este despliegue: `21083`).
3. **Deja TLS desactivado** en este servidor.
4. **Guarda** los cambios de este servidor antes de pasar al siguiente.

### 1.3 Configurar el Servidor JT808 (GPS + ignición)

Este segundo canal manda la posición GPS de forma continua y el estado de ignición (ACC). Suele ser el **Servidor 2**. A diferencia del anterior, **este casi siempre necesita TLS activado** — muchos equipos Streamax vienen así de fábrica y la conexión simplemente no funciona si el servidor no soporta TLS también (justo el problema que este despliegue tuvo que resolver — ver [Problemas comunes](#5-problemas-comunes)).

```
Config Serv → Servidor 2 → Config. red
  IP del servidor .... ‹misma IP/dominio del servidor Traccar›
  Puerto TLS .......... 6556
  Habil. TLS .......... Activado
```

1. **Misma IP/dominio del servidor** que usaste para el Servidor 1 — es el mismo servidor, distinto puerto.
2. **Activa la casilla "Habil. TLS"** y usa el puerto que el equipo muestre para TLS (en este despliegue: `6556`). Si dejas TLS desactivado y el equipo lo trae exigido de fábrica, la conexión no va a establecerse — ni siquiera vas a ver un error claro, simplemente nunca llegan datos.
3. **Guarda y reinicia** el equipo si te lo pide.

### 1.4 Verificar el conteo de pasajeros (APC)

El módulo de conteo (sensores en las puertas) normalmente viene configurado por el instalador del hardware, pero vale la pena confirmarlo antes de dar el equipo por listo.

1. **Busca la sección de recolección de datos** — en el menú, algo como `Conf. → Recol. datos → General` o `Config captura`. Confirma que el conteo de pasajeros esté **activo** y que el número de puertas/canales configurado corresponda a los sensores físicamente instalados en el vehículo.
2. **Anota qué canal de cámara corresponde a qué puerta** — vas a necesitar saber si la puerta delantera es el canal 1 o el 2 (varía por instalación); esto se usa opcionalmente en la Parte 3 si el conteo automático no asigna bien la puerta.

### 1.5 Ignición (ACC) y GPS

Estos dos no se configuran por software en la plataforma — dependen del **cableado físico** del DVR en el vehículo.

- **Ignición:** el DVR tiene un cable de entrada de ACC/ignición que debe conectarse al circuito que se energiza al encender el vehículo. Una vez cableado correctamente, el estado de ignición llega automáticamente en cada reporte de posición por JT808 — no requiere ningún atributo ni configuración adicional en la plataforma.
- **GPS:** requiere que la antena GPS del equipo tenga vista razonable del cielo. Con el equipo conectado y la antena bien instalada, la posición llega sola por JT808 cada pocos segundos — tampoco requiere configuración en la plataforma.

Si al final de esta guía no ves ignición o GPS en la plataforma, el problema casi siempre está en el cableado o la antena del DVR, no en la configuración de la plataforma.

---

## 2. Preparar el servidor

Esta parte la hace una sola vez quien administra el servidor Traccar — no se repite por cada vehículo nuevo.

### 2.1 Generar el certificado TLS para JT808

El canal JT808 necesita un certificado para poder cifrar la conexión. Un certificado autofirmado es suficiente — el equipo no valida la identidad del servidor, solo necesita que la conexión pueda cifrarse.

```
keytool -genkeypair -alias traccar-jt808 -keyalg RSA -keysize 2048 -validity 3650 \
  -keystore jt808-keystore.p12 -storetype PKCS12 \
  -storepass <elige-una-clave> -keypass <elige-una-clave> \
  -dname "CN=traccar-jt808, O=<tu-organizacion>, C=<tu-pais>"
```

> **Importante:** este archivo `.p12` es una clave privada — **no se sube al repositorio de código** (está excluido en `.gitignore`). Se genera una vez por servidor y se guarda solo donde vive el servidor. Si se pierde o se rota, solo hay que regenerarlo con el comando de arriba.

### 2.2 Puertos y configuración del servidor

En el archivo de configuración del servidor (`traccar.xml` en producción, `debug.xml` en desarrollo local):

| Clave | Valor |
|---|---|
| `jt808.port` | Puerto TCP de JT808 — `6556` en este despliegue |
| `jt808.ssl` | `true` — activa TLS en ese puerto |
| `n9m.port` | Puerto TCP del canal de control N9M — `21083` |
| `n9mmedia.port` | Puerto TCP del canal de video N9M — `21720` |
| `n9m.serverHost` | IP/dominio público que el DVR usará para abrir la conexión de video de vuelta — **debe ser alcanzable desde el DVR**, no una IP interna si el equipo está en otra red |
| `decoder.timezone` | Zona horaria para interpretar las marcas de tiempo del equipo, ej. `America/Bogota` |

> **Ojo:** la clave `jt808.timezone` **no tiene ningún efecto** aunque exista en el archivo — el servidor la ignora por completo. La zona horaria real se controla con `decoder.timezone`. Tampoco tiene efecto `jt808.udp.port`: JT808 en este servidor solo escucha por TCP, nunca por UDP.

El keystore generado en 2.1 se carga vía variable de entorno del proceso del servidor (por ejemplo `JAVA_TOOL_OPTIONS` con `-Djavax.net.ssl.keyStore=...`) — quien despliega el servidor ya tiene esto resuelto en el archivo de despliegue; solo hace falta si estás levantando un servidor nuevo desde cero.

### 2.3 Firewall y red

Los tres puertos de esta sección deben estar abiertos hacia el servidor, alcanzables desde donde estén los vehículos (internet si usan SIM, o la red local si es una prueba en sitio):

- `jt808.port` (TCP) — GPS + ignición
- `n9m.port` (TCP) — control de conteo + video
- `n9mmedia.port` (TCP) — el video en sí; se abre solo cuando hay una vista en vivo o descarga activa, pero el puerto debe estar accesible en todo momento

---

## 3. Dar de alta el equipo en la plataforma

Aquí es donde le dices a Traccar "este vehículo existe, y así es como vas a reconocer sus datos". Se hace una vez por vehículo, dentro del navegador, con una cuenta de administrador.

### 3.1 Crear el dispositivo

1. **Entra a Dispositivos** — `Configuración → Dispositivos`, y haz clic en el botón de agregar (ícono **+**).
2. **Llena el nombre y la placa** — cualquier nombre que identifique el vehículo para tu operación (ej. la placa).
3. **Llena el campo "Identificador" (Identifier)** — este campo conecta el dispositivo físico con el registro en la plataforma para el canal **JT808**; debe coincidir exactamente con el ID que el equipo manda al conectarse, que normalmente es su número de terminal (parecido a un número de teléfono, sin ceros a la izquierda).
   - **Cómo confirmarlo:** si no estás seguro del valor exacto, la forma más segura es guardar el dispositivo con cualquier identificador provisional, dejar el equipo intentando conectar, y revisar el registro (log) del servidor — ahí aparece el identificador real que el equipo está mandando la primera vez que intenta conectarse. Ajusta el campo con ese valor exacto.
4. **Guarda** el dispositivo.

### 3.2 Configurar los atributos del dispositivo

Los atributos son los que activan el conteo por N9M y evitan que se duplique con JT808. Se agregan editando el dispositivo recién creado, en su sección de **Atributos** (botón "Agregar atributo" o similar, normalmente como un atributo de tipo texto con nombre y valor libres).

| Atributo | Valor | Obligatorio | Para qué sirve |
|---|---|---|---|
| `mdvrMode` | `n9m` | **Sí** | Le dice a la plataforma que este equipo manda su conteo y video por N9M. Sin esto, el conteo por JT808 no se suprime y se duplica cada evento; y el botón de video usa el flujo equivocado. |
| `n9mSerial` | ‹SERIAL/DSNO del paso 1.2› | **Sí** | Conecta la conexión N9M entrante con este dispositivo. Debe coincidir exacto, tal como aparece en el propio DVR — mayúsculas incluidas. |
| `apc.forceDoor` | `front` ó `rear` | No | Solo si el conteo automático está asignando mal la puerta delantera/trasera del vehículo (ver 1.4). Si no hay problema, no lo agregues — el equipo detecta la puerta solo. |
| `decoder.timezone` | `America/Bogota` | No | Solo si necesitas una zona horaria distinta a la que ya está definida globalmente en el servidor (ver 2.2). Se puede poner por dispositivo o dejar que herede la del servidor. |

> **No necesitas esto:** si viste en algún manual viejo los atributos `mdvrIp`, `mdvrUser` o `mdvrPass` — esos son de un modo distinto (descarga directa por WiFi local) y **no aplican** cuando `mdvrMode=n9m`. No hace falta configurarlos.

### 3.3 Asignar el vehículo a la empresa correcta

Si tu plataforma administra varias empresas/flotas (grupos), asigna el dispositivo al grupo correspondiente desde el mismo formulario de edición del dispositivo — así solo lo ven los usuarios de esa empresa (además de los administradores y supervisores globales).

---

## 4. Verificar que todo funciona

Con el DVR configurado (Parte 1) y el dispositivo dado de alta (Parte 3), esto confirma que los datos realmente están llegando.

### 4.1 Confirmar la conexión N9M

En el mapa principal, el vehículo debería pasar a estado "en línea" y, tras el primer evento real de conteo (alguien sube o baja por una puerta con sensor), debería aparecer un nuevo registro en `Informes → Streamax Eventos de Conteo` (ruta directa: `/reports/counting/events`).

### 4.2 Confirmar la conexión JT808 (GPS + ignición)

En el mapa, el vehículo debe mostrar una posición que se actualiza cada pocos segundos, y el ícono/indicador de ignición debe reflejar el estado real del vehículo (encendido/apagado) si el cableado ACC está bien hecho.

### 4.3 Probar la vista en vivo

Haz clic derecho (o el menú contextual) sobre el vehículo en el mapa y busca la opción **"Live Video"**.

> **Importante:** esta opción solo se habilita **después** de que haya llegado al menos una posición por JT808 — aunque el video en sí viaja por N9M, el botón depende de que JT808 esté reportando. Si lo ves deshabilitado, revisa primero el punto 4.2.

### 4.4 Descargar un clip de un evento de conteo

Desde `Informes → Streamax Eventos de Conteo`, cada fila de evento tiene un botón para descargar el clip de video correspondiente a ese momento. El sistema decide automáticamente usar el canal N9M porque el dispositivo tiene `mdvrMode=n9m` configurado.

### 4.5 Confirmar el consumo de datos SIM

Para verificar cuántos datos está consumiendo el equipo (útil para controlar el plan de datos del SIM): `Configuración → Estado de SIM` (ruta directa: `/settings/sim-status` — visible para roles administrador, administrador de empresa y supervisor).

---

## 5. Problemas comunes

| Síntoma | Causa probable | Qué revisar |
|---|---|---|
| N9M nunca conecta (nunca aparece "en línea" por conteo) | Serial mal escrito, o servidor mal configurado en el DVR | Que `n9mSerial` coincida exacto con lo que muestra el DVR; que el Servidor 1 del DVR apunte a la IP/puerto correctos (1.2) |
| JT808 nunca conecta, sin ningún error visible | El DVR exige TLS pero el servidor no tiene el certificado cargado, o viceversa | Que "Habil. TLS" esté activado en el Servidor 2 del DVR (1.3) **y** que `jt808.ssl=true` con el keystore cargado en el servidor (2.1/2.2) — si solo uno de los dos lados tiene TLS, la conexión simplemente nunca se completa |
| El botón "Live Video" está deshabilitado | Aún no ha llegado ninguna posición por JT808 | Ver 4.2 primero — es un requisito del botón, no un problema real de N9M |
| Cada evento de conteo aparece duplicado | El dispositivo no tiene `mdvrMode=n9m`, así que JT808 también publica el mismo evento | Confirmar el atributo exacto (3.2) — sin él, ambos canales cuentan el mismo pasajero |
| El video se descarga muy lento o trae varios minutos de más | La ventana de tiempo pedida no coincide con una grabación real del equipo | Confirmar que el evento caiga dentro de un horario con grabación en el DVR (revisar calendario de grabación del equipo) |
| No se ve consumo de datos para un equipo en "Estado de SIM" | El equipo aún no se ha conectado desde que el servidor tiene la atribución de datos activa | Esperar a la siguiente conexión/evento del equipo — el contador se actualiza en tiempo real desde ese momento en adelante |

---

## 6. Referencia rápida

| Elemento | Valor / ruta |
|---|---|
| Puerto N9M control | `n9m.port` → 21083 |
| Puerto N9M video | `n9mmedia.port` → 21720 |
| Puerto JT808 (TLS) | `jt808.port` → 6556, `jt808.ssl=true` |
| Zona horaria | `decoder.timezone` (NO `jt808.timezone`) |
| Host público para video | `n9m.serverHost` |
| Atributo modo MDVR | `mdvrMode = n9m` |
| Atributo serial N9M | `n9mSerial = ‹DSNO del equipo›` |
| Atributo puerta forzada (opcional) | `apc.forceDoor = front \| rear` |
| Eventos de conteo | `/reports/counting/events` |
| Vista en vivo | Clic derecho en el vehículo → "Live Video" (requiere posición JT808 reciente) |
| Estado de SIM | `/settings/sim-status` |
