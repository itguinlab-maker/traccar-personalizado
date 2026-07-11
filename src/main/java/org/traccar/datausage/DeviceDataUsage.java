package org.traccar.datausage;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Acumulado de datos transmitidos por un dispositivo a la plataforma.
 * Los bytes se cuentan a nivel de trama decodificada (aproximación del
 * consumo de la SIM sin el overhead TCP/IP).
 */
public class DeviceDataUsage {

    private long deviceId;
    private long totalBytes;
    private long totalMessages;
    private long lastReceived;
    private Map<String, Long> monthlyBytes = new ConcurrentHashMap<>();

    public long getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(long deviceId) {
        this.deviceId = deviceId;
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    public void setTotalBytes(long totalBytes) {
        this.totalBytes = totalBytes;
    }

    public long getTotalMessages() {
        return totalMessages;
    }

    public void setTotalMessages(long totalMessages) {
        this.totalMessages = totalMessages;
    }

    public long getLastReceived() {
        return lastReceived;
    }

    public void setLastReceived(long lastReceived) {
        this.lastReceived = lastReceived;
    }

    public Map<String, Long> getMonthlyBytes() {
        return monthlyBytes;
    }

    public void setMonthlyBytes(Map<String, Long> monthlyBytes) {
        this.monthlyBytes = monthlyBytes != null ? new ConcurrentHashMap<>(monthlyBytes) : new ConcurrentHashMap<>();
    }
}
