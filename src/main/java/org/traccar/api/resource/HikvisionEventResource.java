package org.traccar.api.resource;

import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.api.BaseResource;
import org.traccar.hikvision.HikvisionEvent;
import org.traccar.hikvision.HikvisionEventService;
import org.traccar.model.Device;
import org.traccar.session.ConnectionManager;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Path("hikvision")
@Produces(MediaType.APPLICATION_JSON)
public class HikvisionEventResource extends BaseResource {

    private static final Logger LOGGER = LoggerFactory.getLogger(HikvisionEventResource.class);

    @Inject
    private HikvisionEventService service;

    @Inject
    private ConnectionManager connectionManager;

    @GET
    @Path("events")
    public Response getEvents(
            @QueryParam("deviceId") long deviceId,
            @QueryParam("from") String fromIso,
            @QueryParam("to") String toIso) {
        Instant from = parseInstant(fromIso);
        Instant to   = parseInstant(toIso);
        List<HikvisionEvent> result = deviceId > 0
                ? service.getByDevice(deviceId, from, to)
                : service.getAll();
        LOGGER.info("HIK GET events deviceId={} from={} to={} resultados={}", deviceId, fromIso, toIso, result.size());
        return Response.ok(result).build();
    }

    private Instant parseInstant(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(iso);
        } catch (Exception e) {
            return null;
        }
    }

    @POST
    @Path("event")
    @PermitAll
    @Consumes(MediaType.WILDCARD)
    public Response receiveEvent(
            @QueryParam("deviceId") long deviceId,
            InputStream body) {

        if (deviceId <= 0) {
            LOGGER.warn("HIK evento recibido sin deviceId");
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("deviceId requerido").build();
        }

        try {
            byte[] raw = body.readAllBytes();
            String bodyStr = new String(raw, StandardCharsets.UTF_8);
            LOGGER.info("HIK POST recibido deviceId={} bytes={}", deviceId, raw.length);
            LOGGER.info("HIK body deviceId={}: {}", deviceId,
                    bodyStr.length() > 800 ? bodyStr.substring(0, 800) + "…" : bodyStr);

            String xml = extractXml(bodyStr);
            if (xml == null) {
                LOGGER.warn("HIK no se pudo extraer XML del evento deviceId={}", deviceId);
                return Response.ok().build();
            }
            LOGGER.info("HIK XML extraído deviceId={} chars={}", deviceId, xml.length());

            HikvisionEvent event = parseEvent(xml, deviceId);
            if (event == null) {
                return Response.ok().build();
            }

            service.save(event);
            connectionManager.updateDevice(deviceId, Device.STATUS_ONLINE, new java.util.Date());
            LOGGER.info("HIK evento GUARDADO deviceId={} channel={} enter={} exit={} time={}",
                    deviceId, event.getChannel(), event.getEnter(), event.getExit(), event.getDateTime());

        } catch (Exception e) {
            LOGGER.error("HIK error procesando evento deviceId={}: {}", deviceId, e.getMessage());
        }

        return Response.ok().build();
    }

    @GET
    @Path("clip")
    @Produces("video/mp4")
    public Response downloadClip(
            @QueryParam("deviceId") long deviceId,
            @QueryParam("from") String fromIso,
            @QueryParam("to") String toIso,
            @DefaultValue("1") @QueryParam("channel") int channel) {

        if (deviceId <= 0 || fromIso == null || toIso == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity("deviceId, from, to requeridos").build();
        }

        Device device;
        try {
            device = storage.getObject(Device.class, new Request(
                    new Columns.All(), new Condition.Equals("id", deviceId)));
        } catch (Exception e) {
            LOGGER.error("HIK clip error buscando device {}: {}", deviceId, e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
        if (device == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("Device not found").build();
        }

        String ip    = (String) device.getAttributes().getOrDefault("hikIp",   "");
        String user  = (String) device.getAttributes().getOrDefault("hikUser",  "admin");
        String pass  = (String) device.getAttributes().getOrDefault("hikPass",  "admin");
        String tzOff = (String) device.getAttributes().getOrDefault("hikTzOffset", "-05:00");

        Instant from, to;
        try {
            from = Instant.parse(fromIso);
            to   = Instant.parse(toIso);
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Formato de tiempo inválido").build();
        }

        long durSec = Duration.between(from, to).getSeconds();
        if (durSec <= 0) {
            durSec = 60;
        }

        ZoneOffset zoneOffset;
        try {
            String normOff = tzOff.replaceAll("([+-])(\\d:)", "$10$2");
            zoneOffset = ZoneOffset.of(normOff);
        } catch (Exception e) {
            zoneOffset = ZoneOffset.UTC;
        }

        // RTSP starttime = local time at the clip start (event - 15 s).
        // The camera indexes recordings by its displayed local time.
        String startTime = OffsetDateTime.ofInstant(from, zoneOffset)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx"));

        int trackNum = channel * 100 + 1;
        String rtspUrl = String.format("rtsp://%s:%s@%s:554/Streaming/tracks/%d?starttime=%s",
                user, pass, ip, trackNum, startTime);

        LOGGER.info("HIK clip deviceId={} ch={} dur={}s startTime={} url={}",
                deviceId, channel, durSec, startTime, rtspUrl.replaceAll(":[^:@]+@", ":***@"));

        String filename = String.format("hik_ch%d_%s.mp4",
                channel, fromIso.replaceAll("[:.Z]", "-").substring(0, Math.min(19, fromIso.length())));

        final long finalDur = durSec;
        final String finalUrl = rtspUrl;
        StreamingOutput out = output -> {
            java.nio.file.Path tmp = java.nio.file.Files.createTempFile("hikclip_", ".mp4");
            Process proc = null;
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        "ffmpeg", "-y",
                        "-rtsp_transport", "tcp",
                        "-stimeout", "15000000",  // 15s socket stall timeout (microseconds)
                        "-i", finalUrl,
                        "-t", String.valueOf(finalDur),
                        "-c", "copy",
                        "-movflags", "+faststart",
                        tmp.toAbsolutePath().toString());
                pb.redirectErrorStream(true);
                proc = pb.start();

                // Drain stderr in a daemon thread — transferTo() blocks until ffmpeg exits,
                // so it MUST run in a separate thread or the waitFor timeout below never fires.
                final Process finalProc = proc;
                final java.io.ByteArrayOutputStream errBuf = new java.io.ByteArrayOutputStream(8192);
                Thread drainer = new Thread(() -> {
                    try {
                        finalProc.getInputStream().transferTo(errBuf);
                    } catch (Exception ignored) {
                    }
                }, "hik-ffmpeg-drain");
                drainer.setDaemon(true);
                drainer.start();

                boolean done = proc.waitFor(finalDur + 20, java.util.concurrent.TimeUnit.SECONDS);
                drainer.join(3000);

                if (!done) {
                    String errStr = errBuf.toString(java.nio.charset.StandardCharsets.UTF_8);
                    LOGGER.error("HIK clip ffmpeg timeout after {}s. stderr first 600: {}",
                            finalDur + 20, errStr.length() > 600 ? errStr.substring(0, 600) : errStr);
                    throw new java.io.IOException("ffmpeg timeout after " + (finalDur + 20) + "s");
                }
                if (proc.exitValue() != 0) {
                    String errStr = errBuf.toString(java.nio.charset.StandardCharsets.UTF_8);
                    LOGGER.error("HIK clip ffmpeg exit={} stderr first 600: {}",
                            proc.exitValue(), errStr.length() > 600 ? errStr.substring(0, 600) : errStr);
                    throw new java.io.IOException("ffmpeg failed, exit=" + proc.exitValue());
                }
                long fileSize = java.nio.file.Files.size(tmp);
                LOGGER.info("HIK clip ffmpeg OK, file={} bytes, copying to response", fileSize);
                java.nio.file.Files.copy(tmp, output);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException("interrupted", e);
            } finally {
                if (proc != null) {
                    proc.destroyForcibly();
                }
                java.nio.file.Files.deleteIfExists(tmp);
            }
        };

        return Response.ok(out)
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .header("Content-Type", "video/mp4")
                .header("Cache-Control", "no-store, no-cache")
                .build();
    }

    @POST
    @Path("rawdump")
    @PermitAll
    @Consumes(MediaType.WILDCARD)
    public Response rawDump(@Context HttpHeaders headers, InputStream body) throws Exception {
        byte[] raw = body.readAllBytes();
        String bodyStr = new String(raw, StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder("HIKDUMP headers:");
        headers.getRequestHeaders().forEach((k, v) -> sb.append(" [").append(k).append("=").append(v).append("]"));
        sb.append(" | body(").append(raw.length).append("B): ");
        sb.append(bodyStr.length() > 2000 ? bodyStr.substring(0, 2000) + "…" : bodyStr);
        LOGGER.info("{}", sb);
        return Response.ok().build();
    }

    private String extractXml(String bodyStr) {
        String trimmed = bodyStr.trim();
        if (trimmed.startsWith("<?xml") || trimmed.startsWith("<Event")) {
            return trimmed;
        }
        int xmlStart = bodyStr.indexOf("<?xml");
        if (xmlStart < 0) {
            xmlStart = bodyStr.indexOf("<EventNotificationAlert");
        }
        return xmlStart >= 0 ? bodyStr.substring(xmlStart) : null;
    }

    private HikvisionEvent parseEvent(String xml, long deviceId) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            Document doc = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

            String eventType = textOf(doc, "eventType");
            if (eventType != null && !eventType.isEmpty()) {
                String lower = eventType.toLowerCase();
                if (!lower.contains("people") && !lower.contains("counting")) {
                    LOGGER.info("HIK eventType ignorado: '{}' deviceId={}", eventType, deviceId);
                    return null;
                }
            }

            HikvisionEvent event = new HikvisionEvent();
            event.setDeviceId(deviceId);
            event.setDateTime(textOf(doc, "dateTime", "DateTime"));
            event.setChannel(intOf(doc, 1, "channelID", "ChannelID", "channelId"));
            event.setEnter(intOf(doc, 0, "enterCount", "entryCount", "enter", "Enter", "enterNum"));
            event.setExit(intOf(doc, 0, "leaveCount", "exit", "Exit", "exitNum"));
            return event;

        } catch (Exception e) {
            LOGGER.warn("HIK error parseando XML deviceId={}: {}", deviceId, e.getMessage());
            return null;
        }
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
}
