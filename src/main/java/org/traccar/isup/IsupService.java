/*
 * Servicio que consume el gateway ISUP (sidecar) y refleja el estado online de
 * las cámaras Hikvision registradas por ISUP en los dispositivos de Traccar.
 *
 * El gateway (proceso aparte, con la JNA/SDK nativo) expone GET /devices con la
 * lista de deviceIDs registrados y su estado. Aquí (Java puro, sin JNA) se sondea
 * ese endpoint y se mapea cada deviceID ISUP al dispositivo Traccar cuyo atributo
 * "isupDeviceId" coincide, actualizando su estado de conexión.
 */
package org.traccar.isup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.Device;
import org.traccar.session.ConnectionManager;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Request;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collection;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Singleton
public class IsupService {

    private static final Logger LOGGER = LoggerFactory.getLogger(IsupService.class);
    private static final String DEVICE_ATTRIBUTE = "isupDeviceId";
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 10_000;

    private final Storage storage;
    private final ConnectionManager connectionManager;
    private final ObjectMapper objectMapper;
    private final String gatewayUrl;

    @Inject
    public IsupService(
            Config config, Storage storage, ConnectionManager connectionManager, ObjectMapper objectMapper) {
        this.storage = storage;
        this.connectionManager = connectionManager;
        this.objectMapper = objectMapper;
        this.gatewayUrl = config.getString(Keys.ISUP_GATEWAY_URL);

        if (config.getBoolean(Keys.ISUP_ENABLE)) {
            int interval = config.getInteger(Keys.ISUP_POLL_INTERVAL);
            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "isup-poll");
                t.setDaemon(true);
                return t;
            });
            executor.scheduleAtFixedRate(this::poll, 15, interval, TimeUnit.SECONDS);
            LOGGER.info("ISUP service habilitado, gateway={} intervalo={}s", gatewayUrl, interval);
        }
    }

    private void poll() {
        try {
            JsonNode devices = fetchDevices();
            if (devices == null || !devices.isArray()) {
                return;
            }
            for (JsonNode entry : devices) {
                String isupId = entry.path("deviceId").asText(null);
                boolean online = entry.path("online").asBoolean(false);
                if (isupId == null || isupId.isEmpty()) {
                    continue;
                }
                Device device = findDeviceByIsupId(isupId);
                if (device != null) {
                    connectionManager.updateDevice(
                            device.getId(),
                            online ? Device.STATUS_ONLINE : Device.STATUS_OFFLINE,
                            new Date());
                }
            }
        } catch (Exception e) {
            LOGGER.warn("ISUP poll error: {}", e.getMessage());
        }
    }

    private JsonNode fetchDevices() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(gatewayUrl + "/devices").openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.connect();
        try (InputStream is = conn.getInputStream()) {
            return objectMapper.readTree(is);
        } finally {
            conn.disconnect();
        }
    }

    private Device findDeviceByIsupId(String isupId) throws Exception {
        Collection<Device> devices = storage.getObjects(Device.class, new Request(new Columns.All()));
        for (Device device : devices) {
            if (isupId.equals(device.getString(DEVICE_ATTRIBUTE))) {
                return device;
            }
        }
        return null;
    }
}
