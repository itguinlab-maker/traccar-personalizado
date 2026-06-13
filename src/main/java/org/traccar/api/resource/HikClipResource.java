package org.traccar.api.resource;

import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.api.BaseResource;
import org.traccar.model.Device;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Descarga un clip de video desde una cámara Hikvision vía RTSP playback.
 *
 * GET /api/hikclip?deviceId=X&from=ISO&to=ISO&channel=1
 *
 * Atributos del dispositivo:
 *   hikIp       — IP de la cámara        (default: 192.168.1.64)
 *   hikUser     — usuario                 (default: admin)
 *   hikPass     — contraseña              (default: admin)
 *   hikTimezone — zona horaria de la cám  (default: America/Bogota)
 *
 * La URL RTSP que usa ffmpeg internamente:
 *   rtsp://user:pass@ip:554/Streaming/tracks/{channel*100+1}?starttime=YYYY-MM-DDTHH:mm:ssZ
 */
@Path("hikclip")
@Produces("video/mp4")
public class HikClipResource extends BaseResource {

    private static final Logger LOGGER = LoggerFactory.getLogger(HikClipResource.class);

    private static final DateTimeFormatter RTSP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    private static final DateTimeFormatter FILENAME_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    @GET
    public Response downloadClip(
            @QueryParam("deviceId") long deviceId,
            @QueryParam("from") String fromIso,
            @QueryParam("to") String toIso,
            @QueryParam("channel") @DefaultValue("1") int channel) throws Exception {

        if (deviceId <= 0 || fromIso == null || toIso == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("deviceId, from y to son requeridos").build();
        }

        permissionsService.checkPermission(Device.class, getUserId(), deviceId);

        Device device = storage.getObject(Device.class, new Request(
                new Columns.All(), new Condition.Equals("id", deviceId)));

        String hikIp   = device != null ? device.getString("hikIp",       "192.168.1.64")    : "192.168.1.64";
        String hikUser = device != null ? device.getString("hikUser",      "admin")           : "admin";
        String hikPass = device != null ? device.getString("hikPass",      "admin")           : "admin";
        String hikTz   = device != null ? device.getString("hikTimezone",  "America/Bogota")  : "America/Bogota";

        ZoneId zone;
        try {
            zone = ZoneId.of(hikTz);
        } catch (Exception e) {
            LOGGER.warn("HIK invalid hikTimezone '{}', using America/Bogota", hikTz);
            zone = ZoneId.of("America/Bogota");
        }

        Instant fromInstant = Instant.parse(fromIso);
        Instant toInstant   = Instant.parse(toIso);
        long clipSeconds    = Math.min(120, Math.max(5, Duration.between(fromInstant, toInstant).getSeconds()));

        // Track ID: canal 1 → 101, canal 2 → 201
        int trackId   = channel * 100 + 1;
        String startTime = RTSP_FMT.withZone(zone).format(fromInstant);
        String rtspUrl   = "rtsp://" + hikUser + ":" + hikPass + "@" + hikIp
                + ":554/Streaming/tracks/" + trackId + "?starttime=" + startTime;

        LOGGER.info("HIK CLIP deviceId={} ch={} trackId={} startTime={} duration={}s",
                deviceId, channel, trackId, startTime, clipSeconds);

        final String finalRtsp    = rtspUrl;
        final long   finalClipSec = clipSeconds;

        StreamingOutput out = output -> {
            java.nio.file.Path tmpFile = Files.createTempFile("hik_clip_", ".mp4");
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        "ffmpeg",
                        "-rtsp_transport", "tcp",
                        "-i", finalRtsp,
                        "-t", String.valueOf(finalClipSec),
                        "-c", "copy",
                        "-movflags", "+faststart",
                        "-y", tmpFile.toString()
                );

                Process ffmpeg = pb.start();

                Thread errThread = new Thread(() -> {
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(ffmpeg.getErrorStream()))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            LOGGER.debug("ffmpeg-hik: {}", line);
                        }
                    } catch (IOException ignored) { }
                });
                errThread.setDaemon(true);
                errThread.start();

                int exit = ffmpeg.waitFor();
                long fileSize = Files.size(tmpFile);
                LOGGER.info("HIK CLIP ffmpeg exit={} size={}", exit, fileSize);

                try (InputStream fis = Files.newInputStream(tmpFile)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = fis.read(buf)) != -1) {
                        output.write(buf, 0, n);
                    }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrumpido durante ffmpeg", ie);
            } finally {
                Files.deleteIfExists(tmpFile);
            }
        };

        String dateStr  = FILENAME_FMT.withZone(zone).format(fromInstant);
        String filename = "hik_ch" + channel + "_" + dateStr + ".mp4";

        return Response.ok(out)
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .build();
    }
}
