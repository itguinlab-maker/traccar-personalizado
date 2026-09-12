# Nodiklab CCTV — Resumen de la plataforma y resultados del DEMO

Documento interno de síntesis: qué se construyó, cómo funciona y qué arrojó la prueba en campo.
No es material de venta — los números son los reales, incluidos los que salieron mal.

---

## 1. Qué ofrece la plataforma

Una plataforma web multiempresa que toma los datos del equipo de a bordo de cada bus y los
presenta en un solo lugar: posición, conteo de pasajeros y video, con el video atado al momento
exacto de cada evento.

| Capacidad | Estado | Cómo funciona |
|---|---|---|
| GPS continuo + ignición | Operativo | JT808 sobre TCP con TLS, reporte cada ~10 s |
| Conteo de pasajeros por puerta | Operativo | N9M (`UPPSTATISTICS`), con el GPS del instante del evento |
| Video en vivo | Operativo | N9M: la plataforma pide el stream, el equipo lo devuelve |
| Descarga de video por evento | Operativo | Se ubica el segmento real grabado y se baja solo la ventana del evento |
| Consumo de datos SIM | Operativo | Conteo de bytes por socket, atribuido a cada equipo |
| Multiempresa y roles | Operativo | Cada empresa es un grupo; el usuario solo ve lo suyo |
| Cámaras Hikvision | Operativo | Conteo por push HTTP o consulta ISAPI; video por RTSP |

### Lo que hizo falta construir

El equipo no hablaba ningún protocolo de video que la plataforma soportara: **JT1078 está muerto
en este hardware** — el MDVR ignora los comandos `0x9202`/`0x9205`. La solución fue reversar por
captura de tráfico el protocolo propietario **N9M** de Streamax (JSON sobre TCP) y construir
desde cero el decoder, el canal de medios y el enrutamiento de comandos.

Resultado: login, keepalive, conteo con GPS, vista en vivo y descarga de video histórico
funcionando sin depender de la plataforma del fabricante.

---

## 2. Integración de cámaras

Dos caminos, según el hardware del vehículo:

**Streamax (MDVR centralizado)** — un equipo maneja todas las cámaras del bus. Reporta conteo y
video por N9M (puertos 21083 control / 21720 medios) y GPS+ignición por JT808 (puerto 6556 con
TLS). Ambos canales corren en paralelo; la plataforma evita contar dos veces el mismo evento.

**Hikvision (cámara por puerta)** — cada cámara cuenta su propia puerta. El conteo llega por dos
mecanismos simultáneos: la cámara empuja el evento por HTTP, y la plataforma además la consulta
periódicamente por ISAPI. El video se baja por RTSP directo.

En ambos casos **el equipo abre la conexión hacia el servidor**, no al revés. Por eso funciona
con SIM celular sin IP pública fija en el vehículo.

---

## 3. Descarga de video

El flujo completo, tal como quedó:

1. El usuario elige un evento de conteo en el reporte.
2. La plataforma consulta al MDVR qué segmentos tiene grabados ese día (`QUERYFILELIST`).
3. Ubica el segmento real que contiene ese instante y acota la ventana al evento.
4. Pide la reproducción (`REQUESTREMOTEPLAYBACK`); el equipo abre la conexión de video de vuelta.
5. Se corta al llegar a los cuadros necesarios y se convierte a MP4.

**Por qué importa el paso 3**: el equipo ignora la hora de fin que se le pide y transmite mucho
más rápido que tiempo real. Sin el corte por cantidad de cuadros, una descarga se llevaba
minutos de video innecesarios — se midieron clips de 15 a 275 MB. Con el corte, un clip de ~85 s
pesa lo que debe pesar.

La ventana quedó en 60 s antes y 25 s después del evento.

---

## 4. Resultados del DEMO

### Periodo y alcance

- **Vehículo probado:** 36 (equipo FWK932, serial `00E4006A50`)
- **Datos del cliente:** informe de recaudo, 16 jun – 15 jul (30 días)
- **Datos de la plataforma:** eventos de conteo registrados desde el 3 de julio
- **Periodo comparable:** 3 – 15 de julio (13 días de solape)

### Comparación día a día

| Día (julio) | Plataforma | Cliente | Cobertura |
|---:|---:|---:|---:|
| 3 | 223 | 456 | 49 % |
| 4 | 5 | 142 | 4 % |
| 5 | 116 | 367 | 32 % |
| 6 | 273 | 444 | 61 % |
| 7 | 199 | 368 | 54 % |
| 8 | 145 | 354 | 41 % |
| 9 | 104 | 412 | 25 % |
| 10 | 115 | 464 | 25 % |
| 11 | 39 | 417 | 9 % |
| 12 | 565 | 0 | — |
| 13 | 83 | 0 | — |
| 14 | 100 | 280 | 36 % |
| 15 | 218 | 237 | 92 % |
| **Total** | **2 185** | **3 941** | **55 %** |

Referencia del periodo completo del cliente (16 jun – 15 jul): **8 712 pasajeros** en 30 días,
de los cuales 4 días figuran en cero.

### Lectura honesta de estos números

**La plataforma registró alrededor de la mitad de los pasajeros que reportó el cliente.** No es
un resultado presentable como éxito de precisión, y conviene entender por qué antes de sacar
conclusiones:

1. **Las dos cifras no miden lo mismo.** El cliente reporta **recaudo** (pasajeros que pagaron,
   por tipo de tarifa); la plataforma cuenta **personas cruzando la puerta** con visión. Un
   pasajero integrado, un menor o alguien que no valida siguen siendo personas contadas, o
   personas no cobradas. No debe esperarse coincidencia exacta ni siquiera con el sensor perfecto.

2. **Los días 12 y 13 de julio invierten la relación**: la plataforma registró 565 y 83 mientras
   el cliente reporta 0. Eso confirma que el informe del cliente tiene días sin operación
   registrada (o sin recaudo) en los que el vehículo sí se movió y contó.

3. **La cobertura es muy irregular** (del 4 % al 92 %). Esa dispersión no se explica por la
   diferencia conceptual entre recaudo y conteo: apunta a **pérdida de eventos**, no a un sesgo
   constante. Los días bajos coinciden con periodos donde el equipo estuvo desconectado o el
   canal N9M no estuvo activo durante toda la jornada.

4. **Bajadas prácticamente sin registrar**: 3 088 subidas contra 84 bajadas en todo el histórico.
   El sensor de la puerta de salida no está contando, o su canal no está mapeado. Esto por sí
   solo invalida cualquier cálculo de ocupación a bordo.

### Qué sí quedó demostrado

- El canal N9M entrega conteo con GPS del instante exacto, de forma sostenida (1 551 eventos).
- El video histórico se baja atado al evento, con el segmento real de grabación.
- La vista en vivo funciona sobre SIM celular sin IP pública.
- JT808 y N9M conviven sin duplicar eventos de conteo.

### Qué hay que resolver antes de una venta seria

| Prioridad | Problema | Acción |
|---|---|---|
| Crítica | Bajadas no se registran (84 vs 3 088) | Revisar sensor/mapeo de la puerta de salida en el MDVR |
| Crítica | Cobertura irregular por desconexiones | Medir disponibilidad real del equipo y cerrar los huecos |
| Alta | No hay línea base de precisión | Conteo manual contra plataforma en una jornada controlada |
| Media | Comparar contra recaudo induce a error | Definir con el cliente qué métrica se va a comparar |

**Recomendación:** antes de presentar cifras de precisión a un cliente, hacer una jornada de
validación con conteo manual como referencia. Comparar contra recaudo mezcla dos métricas
distintas y deja la conversación en terreno débil.

---

## 5. Arquitectura en una línea

```
Cámaras → MDVR/cámara → SIM celular o WiFi → Gateway Nodiklab
   → decodifica N9M / JT808 / ISAPI → PostgreSQL → plataforma web
```

Despliegue en Docker: dos contenedores (aplicación + PostgreSQL). Puertos 8082 web, 6556 JT808
con TLS, 21083 y 21720 N9M, 8400 JT1078.
