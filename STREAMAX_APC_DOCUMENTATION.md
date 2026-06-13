# Documentación Técnica: Integración Streamax APC (Passenger Counting) en Traccar

Este documento resume el trabajo de ingeniería inversa y personalización realizado para integrar el conteo de pasajeros de un MDVR Streamax utilizando el protocolo JT808 extendido.

## 1. Contexto del Hardware
- **Dispositivo:** MDVR Streamax con cámaras IA de conteo de pasajeros (APC).
- **Protocolo Base:** JT808 (Realiza handshake y ACKs estándar).
- **Identificador:** Terminal Phone ID (BCD) de 10 o 6 bytes.

## 2. Análisis del Protocolo APC
Se identificaron dos fuentes de verdad para la telemetría de conteo:

### A. Trama de Posición Estándar (0x0200)
El MDVR inyecta atributos adicionales (Extensiones) al final de la trama GPS:
- **ID 0x41 (2 bytes):** Acumulado global de entradas (`passengersOn`). Mapeado como `streamax.odometerOn`.
- **ID 0x42 (2 bytes):** Acumulado global de salidas (`passengersOff`). Mapeado como `streamax.odometerOff`.
- **ID 0x30/0x31:** Ignorados (en Streamax no representan conteo real sino estados de IA/Señal).

### B. Mensajes Propietarios de Eventos (0x0B02)
Reportes instantáneos generados físicamente al detectar movimiento en las puertas:
- **Estructura Útil (12 bytes al final):** `[BCD Date (6)] [DoorID (1)] [On (2)] [Type (1)] [Off (2)]`.
- **DoorId 0 o 1:** Puerta Delantera (Sufijo `Front`).
- **DoorId 2:** Puerta Trasera (Sufijo `Rear`).
- **Valores:** Se detectó un comportamiento de "flags" (ej. 257 en lugar de 1), solucionado aislando el byte bajo mediante `getUnsignedByte`.

## 3. Implementación en el Backend (Java)
Archivo: `Jt808ProtocolDecoder.java`

### Mejoras Críticas:
1.  **BCD Date Fix:** Uso de `buf.slice(startIndex, 6)` para evitar desalineación del puntero de lectura global y prevenir `IndexOutOfBoundsException`.
2.  **Lógica de Memoria (Persistence):**
    - Implementación de consulta al `CacheManager` (Traccar 6.x).
    - **Software Odometer Priority:** El decoder prioriza la llave `passengersOn` calculada por Traccar sobre `streamax.odometerOn` reportada por el hardware en 0x0200.
    - Esto evita que los reportes GPS periódicos (con valores estáticos) sobreescriban los incrementos reales detectados en los eventos 0x0B02.
    - El mensaje 0x0B19 (Estadístico) actúa como reporte de estado sin forzar re-cálculos si ya existe una posición reciente.
3.  **Retorno Inteligente:** Para compatibilidad con tests unitarios, el método `decode` devuelve una `Position` directa si el tamaño de la lista es 1, o la `List` completa si es un reporte por lotes.

## 4. Configuración del Servidor
Archivo: `traccar.xml`

Para asegurar que los datos no se pierdan entre reportes GPS (0x0200) y eventos (0x0B02), se activó la copia de atributos:
```xml
<entry key='processing.copyAttributes'>
    passengersOn passengersOff 
    passengersOnFront passengersOffFront 
    passengersOnRear passengersOffRear
</entry>
```

## 5. Visualización (Frontend)
Componente: `GeneralCountingPage.jsx`
- **Ruta:** `/reports/counting`.
- **Funcionalidad:** Relaciona placas de vehículos con contadores desglosados por puerta y totales consolidados.
- **Periodos:** Filtros por Día, Semana y Mes mediante consultas a `/api/reports/summary`.

## 6. Pruebas Unitarias
Archivo: `Jt808ProtocolDecoderTest.java`
- **Ajuste de Tipos:** Se corrigió el error de inferencia de tipo cambiando `var` por `Position` y realizando casting explícito en las llamadas a `decoder.decode`.
- **Validación:** Se utiliza `org.junit.jupiter.api.Assertions.assertEquals` para verificar atributos dinámicos como `passengersOnFront`.

---
**Estado Actual:** Compilación exitosa (BUILD SUCCESSFUL) y persistencia operativa en PostgreSQL.
```bash
.\gradlew.bat clean test --tests Jt808ProtocolDecoderTest
```
*Última actualización: 19 de Mayo de 2026*