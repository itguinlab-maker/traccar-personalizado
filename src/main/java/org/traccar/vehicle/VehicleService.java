package org.traccar.vehicle;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.forwarding.ExternalForwardingManager;
import org.traccar.model.Device;
import org.traccar.model.Group;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Singleton
public class VehicleService {

    private static final Logger LOGGER = LoggerFactory.getLogger(VehicleService.class);
    private static final String DATA_FILE = "./data/vehicle_records.json";

    private final ObjectMapper objectMapper;
    private final ExternalForwardingManager forwardingManager;
    private final Storage storage;

    // id → record
    private final Map<String, VehicleRecord> records = new ConcurrentHashMap<>();
    // deviceId → recordId  (only entries with deviceId > 0)
    private final Map<Long, String> deviceIndex = new ConcurrentHashMap<>();

    @Inject
    public VehicleService(ObjectMapper objectMapper, ExternalForwardingManager forwardingManager, Storage storage) {
        this.objectMapper = objectMapper;
        this.forwardingManager = forwardingManager;
        this.storage = storage;
        load();
    }

    public List<VehicleRecord> getAll() {
        return new ArrayList<>(records.values());
    }

    public List<VehicleRecord> getByCompany(String company) {
        if (company == null || company.isBlank()) {
            return getAll();
        }
        return records.values().stream()
                .filter(r -> company.equals(r.getCompany()))
                .collect(Collectors.toList());
    }

    public Optional<VehicleRecord> getByDeviceId(long deviceId) {
        String id = deviceIndex.get(deviceId);
        return id != null ? Optional.ofNullable(records.get(id)) : Optional.empty();
    }

    public VehicleRecord create(VehicleRecord record, boolean adminMode) {
        record.setId(UUID.randomUUID().toString());
        records.put(record.getId(), record);
        if (record.getDeviceId() > 0) {
            deviceIndex.put(record.getDeviceId(), record.getId());
        }
        if (record.getRearDeviceId() > 0) {
            deviceIndex.put(record.getRearDeviceId(), record.getId());
        }
        save();
        syncForwardingGroup(record.getCompany(), adminMode);
        syncDeviceGroup(record.getDeviceId(), record.getCompany());
        syncDeviceGroup(record.getRearDeviceId(), record.getCompany());
        return record;
    }

    public Optional<VehicleRecord> update(String id, VehicleRecord updated, boolean adminMode) {
        VehicleRecord existing = records.get(id);
        if (existing == null) {
            return Optional.empty();
        }
        if (existing.getDeviceId() > 0) {
            deviceIndex.remove(existing.getDeviceId());
        }
        if (existing.getRearDeviceId() > 0) {
            deviceIndex.remove(existing.getRearDeviceId());
        }
        String oldCompany = existing.getCompany();
        long oldDeviceId = existing.getDeviceId();
        long oldRearDeviceId = existing.getRearDeviceId();

        updated.setId(id);
        records.put(id, updated);
        if (updated.getDeviceId() > 0) {
            deviceIndex.put(updated.getDeviceId(), id);
        }
        if (updated.getRearDeviceId() > 0) {
            deviceIndex.put(updated.getRearDeviceId(), id);
        }
        save();

        syncForwardingGroup(oldCompany, adminMode);
        if (updated.getCompany() != null && !updated.getCompany().equals(oldCompany)) {
            syncForwardingGroup(updated.getCompany(), adminMode);
        }

        if (oldDeviceId > 0
                && (oldDeviceId != updated.getDeviceId() || !Objects.equals(oldCompany, updated.getCompany()))) {
            syncDeviceGroup(oldDeviceId, null);
        }
        if (oldRearDeviceId > 0
                && (oldRearDeviceId != updated.getRearDeviceId()
                        || !Objects.equals(oldCompany, updated.getCompany()))) {
            syncDeviceGroup(oldRearDeviceId, null);
        }
        syncDeviceGroup(updated.getDeviceId(), updated.getCompany());
        syncDeviceGroup(updated.getRearDeviceId(), updated.getCompany());
        return Optional.of(updated);
    }

    public boolean delete(String id, boolean adminMode) {
        VehicleRecord record = records.remove(id);
        if (record == null) {
            return false;
        }
        if (record.getDeviceId() > 0) {
            deviceIndex.remove(record.getDeviceId());
        }
        if (record.getRearDeviceId() > 0) {
            deviceIndex.remove(record.getRearDeviceId());
        }
        save();
        syncForwardingGroup(record.getCompany(), adminMode);
        syncDeviceGroup(record.getDeviceId(), null);
        syncDeviceGroup(record.getRearDeviceId(), null);
        return true;
    }

    // Asigna el dispositivo al grupo de Traccar cuyo nombre coincide con la empresa.
    // Si company es null o vacío, limpia el groupId (queda sin grupo).
    private void syncDeviceGroup(long deviceId, String company) {
        if (deviceId <= 0) {
            return;
        }
        try {
            long groupId = 0;
            if (company != null && !company.isBlank()) {
                List<Group> groups = storage.getObjects(Group.class, new Request(
                        new Columns.All(),
                        new Condition.Equals("name", company)));
                if (!groups.isEmpty()) {
                    groupId = groups.get(0).getId();
                } else {
                    LOGGER.warn("VEHICLE no existe grupo de Traccar con nombre '{}', el dispositivo {} no se asignará",
                            company, deviceId);
                    return;
                }
            }
            Device device = new Device();
            device.setId(deviceId);
            device.setGroupId(groupId);
            storage.updateObject(device, new Request(
                    new Columns.Include("groupId"),
                    new Condition.Equals("id", deviceId)));
            LOGGER.info("VEHICLE dispositivo {} asignado a groupId={} (empresa='{}')", deviceId, groupId, company);
        } catch (StorageException e) {
            LOGGER.warn("VEHICLE error al sincronizar groupId del dispositivo {}: {}", deviceId, e.getMessage());
        }
    }

    private void syncForwardingGroup(String company, boolean createIfMissing) {
        if (company == null || company.isBlank()) {
            return;
        }
        Set<Long> deviceIds = records.values().stream()
                .filter(r -> company.equals(r.getCompany()) && r.getDeviceId() > 0)
                .map(VehicleRecord::getDeviceId)
                .collect(Collectors.toSet());
        forwardingManager.syncCompanyGroup(company, deviceIds, createIfMissing);
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
