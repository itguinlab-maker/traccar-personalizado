package org.traccar.datausage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Contador de datos transmitidos por dispositivo. Acumula bytes por trama
 * recibida y persiste periodicamente a JSON en el volumen de datos.
 */
@Singleton
public class DataUsageManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(DataUsageManager.class);
    private static final String DATA_FILE = "./data/data_usage.json";
    private static final long SAVE_INTERVAL_MS = 60_000;

    private final ObjectMapper objectMapper;
    private final Map<Long, DeviceDataUsage> usage = new ConcurrentHashMap<>();
    private volatile long lastSave;

    @Inject
    public DataUsageManager(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        load();
    }

    /**
     * Acredita al dispositivo los bytes pendientes acumulados en el canal por
     * {@link DataUsageChannelHandler} (ambas direcciones) y marca el canal
     * para el flush final al cerrarse la conexion.
     */
    public void flushChannel(Channel channel, long deviceId, boolean countMessage) {
        channel.attr(DataUsageChannelHandler.DEVICE_ID).set(deviceId);
        long rx = getAndReset(channel, DataUsageChannelHandler.PENDING_RX);
        long tx = getAndReset(channel, DataUsageChannelHandler.PENDING_TX);
        if (rx > 0 || tx > 0 || countMessage) {
            apply(deviceId, rx, tx, countMessage ? 1 : 0);
        }
    }

    private static long getAndReset(Channel channel, AttributeKey<AtomicLong> key) {
        AtomicLong counter = channel.attr(key).get();
        return counter != null ? counter.getAndSet(0) : 0;
    }

    private void apply(long deviceId, long rxBytes, long txBytes, int messages) {
        DeviceDataUsage entry = usage.computeIfAbsent(deviceId, id -> {
            DeviceDataUsage created = new DeviceDataUsage();
            created.setDeviceId(id);
            return created;
        });
        synchronized (entry) {
            entry.setTotalReceivedBytes(entry.getTotalReceivedBytes() + rxBytes);
            entry.setTotalSentBytes(entry.getTotalSentBytes() + txBytes);
            entry.setTotalBytes(entry.getTotalBytes() + rxBytes + txBytes);
            entry.setTotalMessages(entry.getTotalMessages() + messages);
            if (rxBytes > 0) {
                entry.setLastReceived(System.currentTimeMillis());
            }
            String month = LocalDate.now(ZoneOffset.UTC).toString().substring(0, 7);
            entry.getMonthlyBytes().merge(month, rxBytes + txBytes, Long::sum);
        }
        long now = System.currentTimeMillis();
        if (now - lastSave > SAVE_INTERVAL_MS) {
            lastSave = now;
            save();
        }
    }

    public List<DeviceDataUsage> getAll() {
        return new ArrayList<>(usage.values());
    }

    private void load() {
        File file = new File(DATA_FILE);
        if (!file.exists()) {
            return;
        }
        try {
            List<DeviceDataUsage> list = objectMapper.readValue(file, new TypeReference<List<DeviceDataUsage>>() { });
            list.forEach(u -> usage.put(u.getDeviceId(), u));
            LOGGER.info("DATAUSAGE loaded {} device(s) from {}", usage.size(), DATA_FILE);
        } catch (Exception e) {
            LOGGER.error("DATAUSAGE failed to load {}: {}", DATA_FILE, e.getMessage());
        }
    }

    private synchronized void save() {
        try {
            File file = new File(DATA_FILE);
            file.getParentFile().mkdirs();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, new ArrayList<>(usage.values()));
        } catch (Exception e) {
            LOGGER.error("DATAUSAGE failed to save {}: {}", DATA_FILE, e.getMessage());
        }
    }
}
