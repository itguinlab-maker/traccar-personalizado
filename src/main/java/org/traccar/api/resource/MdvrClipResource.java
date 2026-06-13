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
package org.traccar.api.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * GET /api/mdvrclip?deviceId=1&channel=1&from=ISO&to=ISO&plate=ABC123&door=cam
 *
 * Confirmed MDVR download flow (reverse-engineered from web UI network capture):
 *   1. Auth      → GET /devapi/v1/basic/key?...autoLogin=1...
 *                  → key token + session cookies
 *   2. Periods   → GET /devapi/v1/basic/periodrecord?…&startDate=yy-MM-dd%20HH:mm:ss&…
 *                  IMPORTANT: colons must NOT be percent-encoded (use %20 for space only)
 *   3. Download  → POST /devapi/v1/basic/videodownload?…&periodId=hddId-rootId-segId
 *                  → Streamax proprietary container:
 *                    – ~672-byte file header before the first SPS NAL
 *                    – Periodic fake-PPS blocks (NAL type 8, pps_id > 255) interspersed
 *   4. Filter    → skipToSps() drops the file header; streamFilteredNals() removes the
 *                  fake-PPS blocks by validating pps_id via ue(v) decoding.
 *   5. ffmpeg    → re-encodes clean H.264 to MP4 (libx264, faststart, silent audio).
 *
 * Device attributes (optional):
 *   mdvrIp       – MDVR IP         (default: 192.168.1.11)
 *   mdvrUser     – username        (default: admin)
 *   mdvrPass     – password        (default: admin)
 *   mdvrTimezone – MDVR clock TZ   (default: GMT-5)
 */
@Path("mdvrclip")
public class MdvrClipResource extends BaseResource {

    private static final Logger LOGGER = LoggerFactory.getLogger(MdvrClipResource.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final DateTimeFormatter LOCAL_FMT =
            DateTimeFormatter.ofPattern("yy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILENAME_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private static final byte[] H264_START_CODE = {0x00, 0x00, 0x00, 0x01};

    @GET
    @Produces("video/mp4")
    public Response downloadClip(
            @QueryParam("deviceId") long deviceId,
            @QueryParam("channel") @DefaultValue("1") int channel,
            @QueryParam("from") String fromIso,
            @QueryParam("to") String toIso,
            @QueryParam("plate") @DefaultValue("") String plate,
            @QueryParam("door") @DefaultValue("cam") String door,
            @QueryParam("ip") @DefaultValue("") String ipOverride) throws Exception {

        LOGGER.info("MDVR CLIP deviceId={} ch={} from={} to={} plate={} door={} ip={}",
                deviceId, channel, fromIso, toIso, plate, door,
                ipOverride.isEmpty() ? "(device attr)" : ipOverride);

        if (deviceId <= 0 || fromIso == null || toIso == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("deviceId, from and to are required").build();
        }

        permissionsService.checkPermission(Device.class, getUserId(), deviceId);

        Device device = storage.getObject(Device.class, new Request(
                new Columns.All(), new Condition.Equals("id", deviceId)));

        String mdvrIp = (!ipOverride.isBlank())
                ? ipOverride
                : (device != null ? device.getString("mdvrIp", "192.168.1.11") : "192.168.1.11");
        String mdvrUser = device != null ? device.getString("mdvrUser",     "admin")        : "admin";
        String mdvrPass = device != null ? device.getString("mdvrPass",     "admin")        : "admin";
        String tzName   = device != null ? device.getString("mdvrTimezone", "GMT-5")        : "GMT-5";

        ZoneId mdvrZone;
        try {
            mdvrZone = ZoneId.of(tzName);
        } catch (Exception e) {
            LOGGER.warn("Invalid mdvrTimezone '{}', falling back to GMT-5", tzName);
            mdvrZone = ZoneId.of("GMT-5");
        }

        Instant fromInstant = Instant.parse(fromIso);
        Instant toInstant   = Instant.parse(toIso);
        long clipSeconds    = Math.min(60, Math.max(1, Duration.between(fromInstant, toInstant).getSeconds()));

        DateTimeFormatter localFmt = LOCAL_FMT.withZone(mdvrZone);
        String mdvrFrom = localFmt.format(fromInstant).replace(" ", "%20");
        String mdvrTo   = localFmt.format(toInstant).replace(" ", "%20");
        LOGGER.info("MDVR time window: {} → {} ({} s)", mdvrFrom, mdvrTo, clipSeconds);

        String baseUrl = "http://" + mdvrIp;

        // ── 1. Authenticate ──────────────────────────────────────────────────────
        CookieManager cookieManager = new CookieManager();
        HttpClient http = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        String authUrl = baseUrl + "/devapi/v1/basic/key?username=" + enc(mdvrUser)
                + "&password=" + enc(mdvrPass) + "&language=1&autoLogin=1&pwenc=0";
        HttpResponse<String> authResp = http.send(
                HttpRequest.newBuilder(URI.create(authUrl))
                        .GET().timeout(Duration.ofSeconds(15)).build(),
                HttpResponse.BodyHandlers.ofString());
        LOGGER.info("MDVR auth status={} body={}", authResp.statusCode(), authResp.body());

        @SuppressWarnings("unchecked")
        Map<String, Object> authBody = MAPPER.readValue(authResp.body(), Map.class);
        String authKey = "";
        Object authData = authBody.get("data");
        if (authData instanceof Map<?, ?> authDataMap) {
            Object keyVal = authDataMap.get("key");
            if (keyVal instanceof String s && !s.isBlank()) {
                authKey = s;
            }
        }
        LOGGER.info("MDVR authKey={}", authKey);
        String keyParam = authKey.isEmpty() ? "" : "&key=" + authKey;

        // ── 2. Query recording periods ───────────────────────────────────────────
        String periodUrl = baseUrl + "/devapi/v1/basic/periodrecord"
                + "?group=0&stream=0&channelMask=63"
                + "&startDate=" + mdvrFrom
                + "&endDate=" + mdvrTo
                + "&type=3"
                + keyParam
                + "&_=" + System.currentTimeMillis();

        LOGGER.info("MDVR periodrecord url={}", periodUrl);
        HttpResponse<String> periodResp = http.send(
                HttpRequest.newBuilder(URI.create(periodUrl))
                        .GET()
                        .header("Referer", baseUrl + "/pages/playback/default.html")
                        .timeout(Duration.ofSeconds(15))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        LOGGER.info("MDVR periodrecord status={} body={}", periodResp.statusCode(), periodResp.body());

        @SuppressWarnings("unchecked")
        Map<String, Object> periodBody = MAPPER.readValue(periodResp.body(), Map.class);
        Object errCode = periodBody.get("errorcode");
        if (!(errCode instanceof Number ec) || ec.intValue() != 200) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("No hay grabación para el canal " + channel + " en ese rango de tiempo")
                    .build();
        }

        int targetChannel = channel - 1; // MDVR is 0-based, caller is 1-based
        List<Map<String, Object>> segments = findAllSegments(periodBody, targetChannel);
        if (segments.isEmpty()) {
            LOGGER.warn("MDVR no segments for channel {} in: {}", targetChannel, periodResp.body());
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("No hay grabación para el canal " + channel + " en ese rango de tiempo")
                    .build();
        }
        LOGGER.info("MDVR found {} segment(s) for channel {}", segments.size(), targetChannel);

        List<String> downloadUrls = new java.util.ArrayList<>();
        for (Map<String, Object> segment : segments) {
            int hddId  = ((Number) segment.get("hddid")).intValue();
            int rootId = ((Number) segment.get("rootindex")).intValue();
            int segId  = ((Number) segment.get("streamsegmentindex")).intValue();
            String url = baseUrl + "/devapi/v1/basic/videodownload"
                    + "?recordtype=2&streamtype=0"
                    + "&hddId=" + hddId
                    + "&rootId=" + rootId
                    + "&streamSegId=" + segId
                    + "&starttime=" + mdvrFrom
                    + "&endtime=" + mdvrTo
                    + "&periodId=" + hddId + "-" + rootId + "-" + segId
                    + keyParam;
            downloadUrls.add(url);
            LOGGER.info("MDVR segment hddId={} rootId={} segId={} url={}", hddId, rootId, segId, url);
        }

        final HttpClient        finalHttp     = http;
        final List<String>      finalUrls     = downloadUrls;
        final String            finalBaseUrl  = baseUrl;
        final long              finalClipSecs = clipSeconds;

        // ── 4. Stream ALL segments: filter NALs → ffmpeg → MP4 ───────────────────
        StreamingOutput out = output -> {
            java.nio.file.Path tmpFile = Files.createTempFile("mdvr_clip_", ".mp4");
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        "ffmpeg",
                        "-f", "h264", "-r", "30", "-i", "pipe:0",
                        "-f", "lavfi", "-i", "anullsrc=r=44100:cl=mono",
                        "-c:v", "copy",
                        "-c:a", "aac", "-b:a", "32k", "-t", String.valueOf(finalClipSecs),
                        "-movflags", "+faststart",
                        "-y", tmpFile.toString()
                );

                Process ffmpeg = pb.start();

                // Feed ALL segments sequentially into ffmpeg stdin
                Thread pipeThread = new Thread(() -> {
                    try (OutputStream ffIn = ffmpeg.getOutputStream()) {
                        for (String segUrl : finalUrls) {
                            HttpResponse<InputStream> videoResp;
                            try {
                                videoResp = finalHttp.send(
                                        HttpRequest.newBuilder(URI.create(segUrl))
                                                .POST(HttpRequest.BodyPublishers.noBody())
                                                .header("Referer", finalBaseUrl + "/pages/playback/default.html")
                                                .timeout(Duration.ofSeconds(300))
                                                .build(),
                                        HttpResponse.BodyHandlers.ofInputStream());
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                            LOGGER.info("MDVR seg download status={}", videoResp.statusCode());
                            try (InputStream mdvrStream = new BufferedInputStream(videoResp.body(), 65536)) {
                                byte[] spsStart = skipToSps(mdvrStream);
                                if (spsStart == null) {
                                    LOGGER.warn("H.264 SPS not found in segment, skipping");
                                    continue;
                                }
                                streamFilteredNals(spsStart, mdvrStream, ffIn);
                            }
                        }
                    } catch (IOException e) {
                        String msg = e.getMessage();
                        if (msg == null || (!msg.contains("Broken pipe") && !msg.contains("Stream closed"))) {
                            LOGGER.warn("MDVR piper: {}", msg);
                        }
                    }
                });
                pipeThread.setName("mdvr-piper");
                pipeThread.start();

                // Drain ffmpeg stderr to avoid blocking
                Thread errThread = new Thread(() -> {
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(ffmpeg.getErrorStream()))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            LOGGER.info("ffmpeg: {}", line);
                        }
                    } catch (IOException ignored) { }
                });
                errThread.setDaemon(true);
                errThread.start();

                int exit = ffmpeg.waitFor();
                pipeThread.join(5000);

                long fileSize = Files.size(tmpFile);
                LOGGER.info("MDVR CLIP ffmpeg exit={} fileSize={}", exit, fileSize);

                // Stream the finished MP4 to the client
                try (InputStream fis = Files.newInputStream(tmpFile)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = fis.read(buf)) != -1) {
                        output.write(buf, 0, n);
                    }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted during ffmpeg encode", ie);
            } finally {
                Files.deleteIfExists(tmpFile);
            }
        };

        String safePlate = plate.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        String safeDoor  = door.replaceAll("[^A-Za-z0-9]", "");
        String dateStr   = FILENAME_FMT.withZone(mdvrZone).format(fromInstant);
        String filename  = (safePlate.isEmpty() ? "mdvr" : safePlate)
                + "_" + safeDoor + "_" + dateStr + ".mp4";

        return Response.ok(out)
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .build();
    }

    // ── NAL unit filter ──────────────────────────────────────────────────────────

    /**
     * Reads the MDVR stream byte by byte, filters Streamax proprietary blocks,
     * and writes clean H.264 Annex B to {@code out}.
     *
     * Streamax blocks are fake PPS NAL units (type 8) with pps_id > 255.
     * They are detected by decoding the first ue(v) in the PPS payload.
     *
     * @param spsStart the 5-byte SPS start code returned by skipToSps()
     * @param in       buffered MDVR stream positioned just after spsStart
     * @param out      destination for filtered H.264 Annex B (ffmpeg stdin)
     */
    private static void streamFilteredNals(byte[] spsStart, InputStream in, OutputStream out)
            throws IOException {
        // NAL accumulator; starts with the SPS type byte (0x67)
        ByteArrayOutputStream nalBuf = new ByteArrayOutputStream(524288);
        nalBuf.write(spsStart[4]);

        int trailingZeros = 0;
        int kept = 0, dropped = 0;
        int b;

        while ((b = in.read()) != -1) {
            if (b == 0x00) {
                trailingZeros++;
                nalBuf.write(b);
            } else if (b == 0x01 && trailingZeros >= 3) {
                // Start code detected — flush the completed NAL unit
                byte[] nal = nalBuf.toByteArray();
                int nalLen = nal.length - trailingZeros; // strip the trailing 00-bytes
                if (nalLen > 0) {
                    int nalType = nal[0] & 0x1F;
                    if (isValidNal(nalType, nal, 1, nalLen - 1)) {
                        out.write(H264_START_CODE);
                        out.write(nal, 0, nalLen);
                        kept++;
                    } else {
                        LOGGER.debug("Streamax block dropped: NAL={} size={}", nalType, nalLen);
                        dropped++;
                    }
                }
                nalBuf.reset();
                trailingZeros = 0;
            } else {
                trailingZeros = 0;
                nalBuf.write(b);
            }
        }

        // Flush the final NAL unit (no trailing start code)
        byte[] nal = nalBuf.toByteArray();
        if (nal.length > 0) {
            int nalType = nal[0] & 0x1F;
            if (isValidNal(nalType, nal, 1, nal.length - 1)) {
                out.write(H264_START_CODE);
                out.write(nal);
                kept++;
            }
        }

        LOGGER.info("NAL filter: kept={} dropped={}", kept, dropped);
    }

    /**
     * Returns true when the NAL unit appears to be a legitimate H.264 unit.
     *
     * Streamax fake-PPS blocks have pps_id > 255 (detected via ue(v) decoding).
     * Slice NAL units with slice_type > 9 are also rejected.
     *
     * @param nalType    nal_unit_type (bits 4:0 of the NAL header byte)
     * @param data       full NAL buffer; NAL header byte at index 0
     * @param payloadOff byte offset of the payload within data (always 1)
     * @param payloadLen byte length of the payload
     */
    private static boolean isValidNal(int nalType, byte[] data, int payloadOff, int payloadLen) {
        if (payloadLen < 0) {
            return false;
        }
        switch (nalType) {
            case 7: // SPS
                return payloadLen >= 3;
            case 8: { // PPS — validate pps_id ≤ 255
                int[] pos = {payloadOff << 3};
                Integer ppsId = readUev(data, pos, payloadOff + payloadLen);
                return ppsId != null && ppsId <= 255;
            }
            case 1: case 2: case 3: case 4: case 5: { // Slice — validate slice_type ≤ 9
                int[] pos = {payloadOff << 3};
                int endByte = payloadOff + payloadLen;
                Integer mbAddr = readUev(data, pos, endByte);
                if (mbAddr == null) {
                    return false;
                }
                Integer sliceType = readUev(data, pos, endByte);
                return sliceType != null && sliceType <= 9;
            }
            case 6:  // SEI
            case 9:  // AUD
                return true;
            default:
                return nalType >= 10 && nalType <= 23;
        }
    }

    /**
     * Decodes a ue(v) Exp-Golomb coded integer from {@code data}.
     *
     * @param data    byte array containing the bitstream
     * @param posRef  current bit position (updated in-place on return)
     * @param endByte exclusive end byte index
     * @return decoded value, or null on EOF or too many leading zeros
     */
    private static Integer readUev(byte[] data, int[] posRef, int endByte) {
        int pos = posRef[0];
        int leadingZeros = 0;
        while ((pos >> 3) < endByte) {
            int bit = (data[pos >> 3] >> (7 - (pos & 7))) & 1;
            if (bit == 1) {
                break;
            }
            leadingZeros++;
            pos++;
            if (leadingZeros > 16) {
                return null; // pps_id ≤ 255 needs at most 8 leading zeros; >16 is invalid
            }
        }
        if ((pos >> 3) >= endByte) {
            return null; // reached end of payload without finding the '1' bit
        }
        pos++; // skip the '1' bit
        int value = (1 << leadingZeros) - 1;
        for (int i = 0; i < leadingZeros; i++) {
            if ((pos >> 3) >= endByte) {
                return null;
            }
            value += ((data[pos >> 3] >> (7 - (pos & 7))) & 1) << (leadingZeros - 1 - i);
            pos++;
        }
        posRef[0] = pos;
        return value;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /**
     * Scans the stream for the H.264 SPS NAL start code (00 00 00 01 67),
     * discarding the Streamax proprietary file header that precedes it.
     */
    private static byte[] skipToSps(InputStream in) throws IOException {
        int matched = 0;
        int b;
        while ((b = in.read()) != -1) {
            int bv = b & 0xFF;
            switch (matched) {
                case 0 -> matched = (bv == 0x00) ? 1 : 0;
                case 1 -> matched = (bv == 0x00) ? 2 : 0;
                case 2 -> matched = (bv == 0x00) ? 3 : 0;
                case 3 -> matched = (bv == 0x01) ? 4 : (bv == 0x00 ? 3 : 0);
                case 4 -> {
                    if (bv == 0x67) {
                        return new byte[]{0x00, 0x00, 0x00, 0x01, 0x67};
                    }
                    matched = (bv == 0x00) ? 1 : 0;
                }
                default -> matched = 0;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> findAllSegments(Map<String, Object> body, int targetChannel) {
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        Object data = body.get("data");
        if (!(data instanceof List<?> list)) {
            return result;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> seg) {
                Object ch = seg.get("channel");
                if (ch instanceof Number && ((Number) ch).intValue() == targetChannel) {
                    result.add((Map<String, Object>) seg);
                }
            }
        }
        return result;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
