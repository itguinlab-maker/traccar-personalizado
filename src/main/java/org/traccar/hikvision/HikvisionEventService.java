package org.traccar.hikvision;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

@Singleton
public class HikvisionEventService {

    private static final Logger LOGGER = LoggerFactory.getLogger(HikvisionEventService.class);
    private static final String DATA_FILE = "./data/hikvision_events.json";
    private static final int MAX_EVENTS = 5000;

    private final ObjectMapper objectMapper;
    private final List<HikvisionEvent> events = new CopyOnWriteArrayList<>();

    @Inject
    public HikvisionEventService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        load();
    }

    public HikvisionEvent save(HikvisionEvent event) {
        event.setId(UUID.randomUUID().toString());
        events.add(event);
        if (events.size() > MAX_EVENTS) {
            events.remove(0);
        }
        persist();
        return event;
    }

    public List<HikvisionEvent> getAll() {
        List<HikvisionEvent> copy = new ArrayList<>(events);
        copy.sort(Comparator.comparing(HikvisionEvent::getDateTime).reversed());
        return copy;
    }

    public List<HikvisionEvent> getByDevice(long deviceId) {
        return getByDevice(deviceId, null, null);
    }

    public List<HikvisionEvent> getByDevice(long deviceId, Instant from, Instant to) {
        List<HikvisionEvent> result = new ArrayList<>();
        for (HikvisionEvent e : events) {
            if (e.getDeviceId() != deviceId) {
                continue;
            }
            if (from != null || to != null) {
                try {
                    Instant t = parseEventTime(e.getDateTime());
                    if (t == null) {
                        continue;
                    }
                    if (from != null && t.isBefore(from)) {
                        continue;
                    }
                    if (to != null && t.isAfter(to)) {
                        continue;
                    }
                } catch (Exception ignored) {
                    continue;
                }
            }
            result.add(e);
        }
        result.sort(Comparator.comparing(HikvisionEvent::getDateTime).reversed());
        return result;
    }

    private static Instant parseEventTime(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        // Already correct UTC
        if (s.endsWith("Z") || s.endsWith("z")) {
            try {
                return Instant.parse(s);
            } catch (Exception ignored) {
            }
        }
        // Camera bug: UTC value with local offset appended — correct by adding abs(offset)
        String norm = s.replaceAll("([+-])(\\d:)", "$10$2");
        try {
            OffsetDateTime odt = OffsetDateTime.parse(norm);
            long absOffsetSec = Math.abs(odt.getOffset().getTotalSeconds());
            return odt.toInstant().plusSeconds(absOffsetSec);
        } catch (Exception ignored) { }
        return null;
    }

    private void load() {
        File file = new File(DATA_FILE);
        if (!file.exists()) {
            return;
        }
        try {
            List<HikvisionEvent> loaded = objectMapper.readValue(file, new TypeReference<List<HikvisionEvent>>() { });
            events.addAll(loaded);
            LOGGER.info("HIK loaded {} events from {}", loaded.size(), DATA_FILE);
        } catch (Exception e) {
            LOGGER.error("HIK failed to load {}: {}", DATA_FILE, e.getMessage());
        }
    }

    private synchronized void persist() {
        try {
            File file = new File(DATA_FILE);
            file.getParentFile().mkdirs();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, new ArrayList<>(events));
        } catch (Exception e) {
            LOGGER.error("HIK failed to save {}: {}", DATA_FILE, e.getMessage());
        }
    }
}
