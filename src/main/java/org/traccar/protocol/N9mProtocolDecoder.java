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
package org.traccar.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.BaseProtocolDecoder;
import org.traccar.NetworkMessage;
import org.traccar.database.N9mDeviceRegistry;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.session.ConnectionManager;

import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;

/**
 * N9M control-channel protocol decoder (see {@link N9mFrameDecoder} for the wire framing).
 *
 * Deliberately never calls {@code getDeviceSession()} — devices are resolved via
 * {@link N9mDeviceRegistry} (keyed by the N9M-specific {@code DSNO}, stored as the device's
 * {@code n9mSerial} attribute) instead of Traccar's standard uniqueId-based session lookup. This
 * avoids fighting over command-routing "ownership" with any other protocol the device might also
 * speak — see {@link N9mProtocol} for the reasoning. Returning a {@link Position} from
 * {@code decode()} is enough for it to flow through Traccar's normal save/filter/report pipeline
 * regardless of session ownership.
 *
 * Passenger counting (MODULE=DEVEMM, OPERATION=UPPSTATISTICS) IS ingested here — for DVR units
 * configured with N9M as their only server (contractually this integration is CCTV/DVR + passenger
 * count audit only, not fleet tracking, so JT808 is not necessarily present at all), N9M is the sole
 * source of both passenger counts and their event-time GPS fix. Attribute names mirror
 * {@code Jt808ProtocolDecoder#decodeStreamaxCounting} exactly (passengersOn/Off, per-door suffixed
 * variants, apc.forceDoor override) so downstream reports work identically regardless of transport.
 */
public class N9mProtocolDecoder extends BaseProtocolDecoder {

    private static final Logger LOGGER = LoggerFactory.getLogger(N9mProtocolDecoder.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter N9M_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private static final int HEADER_LENGTH = 12;

    private final N9mProtocol n9mProtocol;
    private N9mDeviceRegistry deviceRegistry;
    private ConnectionManager n9mConnectionManager;

    public N9mProtocolDecoder(N9mProtocol protocol) {
        super(protocol);
        this.n9mProtocol = protocol;
    }

    @Inject
    public void setDeviceRegistry(N9mDeviceRegistry deviceRegistry) {
        this.deviceRegistry = deviceRegistry;
    }

    // NOTE: deliberately NOT named setConnectionManager/@Inject-overriding BaseProtocolDecoder's own
    // setConnectionManager(ConnectionManager) — doing that once caused Guice to call only this override
    // and never the superclass's, leaving BaseProtocolDecoder's own connectionManager field null and
    // crashing onMessageEvent() with an NPE on every message.
    @Inject
    public void setN9mConnectionManager(ConnectionManager connectionManager) {
        this.n9mConnectionManager = connectionManager;
    }

    /**
     * N9M deliberately never calls {@code getDeviceSession()} (see class javadoc), which is the usual
     * mechanism that marks a device online — so for devices where N9M is the only server configured
     * (no JT808 in parallel, per this project's current scope), nothing else would ever do it. Called
     * on every message where a deviceId is already resolved (CONNECT, KEEPALIVE, periodic telemetry).
     */
    private void markOnline(long deviceId) {
        if (n9mConnectionManager != null) {
            n9mConnectionManager.updateDevice(deviceId, Device.STATUS_ONLINE, new Date());
        }
    }

    /**
     * {@code getDeviceSession()} is also the mechanism that attributes a TCP channel's byte counters to
     * a deviceId in {@link org.traccar.datausage.DataUsageManager} — without this, {@code
     * DataUsageChannelHandler} would keep piling up bytes on the channel (it's inserted unconditionally
     * for every TCP server, N9M's control server included) but never credit them to anyone, silently
     * excluding all N9M control traffic from SIM data-usage tracking. Called once per message wherever a
     * deviceId is already resolved, mirroring {@link Jt1078ProtocolDecoder}'s own explicit flush for the
     * same reason.
     */
    private void flushDataUsage(Channel channel, long deviceId) {
        if (getDataUsageManager() != null && channel != null) {
            getDataUsageManager().flushChannel(channel, deviceId, true);
        }
    }

    public static ByteBuf formatMessage(String json) {
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        ByteBuf buf = Unpooled.buffer(HEADER_LENGTH + jsonBytes.length);
        buf.writeByte(0x08);
        buf.writeZero(5);
        buf.writeShort(jsonBytes.length);
        buf.writeByte(0x52);
        buf.writeZero(3);
        buf.writeBytes(jsonBytes);
        return buf;
    }

    private void sendResponse(Channel channel, SocketAddress remoteAddress, String json) {
        if (channel != null) {
            channel.writeAndFlush(new NetworkMessage(formatMessage(json), remoteAddress));
        }
    }

    @Override
    protected Object decode(
            Channel channel, SocketAddress remoteAddress, Object msg) throws Exception {

        ByteBuf buf = (ByteBuf) msg;

        buf.skipBytes(6); // marker byte + 5 reserved bytes
        int length = buf.readUnsignedShort();
        buf.skipBytes(4); // 'R' marker byte + 3 reserved bytes

        if (buf.readableBytes() < length) {
            return null;
        }
        byte[] payload = new byte[length];
        buf.readBytes(payload);
        String json = new String(payload, StandardCharsets.UTF_8).trim();

        if (json.isEmpty() || json.charAt(0) != '{') {
            return null; // non-JSON binary ping frames observed on this channel — ignored for now
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            LOGGER.warn("N9M failed to parse JSON: {}", json, e);
            return null;
        }

        String module = root.path("MODULE").asText(null);
        String operation = root.path("OPERATION").asText(null);
        String session = root.path("SESSION").asText("");

        // Covers every branch below except CONNECT itself (channel isn't registered yet at this point
        // on the very first message — that case is flushed explicitly right after registration).
        Long sessionDeviceId = n9mProtocol.getDeviceIdForChannel(channel);
        if (sessionDeviceId != null) {
            flushDataUsage(channel, sessionDeviceId);
        }

        if ("CERTIFICATE".equals(module) && "CONNECT".equals(operation)) {
            String dsno = root.path("PARAMETER").path("DSNO").asText(null);
            Long deviceId = deviceRegistry != null ? deviceRegistry.lookup(dsno) : null;
            if (deviceId == null) {
                LOGGER.warn("N9M CONNECT from unknown DSNO={}", dsno);
                return null;
            }
            n9mProtocol.registerControlChannel(deviceId, channel, remoteAddress);
            markOnline(deviceId);
            flushDataUsage(channel, deviceId);
            try {
                // Populates CacheManager's device object cache WITHOUT calling getDeviceSession() —
                // addDevice()/removeDevice() are independent of DeviceSession/command routing, they
                // just make getCacheManager().getObject(Device.class, id) work. Needed because
                // BaseProtocolEncoder.write() unconditionally reads the device from that cache for its
                // own logging on every outgoing command, and would otherwise NPE for N9M-only devices
                // (nothing else ever populates that cache for them).
                getCacheManager().addDevice(deviceId, channel);
            } catch (Exception e) {
                LOGGER.warn("N9M failed to add device {} to cache", deviceId, e);
            }
            LOGGER.info("N9M CONNECT deviceId={} dsno={}", deviceId, dsno);
            sendResponse(channel, remoteAddress, "{\"MODULE\":\"CERTIFICATE\",\"OPERATION\":\"CONNECT\","
                    + "\"RESPONSE\":{\"ERRORCAUSE\":\"\",\"ERRORCODE\":0},\"SESSION\":\"" + session + "\"}");
            // Ask for the per-channel video config (frame rate, resolution, bitrate) — mirrors the
            // official server's own post-CONNECT "discovery" sequence (passive capture 2026-08-17).
            // The FR (frame rate) field is what lets N9mMediaProtocolDecoder synthesize correct
            // playback-speed PTS values and size how much video a download needs to pull before
            // self-stopping, since raw media frames carry no timestamp of their own.
            sendResponse(channel, remoteAddress, "{\"MODULE\":\"CONFIGMODEL\",\"OPERATION\":\"GET\","
                    + "\"PARAMETER\":{\"MDVR\":{\"TIMEP\":\"?\",\"MAIN\":\"?\",\"DOSD\":\"?\"}},"
                    + "\"SESSION\":\"" + session + "\"}");
            return null;
        }

        if ("CERTIFICATE".equals(module) && "KEEPALIVE".equals(operation)) {
            Long keepaliveDeviceId = n9mProtocol.getDeviceIdForChannel(channel);
            if (keepaliveDeviceId != null) {
                markOnline(keepaliveDeviceId);
            }
            sendResponse(channel, remoteAddress, "{\"MODULE\":\"CERTIFICATE\",\"OPERATION\":\"KEEPALIVE\","
                    + "\"RESPONSE\":{\"ERRORCAUSE\":\"\",\"ERRORCODE\":0},\"SESSION\":\"" + session + "\"}");
            return null;
        }

        if (module == null && root.has("TASKID")) {
            Long taskDeviceId = n9mProtocol.getDeviceIdForChannel(channel);
            String taskId = root.path("TASKID").asText("");
            sendResponse(channel, remoteAddress, "{\"TASKID\":\"" + taskId + "\",\"STATUS\":0}");
            if (taskDeviceId == null) {
                return null;
            }
            markOnline(taskDeviceId);
            return decodeTelemetry(taskDeviceId, root);
        }

        if ("DEVEMM".equals(module) && "UPPSTATISTICS".equals(operation)) {
            Long deviceId = n9mProtocol.getDeviceIdForChannel(channel);
            if (deviceId == null) {
                LOGGER.warn("N9M UPPSTATISTICS on a channel with no registered CONNECT yet, dropped: {}", json);
                return null;
            }
            markOnline(deviceId);
            JsonNode parameter = root.path("PARAMETER");
            int cmdNo = parameter.path("CMDNO").asInt(0);
            sendResponse(channel, remoteAddress, "{\"MODULE\":\"DEVEMM\",\"SESSION\":\"" + session + "\","
                    + "\"OPERATION\":\"UPPSTATISTICS\",\"RESPONSE\":{\"ERRORCODE\":0,\"ERRORCAUSE\":\"\","
                    + "\"CMDNO\":" + cmdNo + "}}");
            return decodeCounting(deviceId, parameter);
        }

        if ("STORM".equals(module) && "QUERYFILELIST".equals(operation) && root.has("RESPONSE")) {
            JsonNode responseNode = root.path("RESPONSE");
            int serial = responseNode.path("SERIAL").asInt(-1);
            n9mProtocol.completeFileListQuery(serial, responseNode);
            return null;
        }

        if ("MEDIASTREAMMODEL".equals(module) && root.has("RESPONSE")
                && ("REQUESTALIVEVIDEO".equals(operation) || "REQUESTREMOTEPLAYBACK".equals(operation)
                || "CONTROLREMOTEPLAYBACK".equals(operation))) {
            // Confirmed live 2026-08-19: a rejected request (e.g. "TASK FULL") was previously only
            // visible as a DEBUG "unhandled message" line — easy to miss while debugging why a live
            // view or download silently never produced any video. Failures are now promoted to WARN;
            // successes are left quiet (the media connection actually opening is the real confirmation).
            JsonNode responseNode = root.path("RESPONSE");
            int errorCode = responseNode.path("ERRORCODE").asInt(-1);
            if (errorCode != 0) {
                LOGGER.warn("N9M {} REJECTED deviceId={} errorCode={} errorCause={}",
                        operation, n9mProtocol.getDeviceIdForChannel(channel), errorCode,
                        responseNode.path("ERRORCAUSE").asText(""));
            }
            return null;
        }

        if ("CONFIGMODEL".equals(module) && "GET".equals(operation)) {
            // Answers to our own query come back with the actual config filled into PARAMETER (the
            // same field we sent "?" placeholders in), not a RESPONSE envelope like other commands —
            // confirmed live 2026-08-18/19.
            Long deviceId = n9mProtocol.getDeviceIdForChannel(channel);
            if (deviceId != null) {
                decodeChannelConfig(deviceId, root.path("PARAMETER"));
            }
            return null;
        }

        if ("DEVEMM".equals(module) && "DISPATHERPROXYMSG".equals(operation)) {
            // Passive capture only — this is the remote-config tunnel PT Cloud uses to administer the
            // MDVR (camera calibration, network, time, etc.), see the passive-capture notes. The
            // tunneled payload was never decoded from the original capture, only its outer envelope
            // (PARAMETER.A direction, DT type, L length, M multiplex id) — logged at INFO (not DEBUG
            // like other unhandled messages below) specifically so it's easy to find whenever this
            // fires again, e.g. while navigating the MDVR's own remote-config UI, to analyze further.
            LOGGER.info("N9M DISPATHERPROXYMSG (remote-config tunnel, payload format unconfirmed): {}", json);
            return null;
        }

        if ("UPDATEIOSTATUSINFO".equalsIgnoreCase(operation)) {
            // UNCONFIRMED against this device — never seen in our own passive capture or live testing
            // (ignition/ACC never toggled while connected). Operation name and the ACC/ignition field
            // ("ACC" or short "A") come from a third-party protocol implementation's documentation
            // (flespi), not a real captured sample, so the exact MODULE and field name are a best
            // guess — matched on OPERATION alone regardless of MODULE to hedge against that. Needs
            // confirming by toggling the vehicle's ignition while connected and checking this log line.
            Long deviceId = n9mProtocol.getDeviceIdForChannel(channel);
            if (deviceId == null) {
                return null;
            }
            markOnline(deviceId);
            sendResponse(channel, remoteAddress, "{\"MODULE\":\"" + module + "\",\"OPERATION\":\"" + operation
                    + "\",\"RESPONSE\":{\"ERRORCODE\":0,\"ERRORCAUSE\":\"\"},\"SESSION\":\"" + session + "\"}");
            return decodeIoStatus(deviceId, root.path("PARAMETER"), json);
        }

        if (containsLikelyPositionField(json)) {
            // Same "unconfirmed, flag it" reasoning as UPDATEIOSTATUSINFO above, but for continuous GPS
            // position reporting outside a counting event — a third-party protocol implementation
            // (flespi) documents one existing, but without the raw MODULE/OPERATION/field names, so
            // there's nothing concrete to decode yet. Promoted to INFO (unlike the plain unhandled-
            // message catch-all below) so it's easy to spot if this ever actually appears.
            LOGGER.info("N9M unhandled message that may carry position data, module={} operation={} json={}",
                    module, operation, json);
            return null;
        }

        LOGGER.debug("N9M unhandled message module={} operation={} json={}", module, operation, json);
        return null;
    }

    private boolean containsLikelyPositionField(String json) {
        String upper = json.toUpperCase();
        return upper.contains("\"LAT") || upper.contains("\"LON") || upper.contains("\"SPEED")
                || upper.contains("\"COURSE") || upper.contains("\"DIRECTION");
    }

    /**
     * Parses the CONFIGMODEL/GET response for the per-channel {@code FR} (frame rate) field and stores
     * it via {@link N9mProtocol#setChannelFps}. Response nesting isn't 100% pinned down from the
     * passive capture (only the field names were confirmed, not a full real RESPONSE sample), so this
     * tries a couple of plausible paths for the MAIN array and logs the raw response at INFO the first
     * time so the actual shape can be confirmed/adjusted against a real device.
     */
    private void decodeChannelConfig(long deviceId, JsonNode response) {
        JsonNode main = response.path("MDVR").path("MAIN");
        if (!main.isArray() || main.isEmpty()) {
            main = response.path("MAIN");
        }
        if (!main.isArray() || main.isEmpty()) {
            LOGGER.info("N9M CONFIGMODEL GET response (no MAIN array found) deviceId={} response={}",
                    deviceId, response);
            return;
        }
        int found = 0;
        for (JsonNode entry : main) {
            int channelNumber = entry.path("LCN").asInt(-1);
            double fps = entry.path("FR").asDouble(0);
            if (channelNumber > 0 && fps > 0) {
                n9mProtocol.setChannelFps(deviceId, channelNumber, fps);
                found++;
            }
        }
        LOGGER.info("N9M CONFIGMODEL channel FPS discovered deviceId={} channels={}", deviceId, found);
    }

    /**
     * Decodes the periodic {@code TASKID}-wrapped telemetry reports (device health: storage, camera
     * status, recording status, temperature, voltage, faults, comm channel status, GPS satellite
     * count) into a {@link Position} — previously these were only ACKed and their content discarded.
     * There is no live GPS fix in these reports (N9M only carries position pegged to counting events,
     * see {@code decodeCounting}), so the last known location is inherited, same as JT808 heartbeat
     * reports without a fix. Field names/scale for TEMP and VOLTAGE weren't confirmed against a real
     * numeric sample during the passive capture, so raw values are stored as-is rather than guessing a
     * unit conversion that could actively mislead.
     */
    private Position decodeTelemetry(long deviceId, JsonNode root) {
        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceId);

        long epochSeconds = root.path("TIME").asLong(0);
        Date time = epochSeconds > 0 ? new Date(epochSeconds * 1000) : new Date();
        getLastLocation(position, time);

        if (root.path("GPS").has("PLANNET")) {
            position.set("streamax.satellites", root.path("GPS").path("PLANNET").asInt());
        }

        for (JsonNode slot : root.path("STORAGE")) {
            int no = slot.path("NO").asInt(0);
            position.set("streamax.storage" + no + "FreeSize", slot.path("FREESIZE").asLong(0));
            position.set("streamax.storage" + no + "Status", slot.path("STATUS").asInt(0));
            if (slot.has("SURPLUSTIME")) {
                position.set("streamax.storage" + no + "SurplusTime", slot.path("SURPLUSTIME").asInt());
            }
        }

        if (root.has("CAMERA")) {
            position.set("streamax.cameraChannels", root.path("CAMERA").path("CHN").asInt(0));
            position.set("streamax.cameraStatus", root.path("CAMERA").path("STATUS").asInt(0));
        }

        for (JsonNode group : root.path("RECORD")) {
            position.set("streamax.record" + group.path("GROUP").asInt(0) + "Status", group.path("STATUS").asInt(0));
        }

        JsonNode temp = root.path("TEMP");
        temp.fieldNames().forEachRemaining(name -> position.set("streamax.temp" + name, temp.path(name).asInt(0)));

        JsonNode voltage = root.path("VOLTAGE");
        voltage.fieldNames().forEachRemaining(
                name -> position.set("streamax.voltage" + name, voltage.path(name).asInt(0)));

        if (root.has("FAULT")) {
            position.set("streamax.faultType", root.path("FAULT").path("TYPE").asInt(0));
            if (root.path("FAULT").path("RECORDFAULT").isArray()) {
                position.set("streamax.faultRecordCount", root.path("FAULT").path("RECORDFAULT").size());
            }
        }

        for (JsonNode status : root.path("DEVSTATUS")) {
            position.set("streamax.devStatus" + status.path("TYPE").asInt(0) + "_" + status.path("ADDR").asInt(0),
                    status.path("STATUS").asInt(0));
        }

        for (JsonNode comm : root.path("COMMUNICATION")) {
            int no = comm.path("NO").asInt(0);
            if (comm.has("EN")) {
                position.set("streamax.comm" + no + "Enabled", comm.path("EN").asInt(0) == 1);
            }
            if (comm.has("ICCID")) {
                position.set("streamax.iccid", comm.path("ICCID").asText());
            }
        }

        position.set("streamax.status", "telemetry");
        position.set("streamax.source", "n9m");

        return position;
    }

    /**
     * Best-effort decode of the speculative {@code UPDATEIOSTATUSINFO} operation — see the caller's
     * javadoc for why this is unconfirmed. Tries both a plain {@code ACC} field and the short
     * {@code A} form as candidate ignition keys; logs the raw JSON at INFO either way so the real
     * shape can be confirmed/corrected the first time this actually fires.
     */
    private Position decodeIoStatus(long deviceId, JsonNode parameter, String rawJson) {
        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceId);
        getLastLocation(position, new Date());

        JsonNode accNode = parameter.has("ACC") ? parameter.path("ACC") : parameter.path("A");
        if (!accNode.isMissingNode()) {
            position.set(Position.KEY_IGNITION, accNode.asInt(0) != 0);
        }

        position.set("streamax.status", "iostatus");
        position.set("streamax.source", "n9m");
        LOGGER.info("N9M UPDATEIOSTATUSINFO deviceId={} raw={}", deviceId, rawJson);

        return position;
    }

    private Position decodeCounting(long deviceId, JsonNode parameter) {
        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceId);

        JsonNode gps = parameter.path("P");
        Double lat = parseDoubleOrNull(gps.path("W").asText(null));
        Double lon = parseDoubleOrNull(gps.path("J").asText(null));

        Date fixTime = parseN9mTime(gps.path("T").asText(null), deviceId);

        if (lat != null && lon != null) {
            position.setValid(true);
            position.setLatitude(lat);
            position.setLongitude(lon);
            position.setTime(fixTime != null ? fixTime : new Date());
        } else {
            getLastLocation(position, fixTime);
        }

        int eventOn = parameter.path("UPP").asInt(0);
        int eventOff = parameter.path("DOWNP").asInt(0);
        int rawDoorId = parameter.path("DOORID").asInt(0);

        String overrideDoor = null;
        Device device = getCacheManager() != null ? getCacheManager().getObject(Device.class, deviceId) : null;
        if (device != null && device.hasAttribute("apc.forceDoor")) {
            overrideDoor = device.getString("apc.forceDoor");
        }

        String suffix;
        if ("front".equalsIgnoreCase(overrideDoor)) {
            suffix = "Front";
        } else if ("rear".equalsIgnoreCase(overrideDoor)) {
            suffix = "Rear";
        } else {
            suffix = (rawDoorId <= 1) ? "Front" : "Rear";
        }

        position.set("passengersOn", eventOn);
        position.set("passengersOff", eventOff);
        position.set("passengersOn" + suffix, eventOn);
        position.set("passengersOff" + suffix, eventOff);

        position.set("streamax.doorId", rawDoorId);
        position.set("streamax.doorEffective", suffix.toLowerCase());
        if (overrideDoor != null) {
            position.set("streamax.doorOverride", overrideDoor.toLowerCase());
        }
        position.set("streamax.eventOn", eventOn);
        position.set("streamax.eventOff", eventOff);
        position.set("streamax.status", "counting_event");
        position.set("streamax.source", "n9m");

        LOGGER.info("N9M APC EVENTO deviceId={} puerta={} on={} off={} lat={} lon={}",
                deviceId, suffix.toLowerCase(), eventOn, eventOff, lat, lon);

        return position;
    }

    private Double parseDoubleOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Date parseN9mTime(String value, long deviceId) {
        if (value == null || value.length() != 14) {
            return null;
        }
        try {
            ZoneId zone = getTimeZone(deviceId, "GMT-5").toZoneId();
            return Date.from(LocalDateTime.parse(value, N9M_TIME_FORMAT).atZone(zone).toInstant());
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        Long deviceId = n9mProtocol.getDeviceIdForChannel(ctx.channel());
        n9mProtocol.unregisterControlChannel(ctx.channel());
        if (deviceId != null) {
            getCacheManager().removeDevice(deviceId, ctx.channel());
        }
    }

}
