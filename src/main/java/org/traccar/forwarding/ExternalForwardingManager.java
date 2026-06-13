/*
 * Copyright 2026 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.forwarding;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.model.ForwardingGroup;
import org.traccar.model.Position;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages forwarding groups and dispatches counting events to external REST servers.
 * Groups are persisted to {@code ./data/forwarding_groups.json}.
 */
@Singleton
public class ExternalForwardingManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExternalForwardingManager.class);
    private static final String DATA_FILE = "./data/forwarding_groups.json";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    // id → group
    private final Map<String, ForwardingGroup> groups = new ConcurrentHashMap<>();
    // deviceId → groupId  (fast lookup for forwarding)
    private final Map<Long, String> deviceIndex = new ConcurrentHashMap<>();

    @Inject
    public ExternalForwardingManager(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        load();
    }

    // ------------------------------------------------------------------ CRUD

    public List<ForwardingGroup> getAll() {
        return new ArrayList<>(groups.values());
    }

    public Optional<ForwardingGroup> getById(String id) {
        return Optional.ofNullable(groups.get(id));
    }

    /**
     * Creates a new group. Devices already assigned to another group are silently skipped.
     */
    public ForwardingGroup create(ForwardingGroup group) {
        group.setId(UUID.randomUUID().toString());
        sanitizeDeviceIds(group);
        groups.put(group.getId(), group);
        indexDevices(group);
        save();
        return group;
    }

    /**
     * Replaces an existing group. Devices claimed by other groups are silently excluded.
     */
    public Optional<ForwardingGroup> update(String id, ForwardingGroup updated) {
        ForwardingGroup existing = groups.get(id);
        if (existing == null) {
            return Optional.empty();
        }
        // Remove old device index entries for this group
        existing.getDeviceIds().forEach(deviceId -> deviceIndex.remove(deviceId, id));

        updated.setId(id);
        sanitizeDeviceIds(updated);
        groups.put(id, updated);
        indexDevices(updated);
        save();
        return Optional.of(updated);
    }

    public boolean delete(String id) {
        ForwardingGroup group = groups.remove(id);
        if (group == null) {
            return false;
        }
        group.getDeviceIds().forEach(deviceId -> deviceIndex.remove(deviceId, id));
        save();
        return true;
    }

    // ------------------------------------------------------- forwarding hook

    public Optional<ForwardingGroup> getGroupForDevice(long deviceId) {
        String groupId = deviceIndex.get(deviceId);
        if (groupId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(groups.get(groupId));
    }

    /**
     * Sends a counting event to the external server for the given device's group (if any).
     * Fire-and-forget: runs on a background thread, never blocks the Netty pipeline.
     */
    public void forwardIfConfigured(Position position, String deviceName, String plate) {
        getGroupForDevice(position.getDeviceId()).ifPresent(group -> {
            executor.submit(() -> {
                try {
                    int doorId = ((Number) position.getAttributes()
                            .getOrDefault("streamax.doorId", 0)).intValue();
                    Map<String, Object> payload = Map.of(
                            "deviceId",   position.getDeviceId(),
                            "deviceName", deviceName,
                            "plate",      plate,
                            "fixTime",    position.getFixTime(),
                            "latitude",   position.getLatitude(),
                            "longitude",  position.getLongitude(),
                            "doorId",     doorId,
                            "doorLabel",  doorId <= 1 ? "Delantera" : "Trasera",
                            "boardings",  ((Number) position.getAttributes()
                                    .getOrDefault("passengersOn", 0)).intValue(),
                            "alightings", ((Number) position.getAttributes()
                                    .getOrDefault("passengersOff", 0)).intValue()
                    );

                    String json = objectMapper.writeValueAsString(List.of(payload));

                    HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(group.getServerUrl()))
                            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                            .header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(30));

                    String user = group.getUsername();
                    if (user != null && !user.isBlank()) {
                        String cred = user + ":" + (group.getPassword() != null ? group.getPassword() : "");
                        req.header("Authorization", "Basic "
                                + Base64.getEncoder().encodeToString(cred.getBytes(StandardCharsets.UTF_8)));
                    }

                    HttpResponse<String> resp = httpClient.send(req.build(), HttpResponse.BodyHandlers.ofString());
                    LOGGER.info("EXTERNAL FORWARD deviceId={} group='{}' → {} status={}",
                            position.getDeviceId(), group.getName(), group.getServerUrl(), resp.statusCode());
                } catch (Exception e) {
                    LOGGER.warn("EXTERNAL FORWARD error deviceId={} group='{}': {}",
                            position.getDeviceId(), group.getName(), e.getMessage());
                }
            });
        });
    }

    // ------------------------------------------------- vehicle-service sync

    /**
     * Called by VehicleService whenever a vehicle is created, updated, or deleted.
     * Keeps the forwarding group for the given company name in sync with the current
     * set of device IDs that belong to it.
     */
    public synchronized void syncCompanyGroup(String companyName, Set<Long> deviceIds) {
        if (companyName == null || companyName.isBlank()) {
            return;
        }
        ForwardingGroup found = groups.values().stream()
                .filter(g -> companyName.equals(g.getName()))
                .findFirst().orElse(null);
        if (found != null) {
            found.getDeviceIds().forEach(did -> deviceIndex.remove(did, found.getId()));
            found.setDeviceIds(new HashSet<>(deviceIds));
            indexDevices(found);
            save();
        } else if (!deviceIds.isEmpty()) {
            ForwardingGroup g = new ForwardingGroup();
            g.setId(UUID.randomUUID().toString());
            g.setName(companyName);
            g.setDeviceIds(new HashSet<>(deviceIds));
            groups.put(g.getId(), g);
            indexDevices(g);
            save();
            LOGGER.info("EXT-FWD created group='{}' devices={}", companyName, deviceIds);
        }
    }

    // ---------------------------------------------------------------- helpers

    private void sanitizeDeviceIds(ForwardingGroup group) {
        // Remove any device that is already assigned to a DIFFERENT group
        group.getDeviceIds().removeIf(deviceId -> {
            String currentGroup = deviceIndex.get(deviceId);
            return currentGroup != null && !currentGroup.equals(group.getId());
        });
    }

    private void indexDevices(ForwardingGroup group) {
        group.getDeviceIds().forEach(deviceId -> deviceIndex.put(deviceId, group.getId()));
    }

    private void rebuildIndex() {
        deviceIndex.clear();
        groups.values().forEach(this::indexDevices);
    }

    // ------------------------------------------------------- file persistence

    private void load() {
        File file = new File(DATA_FILE);
        if (!file.exists()) {
            return;
        }
        try {
            List<ForwardingGroup> list = objectMapper.readValue(file,
                    new TypeReference<List<ForwardingGroup>>() { });
            list.forEach(g -> groups.put(g.getId(), g));
            rebuildIndex();
            LOGGER.info("EXTERNAL FORWARDING loaded {} group(s) from {}", groups.size(), DATA_FILE);
        } catch (Exception e) {
            LOGGER.error("EXTERNAL FORWARDING failed to load {}: {}", DATA_FILE, e.getMessage());
        }
    }

    private synchronized void save() {
        try {
            File file = new File(DATA_FILE);
            file.getParentFile().mkdirs();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, new ArrayList<>(groups.values()));
        } catch (Exception e) {
            LOGGER.error("EXTERNAL FORWARDING failed to save {}: {}", DATA_FILE, e.getMessage());
        }
    }
}
