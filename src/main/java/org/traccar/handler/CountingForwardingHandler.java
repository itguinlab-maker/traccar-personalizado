package org.traccar.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.forwarding.ExternalForwardingService;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runs after {@link DatabaseHandler} in the position pipeline.
 *
 * When a position represents a counting event it builds the standard payload:
 * <pre>
 * {
 *   "placa":         "FAQ123",
 *   "puerta":        1,
 *   "subidas":       3,
 *   "bajadas":       2,
 *   "ubicacion":     {"latitud": 6.244, "longitud": -75.581},
 *   "tiempo_evento": "2026-05-27T14:39:08Z"
 * }
 * </pre>
 *
 * The {@code placa} is read from the device attribute {@code placa};
 * if absent it falls back to the device name.
 *
 * The job is enqueued in {@link ExternalForwardingService} which handles
 * async dispatch and exponential-backoff retry.
 */
public class CountingForwardingHandler extends BasePositionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(CountingForwardingHandler.class);
    private static final DateTimeFormatter ISO_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private final ExternalForwardingService forwardingService;
    private final CacheManager cacheManager;
    private final ObjectMapper mapper;

    @Inject
    public CountingForwardingHandler(
            ExternalForwardingService forwardingService,
            CacheManager cacheManager,
            ObjectMapper mapper) {
        this.forwardingService = forwardingService;
        this.cacheManager = cacheManager;
        this.mapper = mapper;
    }

    @Override
    public void onPosition(Position position, Callback callback) {
        try {
            Object status = position.getAttributes().get("streamax.status");
            if ("counting_event".equals(status)) {
                int subidas   = toInt(position.getAttributes().get("passengersOn"));
                int bajadas   = toInt(position.getAttributes().get("passengersOff"));
                if (subidas > 0 || bajadas > 0) {
                    enqueue(position, subidas, bajadas);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("CountingForwardingHandler error: {}", e.getMessage());
        } finally {
            callback.processed(false);
        }
    }

    private void enqueue(Position position, int subidas, int bajadas) {
        Device device = cacheManager.getObject(Device.class, position.getDeviceId());
        String placa = (device != null)
                ? device.getString("placa", device.getName())
                : String.valueOf(position.getDeviceId());

        int doorId = toInt(position.getAttributes().get("streamax.doorId"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("placa",   placa);
        payload.put("puerta",  doorId + 1);
        payload.put("subidas", subidas);
        payload.put("bajadas", bajadas);

        Map<String, Object> ubicacion = new LinkedHashMap<>();
        ubicacion.put("latitud",   position.getLatitude());
        ubicacion.put("longitud",  position.getLongitude());
        payload.put("ubicacion", ubicacion);

        Instant fixTime = position.getFixTime() != null
                ? position.getFixTime().toInstant()
                : Instant.now();
        payload.put("tiempo_evento", ISO_UTC.format(fixTime));

        try {
            String json = mapper.writeValueAsString(payload);
            forwardingService.enqueue(position.getDeviceId(), placa, json);
        } catch (Exception e) {
            LOGGER.warn("CountingForwardingHandler failed to serialize payload: {}", e.getMessage());
        }
    }

    private static int toInt(Object val) {
        return (val instanceof Number n) ? n.intValue() : 0;
    }
}
