package org.traccar.hikvision;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.model.Device;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Request;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Singleton
public class HikvisionAlertStreamService {

    private static final Logger LOGGER = LoggerFactory.getLogger(HikvisionAlertStreamService.class);
    private static final int RECONNECT_DELAY_MS = 15_000;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS    = 120_000;
    private static final int POLL_INTERVAL_SEC  = 30;
    private static final DateTimeFormatter DT_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final HikvisionEventService eventService;
    private final Storage storage;
    private final ExecutorService streamExecutor;
    private final ScheduledExecutorService pollExecutor;

    // deviceId → [lastEnter, lastExit]
    private final Map<Long, long[]> lastCounts = new ConcurrentHashMap<>();
    // deviceId → timestamp del último evento guardado (para deduplicar search)
    private final Map<Long, Instant> lastSavedTime = new ConcurrentHashMap<>();

    @Inject
    public HikvisionAlertStreamService(HikvisionEventService eventService, Storage storage) {
        this.eventService  = eventService;
        this.storage       = storage;
        this.streamExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "hik-alert-stream");
            t.setDaemon(true);
            return t;
        });
        this.pollExecutor = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "hik-poll");
            t.setDaemon(true);
            return t;
        });
        scheduleStart();
    }

    private void scheduleStart() {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(8000);
                startAll();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LOGGER.error("HIK startup error: {}", e.getMessage());
            }
        }, "hik-starter");
        t.setDaemon(true);
        t.start();
    }

    private void startAll() throws StorageException {
        Collection<Device> devices = storage.getObjects(Device.class, new Request(new Columns.All()));
        int count = 0;
        for (Device device : devices) {
            if (attr(device, "hikIp", null) != null) {
                streamExecutor.submit(() -> streamWithReconnect(device));
                pollExecutor.scheduleAtFixedRate(
                        () -> pollCountingStatus(device),
                        5, POLL_INTERVAL_SEC, TimeUnit.SECONDS);
                count++;
            }
        }
        LOGGER.info("HIK iniciado para {} dispositivos (stream + polling cada {}s)", count, POLL_INTERVAL_SEC);
    }

    // ── Alert stream (heartbeat / eventos básicos) ────────────────────────────

    private void streamWithReconnect(Device device) {
        String hikIp = attr(device, "hikIp", "?");
        while (!Thread.currentThread().isInterrupted()) {
            try {
                LOGGER.info("HIK stream conectando deviceId={} ip={}", device.getId(), hikIp);
                connectAndStream(device);
                LOGGER.warn("HIK stream cerrado deviceId={} — reconectando en {}s",
                        device.getId(), RECONNECT_DELAY_MS / 1000);
            } catch (Exception e) {
                LOGGER.warn("HIK stream error deviceId={}: {} — reconectando en {}s",
                        device.getId(), e.getMessage(), RECONNECT_DELAY_MS / 1000);
            }
            try {
                Thread.sleep(RECONNECT_DELAY_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void connectAndStream(Device device) throws Exception {
        String ip   = attr(device, "hikIp",  "");
        String user = attr(device, "hikUser", "admin");
        String pass = attr(device, "hikPass", "admin");
        String path = "/ISAPI/Event/notification/alertStream";

        HttpURLConnection conn = openGet("http://" + ip + path, null);
        int status = conn.getResponseCode();
        if (status == 401) {
            String wwwAuth = conn.getHeaderField("WWW-Authenticate");
            conn.disconnect();
            conn = openGet("http://" + ip + path, buildAuth(user, pass, "GET", path, wwwAuth));
            status = conn.getResponseCode();
        }
        if (status != 200) {
            conn.disconnect();
            LOGGER.warn("HIK stream HTTP {} deviceId={}", status, device.getId());
            return;
        }
        LOGGER.info("HIK stream conectado OK deviceId={} ip={}", device.getId(), ip);
        try (InputStream is = conn.getInputStream()) {
            parseStream(is, device.getId());
        } finally {
            conn.disconnect();
        }
    }

    private void parseStream(InputStream is, long deviceId) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder xmlBuf = new StringBuilder();
        boolean collecting = false;
        String line;
        while ((line = reader.readLine()) != null) {
            if (!collecting && line.contains("<EventNotificationAlert")) {
                collecting = true;
                xmlBuf.setLength(0);
            }
            if (collecting) {
                xmlBuf.append(line).append('\n');
                if (line.contains("</EventNotificationAlert>")) {
                    collecting = false;
                    processStreamEvent(xmlBuf.toString(), deviceId);
                }
            }
        }
    }

    private void processStreamEvent(String xml, long deviceId) {
        try {
            Document doc = parseXml(xml);
            String eventType = textOf(doc, "eventType");
            if (eventType == null) {
                return;
            }
            String lower = eventType.toLowerCase();
            if (lower.contains("videoloss") || lower.contains("tamper")
                    || lower.contains("shelteralarm")) {
                return; // heartbeat silencioso
            }
            // Hikvision usa enterCount/leaveCount dentro de <peopleCountingEvent>
            int enter = intOf(doc, 0, "enterCount", "entryCount", "enter", "Enter", "enterNum");
            int exit  = intOf(doc, 0, "leaveCount", "leaveCount", "exit",  "Exit",  "exitNum");
            LOGGER.info("HIK stream evento deviceId={} type={} enter={} exit={}", deviceId, eventType, enter, exit);
            if (lower.contains("people") || lower.contains("counting")) {
                if (enter > 0 || exit > 0) {
                    saveEvent(deviceId, textOf(doc, "time", "dateTime", "DateTime"),
                            intOf(doc, 1, "channelID", "ChannelID"), enter, exit, "stream");
                }
            }
        } catch (Exception e) {
            LOGGER.warn("HIK stream parse error deviceId={}: {}", deviceId, e.getMessage());
        }
    }

    // ── Polling de estado de conteo ───────────────────────────────────────────

    private static final String[] POLL_ENDPOINTS = {
        "/ISAPI/System/Video/inputs/channels/1/counting",
        "/ISAPI/System/Video/inputs/channels/1/counting/status",
        "/ISAPI/System/Video/inputs/channels/1/counting/capabilities",
        "/ISAPI/System/Video/inputs/channels/1/counting/statisticsReport",
    };

    private void pollCountingStatus(Device device) {
        String ip   = attr(device, "hikIp",  "");
        String user = attr(device, "hikUser", "admin");
        String pass = attr(device, "hikPass", "admin");
        long deviceId = device.getId();

        for (String endpoint : POLL_ENDPOINTS) {
            try {
                String response = doGet(ip, user, pass, endpoint);
                if (response == null) {
                    continue;
                }
                // Log en una sola línea para que Select-String lo muestre completo
                String oneLine = response.replaceAll("\\r?\\n\\s*", " ").trim();
                LOGGER.info("HIK poll [{}] len={} deviceId={}: {}", endpoint, oneLine.length(), deviceId,
                        oneLine.length() > 6000 ? oneLine.substring(0, 6000) + "…" : oneLine);

                if (parsePollStatus(response, deviceId)) {
                    return;
                }
            } catch (Exception e) {
                LOGGER.debug("HIK poll endpoint={} error deviceId={}: {}", endpoint, deviceId, e.getMessage());
            }
        }
        // Fallback: POST search (único endpoint con datos reales de conteo)
        tryPostSearch(ip, user, pass, deviceId);
    }

    private void tryPostSearch(String ip, String user, String pass, long deviceId) {
        Instant now  = Instant.now();
        Instant from = lastSavedTime.getOrDefault(deviceId, now.minusSeconds(86400));
        String startTime = DT_FMT.format(from);
        String endTime   = DT_FMT.format(now);

        String[] bodies = {
            // A: namespace correcto http://hikvision.com + reportType daily
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<countingStatisticsDescription xmlns=\"http://hikvision.com\">"
            + "<reportType>daily</reportType>"
            + "<timeSpanList><timeSpan>"
            + "<startTime>" + startTime + "</startTime>"
            + "<endTime>" + endTime + "</endTime>"
            + "</timeSpan></timeSpanList>"
            + "</countingStatisticsDescription>",
            // B: sin namespace
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<countingStatisticsDescription>"
            + "<reportType>daily</reportType>"
            + "<timeSpanList><timeSpan>"
            + "<startTime>" + startTime + "</startTime>"
            + "<endTime>" + endTime + "</endTime>"
            + "</timeSpan></timeSpanList>"
            + "</countingStatisticsDescription>",
            // C: reportType custom
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<countingStatisticsDescription xmlns=\"http://hikvision.com\">"
            + "<reportType>custom</reportType>"
            + "<timeSpanList><timeSpan>"
            + "<startTime>" + startTime + "</startTime>"
            + "<endTime>" + endTime + "</endTime>"
            + "</timeSpan></timeSpanList>"
            + "</countingStatisticsDescription>",
        };

        for (int i = 0; i < bodies.length; i++) {
            try {
                String response = doPost(ip, user, pass,
                        "/ISAPI/System/Video/inputs/channels/1/counting/search", bodies[i]);
                if (response != null) {
                    String oneLine = response.replaceAll("\\r?\\n\\s*", " ").trim();
                    LOGGER.info("HIK search fmt{} deviceId={}: {}", i, deviceId,
                            oneLine.length() > 1000 ? oneLine.substring(0, 1000) + "…" : oneLine);
                    parseSearchResponse(response, deviceId, now);
                    return;
                }
            } catch (Exception e) {
                LOGGER.debug("HIK search fmt{} error deviceId={}: {}", i, deviceId, e.getMessage());
            }
        }
        LOGGER.debug("HIK search todos los formatos fallaron deviceId={}", deviceId);
    }

    private void parseSearchResponse(String xml, long deviceId, Instant searchTime) {
        try {
            Document doc = parseXml(xml);

            // Suma todos los matchElement del período
            NodeList items = doc.getElementsByTagName("matchElement");
            if (items.getLength() == 0) {
                LOGGER.debug("HIK search sin matchElement deviceId={}", deviceId);
                lastSavedTime.put(deviceId, searchTime);
                return;
            }

            int totalEnter = 0;
            int totalExit  = 0;
            String latestTime = null;
            for (int i = 0; i < items.getLength(); i++) {
                org.w3c.dom.Element el = (org.w3c.dom.Element) items.item(i);
                totalEnter += intFromElement(el, "enterCount", "entryCount");
                totalExit  += intFromElement(el, "exitCount",  "leaveCount");
                String t = textFromElement(el, "endTime", "startTime");
                if (t != null) {
                    latestTime = t;
                }
            }

            LOGGER.info("HIK search totales deviceId={} enter={} exit={} periodos={}",
                    deviceId, totalEnter, totalExit, items.getLength());

            long[] last = lastCounts.get(deviceId);
            if (last == null) {
                // Primera vez: establecer baseline
                lastCounts.put(deviceId, new long[]{totalEnter, totalExit});
                LOGGER.info("HIK search baseline deviceId={} enter={} exit={}", deviceId, totalEnter, totalExit);
                lastSavedTime.put(deviceId, searchTime);
                return;
            }

            int deltaEnter = (int) (totalEnter - last[0]);
            int deltaExit  = (int) (totalExit  - last[1]);

            if (deltaEnter > 0 || deltaExit > 0) {
                lastCounts.put(deviceId, new long[]{totalEnter, totalExit});
                saveEvent(deviceId, latestTime, 1, Math.max(0, deltaEnter), Math.max(0, deltaExit), "search");
            } else if (totalEnter < last[0] || totalExit < last[1]) {
                // Reset diario: la cámara empezó nuevo día
                LOGGER.info("HIK search reset diario deviceId={}", deviceId);
                lastCounts.put(deviceId, new long[]{totalEnter, totalExit});
            }

            lastSavedTime.put(deviceId, searchTime);
        } catch (Exception e) {
            LOGGER.warn("HIK search parse error deviceId={}: {}", deviceId, e.getMessage());
        }
    }

    private int intFromElement(org.w3c.dom.Element el, String... tags) {
        for (String tag : tags) {
            NodeList nl = el.getElementsByTagName(tag);
            if (nl.getLength() > 0) {
                try { return Integer.parseInt(nl.item(0).getTextContent().trim()); }
                catch (NumberFormatException ignored) { }
            }
        }
        return 0;
    }

    private String textFromElement(org.w3c.dom.Element el, String... tags) {
        for (String tag : tags) {
            NodeList nl = el.getElementsByTagName(tag);
            if (nl.getLength() > 0) {
                String t = nl.item(0).getTextContent().trim();
                if (!t.isEmpty()) return t;
            }
        }
        return null;
    }

    private boolean parsePollStatus(String xml, long deviceId) {
        try {
            Document doc = parseXml(xml);
            int enter = intOf(doc, -1, "enter", "Enter", "enterNum", "EnterNum");
            int exit  = intOf(doc, -1, "exit",  "Exit",  "exitNum",  "ExitNum");

            if (enter < 0 && exit < 0) {
                LOGGER.debug("HIK poll no incluye enter/exit deviceId={}", deviceId);
                return false;
            }

            String dateTime = textOf(doc, "startTime", "dateTime", "DateTime", "time");
            long[] last = lastCounts.get(deviceId);

            if (last == null) {
                lastCounts.put(deviceId, new long[]{Math.max(0, enter), Math.max(0, exit)});
                LOGGER.info("HIK poll baseline deviceId={} enter={} exit={}", deviceId, enter, exit);
                return true;
            }

            long prevEnter = last[0];
            long prevExit  = last[1];
            int deltaEnter = (int) (enter - prevEnter);
            int deltaExit  = (int) (exit  - prevExit);

            // Reset de sesión: la cámara empezó un nuevo ciclo (puerta cerró y abrió)
            if (enter < prevEnter || exit < prevExit) {
                LOGGER.info("HIK poll reset de sesión deviceId={} prev=[{},{}] nuevo=[{},{}]",
                        deviceId, prevEnter, prevExit, enter, exit);
                lastCounts.put(deviceId, new long[]{Math.max(0, enter), Math.max(0, exit)});
                return true;
            }

            if (deltaEnter > 0 || deltaExit > 0) {
                lastCounts.put(deviceId, new long[]{enter, exit});
                saveEvent(deviceId, dateTime, 1, Math.max(0, deltaEnter), Math.max(0, deltaExit), "poll");
            }
            return true;

        } catch (Exception e) {
            LOGGER.warn("HIK poll parse error deviceId={}: {}", deviceId, e.getMessage());
        }
        return false;
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────────

    private String doGet(String ip, String user, String pass, String path) throws Exception {
        String url = "http://" + ip + path;
        HttpURLConnection conn = openGet(url, null);
        int status = conn.getResponseCode();
        if (status == 401) {
            String wwwAuth = conn.getHeaderField("WWW-Authenticate");
            conn.disconnect();
            conn = openGet(url, buildAuth(user, pass, "GET", path, wwwAuth));
            status = conn.getResponseCode();
        }
        if (status != 200) {
            conn.disconnect();
            return null;
        }
        try (InputStream is = conn.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }

    private HttpURLConnection openGet(String urlStr, String authHeader) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("Accept", "application/xml");
        if (authHeader != null) {
            conn.setRequestProperty("Authorization", authHeader);
        }
        conn.connect();
        return conn;
    }

    private String doPost(String ip, String user, String pass, String path, String xmlBody) throws Exception {
        String url = "http://" + ip + path;
        byte[] body = xmlBody.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = openPost(url, null, body);
        int status = conn.getResponseCode();
        if (status == 401) {
            String wwwAuth = conn.getHeaderField("WWW-Authenticate");
            conn.disconnect();
            conn = openPost(url, buildAuth(user, pass, "POST", path, wwwAuth), body);
            status = conn.getResponseCode();
        }
        if (status != 200) {
            InputStream errStream = conn.getErrorStream();
            String errBody = "";
            if (errStream != null) {
                errBody = new String(errStream.readAllBytes(), StandardCharsets.UTF_8)
                        .replaceAll("\\r?\\n\\s*", " ").trim();
            }
            LOGGER.warn("HIK POST {} → HTTP {} body: {}", path, status,
                    errBody.length() > 300 ? errBody.substring(0, 300) : errBody);
            conn.disconnect();
            return null;
        }
        try (InputStream is = conn.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }

    private HttpURLConnection openPost(String urlStr, String authHeader, byte[] body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(30_000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/xml; charset=UTF-8");
        conn.setRequestProperty("Accept", "application/xml");
        if (authHeader != null) {
            conn.setRequestProperty("Authorization", authHeader);
        }
        conn.connect();
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body);
        }
        return conn;
    }

    // ── Auth ──────────────────────────────────────────────────────────────────

    private String buildAuth(String user, String pass, String method, String uri, String wwwAuth) {
        if (wwwAuth != null && wwwAuth.startsWith("Digest")) {
            return buildDigest(user, pass, method, uri, wwwAuth);
        }
        return "Basic " + Base64.getEncoder().encodeToString(
                (user + ":" + pass).getBytes(StandardCharsets.UTF_8));
    }

    private String buildDigest(String user, String pass, String method, String uri, String wwwAuth) {
        String realm  = param(wwwAuth, "realm");
        String nonce  = param(wwwAuth, "nonce");
        String qop    = param(wwwAuth, "qop");
        String ha1    = md5(user + ":" + realm + ":" + pass);
        String ha2    = md5(method + ":" + uri);
        String nc     = "00000001";
        String cnonce = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String response;
        if ("auth".equals(qop)) {
            response = md5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":auth:" + ha2);
            return String.format(
                    "Digest username=\"%s\",realm=\"%s\",nonce=\"%s\",uri=\"%s\","
                    + "qop=auth,nc=%s,cnonce=\"%s\",response=\"%s\"",
                    user, realm, nonce, uri, nc, cnonce, response);
        } else {
            response = md5(ha1 + ":" + nonce + ":" + ha2);
            return String.format(
                    "Digest username=\"%s\",realm=\"%s\",nonce=\"%s\",uri=\"%s\",response=\"%s\"",
                    user, realm, nonce, uri, response);
        }
    }

    private String param(String header, String name) {
        Matcher m = Pattern.compile(name + "=\"?([^,\"]+)\"?").matcher(header);
        return m.find() ? m.group(1).trim() : "";
    }

    private String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    // ── XML / helpers ─────────────────────────────────────────────────────────

    // Camera bug: sends UTC value with local offset appended (e.g. 16:00-05:00 when real local is 21:00)
    // Fix: parse with standard ISO offset, then add abs(offset) to get real UTC
    static String fixCameraDateTime(String raw) {
        if (raw == null) return null;
        if (raw.endsWith("Z") || raw.endsWith("z")) return raw;
        String norm = raw.replaceAll("([+-])(\\d:)", "$10$2");
        try {
            java.time.OffsetDateTime odt = java.time.OffsetDateTime.parse(norm);
            long absOffsetSec = Math.abs(odt.getOffset().getTotalSeconds());
            return odt.toInstant().plusSeconds(absOffsetSec).toString();
        } catch (Exception e) {
            return raw;
        }
    }

    private void saveEvent(long deviceId, String dateTime, int channel, int enter, int exit, String source) {
        HikvisionEvent event = new HikvisionEvent();
        event.setDeviceId(deviceId);
        event.setDateTime(fixCameraDateTime(dateTime != null ? dateTime : Instant.now().toString()));
        event.setChannel(channel);
        event.setEnter(enter);
        event.setExit(exit);
        eventService.save(event);
        LOGGER.info("HIK [{}] GUARDADO deviceId={} enter={} exit={} time={}",
                source, deviceId, enter, exit, event.getDateTime());
    }

    private Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        return f.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private String textOf(Document doc, String... tags) {
        for (String tag : tags) {
            NodeList nodes = doc.getElementsByTagName(tag);
            if (nodes.getLength() > 0) {
                String text = nodes.item(0).getTextContent();
                if (text != null && !text.trim().isEmpty()) {
                    return text.trim();
                }
            }
        }
        return null;
    }

    private int intOf(Document doc, int defaultValue, String... tags) {
        String val = textOf(doc, tags);
        if (val != null) {
            try {
                return Integer.parseInt(val);
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private String attr(Device device, String key, String defaultValue) {
        Object val = device.getAttributes().get(key);
        return val != null ? val.toString() : defaultValue;
    }
}
