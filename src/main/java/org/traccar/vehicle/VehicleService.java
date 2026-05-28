package org.traccar.vehicle;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.forwarding.ExternalForwardingManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Singleton
public class VehicleService {

    private static final Logger LOGGER = LoggerFactory.getLogger(VehicleService.class);
    private static final String DATA_FILE = "./data/vehicle_records.json";

    private final ObjectMapper objectMapper;
    private final ExternalForwardingManager forwardingManager;

    // id → record
    private final Map<String, VehicleRecord> records = new ConcurrentHashMap<>();
    // deviceId → recordId  (only entries with deviceId > 0)
    private final Map<Long, String> deviceIndex = new ConcurrentHashMap<>();

    @Inject
    public VehicleService(ObjectMapper objectMapper, ExternalForwardingManager forwardingManager) {
        this.objectMapper = objectMapper;
        this.forwardingManager = forwardingManager;
        load();
    }

    public List<VehicleRecord> getAll() {
        return new ArrayList<>(records.values());
    }

    public Optional<VehicleRecord> getByDeviceId(long deviceId) {
        String id = deviceIndex.get(deviceId);
        return id != null ? Optional.ofNullable(records.get(id)) : Optional.empty();
    }

    public VehicleRecord create(VehicleRecord record) {
        record.setId(UUID.randomUUID().toString());
        records.put(record.getId(), record);
        if (record.getDeviceId() > 0) {
            deviceIndex.put(record.getDeviceId(), record.getId());
        }
        save();
        syncForwardingGroup(record.getCompany());
        return record;
    }

    public Optional<VehicleRecord> update(String id, VehicleRecord updated) {
        VehicleRecord existing = records.get(id);
        if (existing == null) {
            return Optional.empty();
        }
        // Update device index
        if (existing.getDeviceId() > 0) {
            deviceIndex.remove(existing.getDeviceId());
        }
        String oldCompany = existing.getCompany();

        updated.setId(id);
        records.put(id, updated);
        if (updated.getDeviceId() > 0) {
            deviceIndex.put(updated.getDeviceId(), id);
        }
        save();

        // Sync forwarding groups for old company and (if changed) new company
        syncForwardingGroup(oldCompany);
        if (updated.getCompany() != null && !updated.getCompany().equals(oldCompany)) {
            syncForwardingGroup(updated.getCompany());
        }
        return Optional.of(updated);
    }

    public boolean delete(String id) {
        VehicleRecord record = records.remove(id);
        if (record == null) {
            return false;
        }
        if (record.getDeviceId() > 0) {
            deviceIndex.remove(record.getDeviceId());
        }
        save();
        syncForwardingGroup(record.getCompany());
        return true;
    }

    private void syncForwardingGroup(String company) {
        if (company == null || company.isBlank()) {
            return;
        }
        Set<Long> deviceIds = records.values().stream()
                .filter(r -> company.equals(r.getCompany()) && r.getDeviceId() > 0)
                .map(VehicleRecord::getDeviceId)
                .collect(Collectors.toSet());
        forwardingManager.syncCompanyGroup(company, deviceIds);
    }

    private void load() {
        File file = new File(DATA_FILE);
        if (!file.exists()) {
            return;
        }
        try {
            List<VehicleRecord> list = objectMapper.readValue(file, new TypeReference<List<VehicleRecord>>() { });
            list.forEach(r -> {
                records.put(r.getId(), r);
                if (r.getDeviceId() > 0) {
                    deviceIndex.put(r.getDeviceId(), r.getId());
                }
            });
            LOGGER.info("VEHICLE loaded {} record(s) from {}", records.size(), DATA_FILE);
        } catch (Exception e) {
            LOGGER.error("VEHICLE failed to load {}: {}", DATA_FILE, e.getMessage());
        }
    }

    private synchronized void save() {
        try {
            File file = new File(DATA_FILE);
            file.getParentFile().mkdirs();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, new ArrayList<>(records.values()));
        } catch (Exception e) {
            LOGGER.error("VEHICLE failed to save {}: {}", DATA_FILE, e.getMessage());
        }
    }
}
