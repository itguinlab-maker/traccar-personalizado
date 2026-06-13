package org.traccar.forwarding;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages API groups (grupos_api) and dispatches counting events to external REST servers.
 *
 * Groups are persisted to {@code ./data/api_groups.json}.
 * Pending retry jobs are persisted to {@code ./data/dispatch_queue.json}.
 * Last {@value #MAX_LOGS} dispatch attempts are kept in memory for the logs view.
 *
 * Auth types (configAuth.tipo):
 *   "Basic"  → Basic Authorization using "usuario" and "contrasena"
 *   "Bearer" → Bearer token using "token"
 *   absent / "None" → no Authorization header
 *
 * Retry policy: up to {@value #MAX_ATTEMPTS} attempts.
 *   1st failure → retry after 60 s
 *   2nd failure → retry after 300 s
 *   3rd failure → discard, log as permanent failure
 */
@Singleton
public class ExternalForwardingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExternalForwardingService.class);

    private static final String GROUPS_FILE = "./data/api_groups.json";
    private static final String QUEUE_FILE  = "./data/dispatch_queue.json";
    private static final int    MAX_LOGS    = 500;
    private static final int    MAX_ATTEMPTS = 3;
    private static final int[]  RETRY_DELAYS_SECONDS = {60, 300};

    private final ObjectMapper mapper;
    private final HttpClient   httpClient;

    // id → group
    private final Map<String, ApiGroup> groups      = new ConcurrentHashMap<>();
    // deviceId → groupId
    private final Map<Long, String>     deviceIndex = new ConcurrentHashMap<>();

    private final List<DispatchJob>   queue = Collections.synchronizedList(new ArrayList<>());
    private final LinkedList<IntegrationLog> logs = new LinkedList<>();

    @Inject
    public ExternalForwardingService(ObjectMapper mapper) {
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        loadGroups();
        loadQueue();

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ext-forwarding-dispatch");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::processQueue, 15, 30, TimeUnit.SECONDS);
    }

    // ═══════════════════════════════════════════════ CRUD ═══════════════════

    public List<ApiGroup> getAll() {
        return new ArrayList<>(groups.values());
    }

    public ApiGroup create(ApiGroup group) {
        group.setId(UUID.randomUUID().toString());
        removeAlreadyAssigned(group);
        groups.put(group.getId(), group);
        indexDevices(group);
        saveGroups();
        return group;
    }

    public Optional<ApiGroup> update(String id, ApiGroup updated) {
        ApiGroup existing = groups.get(id);
        if (existing == null) {
            return Optional.empty();
        }
        existing.getDeviceIds().forEach(did -> deviceIndex.remove(did, id));
        updated.setId(id);
        removeAlreadyAssigned(updated);
        groups.put(id, updated);
        indexDevices(updated);
        saveGroups();
        return Optional.of(updated);
    }

    public boolean delete(String id) {
        ApiGroup group = groups.remove(id);
        if (group == null) {
            return false;
        }
        group.getDeviceIds().forEach(did -> deviceIndex.remove(did, id));
        saveGroups();
        return true;
    }

    // ══════════════════════════════════ company-group sync (from vehicles) ═══

    /**
     * Called by VehicleService whenever vehicles are created, updated, or deleted.
     * Ensures a group named {@code companyName} exists and its deviceIds match
     * exactly the set of Traccar device IDs currently belonging to that company.
     *
     * Rules:
     *   - Group exists → update deviceIds in place (preserves endpoint/auth config).
     *   - Group missing AND deviceIds non-empty → create a minimal group (no endpoint yet).
     *   - Group missing AND deviceIds empty → no-op (nothing to create).
     *   - Group exists AND deviceIds empty → keep group but clear deviceIds (config preserved).
     */
    public synchronized void syncCompanyGroup(String companyName, Set<Long> deviceIds) {
        if (companyName == null || companyName.isBlank()) {
            return;
        }
        ApiGroup found = groups.values().stream()
                .filter(g -> companyName.equals(g.getNombreEmpresa()))
                .findFirst()
                .orElse(null);

        if (found != null) {
            found.getDeviceIds().forEach(did -> deviceIndex.remove(did, found.getId()));
            found.setDeviceIds(new HashSet<>(deviceIds));
            indexDevices(found);
            saveGroups();
            LOGGER.info("EXT-FWD sync group='{}' devices={}", companyName, deviceIds);
        } else if (!deviceIds.isEmpty()) {
            ApiGroup g = new ApiGroup();
            g.setId(UUID.randomUUID().toString());
            g.setNombreEmpresa(companyName);
            g.setDeviceIds(new HashSet<>(deviceIds));
            groups.put(g.getId(), g);
            indexDevices(g);
            saveGroups();
            LOGGER.info("EXT-FWD created group='{}' devices={}", companyName, deviceIds);
        }
    }

    // ══════════════════════════════════════════ dispatch entry point ══════════

    /**
     * Called by the position pipeline when a counting event arrives.
     * Enqueues the job immediately (first attempt fires within 30 s).
     */
    public void enqueue(long deviceId, String placa, String payloadJson) {
        String groupId = deviceIndex.get(deviceId);
        if (groupId == null) {
            return;
        }
        ApiGroup group = groups.get(groupId);
        if (group == null) {
            return;
        }

        DispatchJob job = new DispatchJob();
        job.setId(UUID.randomUUID().toString());
        job.setGroup(group);
        job.setPayloadJson(payloadJson);
        job.setDeviceId(deviceId);
        job.setPlaca(placa);
        job.setAttempts(0);
        job.setNextAttemptAt(Instant.now());

        // Extract event metadata for the video-download button in the UI
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> p = mapper.readValue(payloadJson, Map.class);
            String te = (String) p.getOrDefault("tiempo_evento", "");
            job.setTiempoEvento(te);
            Object pv = p.get("puerta");
            if (pv instanceof Number pn) {
                job.setPuerta(pn.intValue());
            }
        } catch (Exception ignored) { }

        queue.add(job);
        saveQueue();
        LOGGER.info("EXT-FWD enqueued job={} device={} placa={} group='{}'",
                job.getId(), deviceId, placa, group.getNombreEmpresa());
    }

    // ═══════════════════════════════════════════ log access ══════════════════

    public List<IntegrationLog> getLogs() {
        synchronized (logs) {
            return new ArrayList<>(logs);
        }
    }

    // ═══════════════════════════════════════════ queue processing ════════════

    private void processQueue() {
        List<DispatchJob> dueNow;
        synchronized (queue) {
            Instant now = Instant.now();
            dueNow = new ArrayList<>(queue.stream()
                    .filter(j -> !j.getNextAttemptAt().isAfter(now))
                    .toList());
        }

        for (DispatchJob job : dueNow) {
            dispatch(job);
        }

        if (!dueNow.isEmpty()) {
            saveQueue();
        }
    }

    private void dispatch(DispatchJob job) {
        job.setAttempts(job.getAttempts() + 1);
        ApiGroup group = job.getGroup();
        IntegrationLog log = new IntegrationLog();
        log.setTimestamp(Instant.now());
        log.setGroupId(group.getId());
        log.setNombreEmpresa(group.getNombreEmpresa());
        log.setDeviceId(job.getDeviceId());
        log.setPlaca(job.getPlaca());
        log.setAttempt(job.getAttempts());
        log.setTiempoEvento(job.getTiempoEvento());
        log.setPuerta(job.getPuerta());

        try {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(group.getEndpointUrl()))
                    .method(group.getMetodoHttp(),
                            HttpRequest.BodyPublishers.ofString(job.getPayloadJson(), StandardCharsets.UTF_8))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30));

            applyAuth(req, group);
            group.getHeadersExtra().forEach(req::header);

            HttpResponse<String> resp = httpClient.send(req.build(), HttpResponse.BodyHandlers.ofString());
            log.setStatusCode(resp.statusCode());
            log.setSuccess(resp.statusCode() >= 200 && resp.statusCode() < 300);

            if (log.isSuccess()) {
                LOGGER.info("EXT-FWD OK job={} attempt={} device={} group='{}' status={}",
                        job.getId(), job.getAttempts(), job.getDeviceId(),
                        group.getNombreEmpresa(), resp.statusCode());
                queue.remove(job);
            } else {
                log.setError("HTTP " + resp.statusCode());
                scheduleRetryOrDiscard(job, group, log);
            }

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.setError("interrupted");
            scheduleRetryOrDiscard(job, group, log);
        } catch (Exception e) {
            log.setError(e.getMessage());
            LOGGER.warn("EXT-FWD ERROR job={} attempt={} group='{}': {}",
                    job.getId(), job.getAttempts(), group.getNombreEmpresa(), e.getMessage());
            scheduleRetryOrDiscard(job, group, log);
        }

        addLog(log);
    }

    private void scheduleRetryOrDiscard(DispatchJob job, ApiGroup group, IntegrationLog log) {
        if (job.getAttempts() >= MAX_ATTEMPTS) {
            LOGGER.warn("EXT-FWD DISCARD job={} after {} attempts group='{}'",
                    job.getId(), job.getAttempts(), group.getNombreEmpresa());
            log.setSuccess(false);
            queue.remove(job);
        } else {
            int delayIdx = Math.min(job.getAttempts() - 1, RETRY_DELAYS_SECONDS.length - 1);
            int delaySecs = RETRY_DELAYS_SECONDS[delayIdx];
            job.setNextAttemptAt(Instant.now().plusSeconds(delaySecs));
            LOGGER.info("EXT-FWD RETRY job={} in {}s group='{}'",
                    job.getId(), delaySecs, group.getNombreEmpresa());
        }
    }

    private void applyAuth(HttpRequest.Builder req, ApiGroup group) {
        Map<String, String> auth = group.getConfigAuth();
        if (auth == null || auth.isEmpty()) {
            return;
        }
        String tipo = auth.getOrDefault("tipo", "None");
        switch (tipo) {
            case "Basic" -> {
                String u = auth.getOrDefault("usuario", "");
                String p = auth.getOrDefault("contrasena", "");
                String cred = Base64.getEncoder()
                        .encodeToString((u + ":" + p).getBytes(StandardCharsets.UTF_8));
                req.header("Authorization", "Basic " + cred);
            }
            case "Bearer" -> {
                String token = auth.getOrDefault("token", "");
                req.header("Authorization", "Bearer " + token);
            }
            default -> { /* None or unknown → no header */ }
        }
    }

    // ═══════════════════════════════════════════════ helpers ═════════════════

    private void removeAlreadyAssigned(ApiGroup group) {
        group.getDeviceIds().removeIf(did -> {
            String currentGroup = deviceIndex.get(did);
            return currentGroup != null && !currentGroup.equals(group.getId());
        });
    }

    private void indexDevices(ApiGroup group) {
        group.getDeviceIds().forEach(did -> deviceIndex.put(did, group.getId()));
    }

    private void rebuildIndex() {
        deviceIndex.clear();
        groups.values().forEach(this::indexDevices);
    }

    private void addLog(IntegrationLog entry) {
        synchronized (logs) {
            logs.addFirst(entry);
            while (logs.size() > MAX_LOGS) {
                logs.removeLast();
            }
        }
    }

    // ═══════════════════════════════════════════ persistence ═════════════════

    private void loadGroups() {
        File f = new File(GROUPS_FILE);
        if (!f.exists()) {
            return;
        }
        try {
            List<ApiGroup> list = mapper.readValue(f, new TypeReference<>() { });
            list.forEach(g -> groups.put(g.getId(), g));
            rebuildIndex();
            LOGGER.info("EXT-FWD loaded {} group(s)", groups.size());
        } catch (Exception e) {
            LOGGER.error("EXT-FWD failed to load {}: {}", GROUPS_FILE, e.getMessage());
        }
    }

    private synchronized void saveGroups() {
        try {
            File f = new File(GROUPS_FILE);
            f.getParentFile().mkdirs();
            mapper.writerWithDefaultPrettyPrinter().writeValue(f, new ArrayList<>(groups.values()));
        } catch (Exception e) {
            LOGGER.error("EXT-FWD failed to save groups: {}", e.getMessage());
        }
    }

    private void loadQueue() {
        File f = new File(QUEUE_FILE);
        if (!f.exists()) {
            return;
        }
        try {
            List<DispatchJob> saved = mapper.readValue(f, new TypeReference<>() { });
            queue.addAll(saved);
            LOGGER.info("EXT-FWD loaded {} pending job(s) from queue", queue.size());
        } catch (Exception e) {
            LOGGER.warn("EXT-FWD failed to load queue (will start fresh): {}", e.getMessage());
        }
    }

    private synchronized void saveQueue() {
        try {
            File f = new File(QUEUE_FILE);
            f.getParentFile().mkdirs();
            synchronized (queue) {
                mapper.writerWithDefaultPrettyPrinter().writeValue(f, new ArrayList<>(queue));
            }
        } catch (Exception e) {
            LOGGER.error("EXT-FWD failed to save queue: {}", e.getMessage());
        }
    }
}
