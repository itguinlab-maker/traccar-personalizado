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

import io.netty.channel.Channel;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.BaseProtocol;
import org.traccar.BaseProtocolEncoder;
import org.traccar.config.Keys;
import org.traccar.helper.model.AttributeUtil;
import org.traccar.model.Command;
import org.traccar.model.Device;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Builds outgoing N9M control-channel commands. See {@link N9mProtocolDecoder} for the wire framing
 * (shares its {@code formatMessage} helper) and {@link N9mProtocol} for how these reach the device
 * without going through the standard {@code DeviceSession} routing.
 */
public class N9mProtocolEncoder extends BaseProtocolEncoder {

    private static final Logger LOGGER = LoggerFactory.getLogger(N9mProtocolEncoder.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String STREAM_ID_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final N9mProtocol n9mProtocol;
    private Storage storage;

    public N9mProtocolEncoder(N9mProtocol protocol) {
        super(protocol);
        this.n9mProtocol = protocol;
    }

    @Inject
    public void setStorage(Storage storage) {
        this.storage = storage;
    }

    private static String randomStreamSuffix() {
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            sb.append(STREAM_ID_CHARS.charAt(RANDOM.nextInt(STREAM_ID_CHARS.length())));
        }
        return sb.toString();
    }

    private static String randomSession() {
        StringBuilder sb = new StringBuilder(32);
        String hex = "0123456789ABCDEF";
        for (int i = 0; i < 32; i++) {
            sb.append(hex.charAt(RANDOM.nextInt(hex.length())));
        }
        return sb.toString();
    }

    /**
     * Reads the device directly from storage rather than {@code getCacheManager().getObject(...)} —
     * that cache is only populated via the {@code getDeviceSession()}/{@code addDevice()} lifecycle,
     * which N9M deliberately never triggers (see {@link N9mProtocolDecoder} javadoc), so it would
     * always be empty for N9M-only devices.
     */
    private String getDsno(long deviceId) {
        try {
            Device device = storage.getObject(Device.class, new Request(
                    new Columns.All(), new Condition.Equals("id", deviceId)));
            return device != null ? device.getString("n9mSerial") : null;
        } catch (Exception e) {
            LOGGER.warn("N9M failed to look up device {}", deviceId, e);
            return null;
        }
    }

    private String getMediaHost() {
        var config = getCacheManager().getConfig();
        if (config.hasKey(Keys.N9M_SERVER_HOST)) {
            return config.getString(Keys.N9M_SERVER_HOST);
        }
        return URI.create(config.getString(Keys.WEB_URL)).getHost();
    }

    private int getMediaPort() {
        return getCacheManager().getConfig().getInteger(
                Keys.PROTOCOL_PORT.withPrefix(BaseProtocol.nameFromClass(N9mMediaProtocol.class)));
    }

    @Override
    protected Object encodeCommand(Channel channel, Command command) {

        String dsno = getDsno(command.getDeviceId());
        if (dsno == null || dsno.isBlank()) {
            LOGGER.warn("N9M command {} skipped: deviceId={} has no n9mSerial attribute",
                    command.getType(), command.getDeviceId());
            return null;
        }

        int channelIndex = command.getInteger(Command.KEY_INDEX, 1);
        String ipAndPort = getMediaHost() + ":" + getMediaPort();

        switch (command.getType()) {
            case Command.TYPE_VIDEO_START -> {
                // Close any leftover media task first — see N9mProtocol.closeActiveMediaChannel javadoc:
                // without this, a live view that never got a clean stop leaves the device reporting
                // "TASK FULL" for ALL future video requests, including counting-event clip downloads.
                n9mProtocol.closeActiveMediaChannel(command.getDeviceId());
                // observed real traffic: CHANNEL/AUDIOVALID use a bitmask, bit(n-1) = channel n
                int channelBit = 1 << Math.max(0, channelIndex - 1);
                String streamName = "1_" + dsno + "_" + channelIndex + "_" + randomStreamSuffix();
                // Live view: no picture cap, it runs until TYPE_VIDEO_STOP.
                n9mProtocol.registerPendingStream(streamName, command.getDeviceId(), channelIndex, false, -1);
                String json = "{\"MODULE\":\"MEDIASTREAMMODEL\",\"OPERATION\":\"REQUESTALIVEVIDEO\","
                        + "\"PARAMETER\":{\"AUDIOVALID\":" + channelBit + ",\"CHANNEL\":" + channelBit + ","
                        + "\"IPANDPORT\":\"" + ipAndPort + "\",\"STREAMNAME\":\"" + streamName + "\","
                        + "\"STREAMTYPE\":0},\"SESSION\":\"" + randomSession() + "\"}";
                LOGGER.info("N9M REQUESTALIVEVIDEO deviceId={} channel={} streamName={} ipAndPort={}",
                        command.getDeviceId(), channelIndex, streamName, ipAndPort);
                return N9mProtocolDecoder.formatMessage(json);
            }
            case Command.TYPE_VIDEO_QUERY -> {
                // MODULE=STORM/OPERATION=GETCALENDAR — lists which days have recordings.
                // CHANNEL 2147483647 (0x7FFFFFFF) is the confirmed "all channels" sentinel;
                // KEY_INDEX>0 selects a single channel via the same bitmask convention as live view.
                int queryChannelIndex = command.getInteger(Command.KEY_INDEX, 0);
                int channelMask = queryChannelIndex > 0 ? (1 << (queryChannelIndex - 1)) : 2147483647;
                int serial = (int) (System.currentTimeMillis() % 1000);
                // Confirmed live against the real device (2026-08-17): RFSTORAGE=0 + STREAMTYPE=1 is
                // the ONLY combination that returns real data (COUNT:5, matching known recording days
                // 16-18 jul / 16-17 aug). RFSTORAGE=1 always fails (ERRORCODE:6); RFSTORAGE=0 with
                // STREAMTYPE=0 succeeds but returns COUNT:0. Both overridable via command attributes.
                int storage = command.getInteger("storage", 0);
                int streamType = command.getInteger("streamtype", 1);
                String json = "{\"MODULE\":\"STORM\",\"OPERATION\":\"GETCALENDAR\",\"SESSION\":\""
                        + UUID.randomUUID() + "\",\"PARAMETER\":{\"CALENDARTYPE\":2,\"SERIAL\":" + serial + ","
                        + "\"CHANNEL\":" + channelMask + ",\"TIMEZONEOFFSET\":780,\"STREAMTYPE\":" + streamType + ","
                        + "\"FILETYPE\":65535,\"RFSTORAGE\":" + storage + "}}";
                LOGGER.info("N9M GETCALENDAR deviceId={} channel={} serial={} storage={} streamType={}",
                        command.getDeviceId(), queryChannelIndex, serial, storage, streamType);
                return N9mProtocolDecoder.formatMessage(json);
            }
            // Command.TYPE_VIDEO_STOP is intentionally NOT a case here — N9mCommandSender intercepts
            // it before it ever reaches the wire (pure local cleanup, no wire message; see its
            // javadoc). BaseProtocolEncoder.write() always tries to write whatever encodeCommand()
            // returns, even null, which crashed Netty with an NPE when this used to return null here.
            case Command.TYPE_VIDEO_DOWNLOAD -> {
                // Same reasoning as TYPE_VIDEO_START: never leave a previous task occupying the
                // device's (apparently single) media task slot before requesting a new one.
                n9mProtocol.closeActiveMediaChannel(command.getDeviceId());
                String tzName = AttributeUtil.lookup(getCacheManager(), Keys.DECODER_TIMEZONE, command.getDeviceId());
                ZoneId zone = ZoneId.of(tzName != null ? tzName : "GMT-5");
                DateTimeFormatter fmt = TIME_FORMAT.withZone(zone);
                long startEpoch = command.getLong(Command.KEY_START_TIME);
                long endEpoch = command.getLong(Command.KEY_END_TIME);
                String startTime = fmt.format(Instant.ofEpochSecond(startEpoch));
                String endTime = fmt.format(Instant.ofEpochSecond(endEpoch));
                String streamName = "7_" + dsno + "_" + channelIndex + "_" + randomStreamSuffix();
                // Playback ignores our requested END time and streams recorded video far faster than
                // real-time (confirmed live 2026-08-18/19), so without a picture cap the device would
                // keep sending long after we have enough content — wasting SIM data and transfer time.
                // Sized from the channel's real FPS (learned via CONFIGMODEL, see N9mProtocolDecoder)
                // when known; falls back to unlimited (existing wall-clock safety timer only) if not.
                Double fps = n9mProtocol.getChannelFps(command.getDeviceId(), channelIndex);
                long requestedDuration = Math.max(1, endEpoch - startEpoch);
                int maxPictures = fps != null ? (int) Math.ceil(requestedDuration * fps) : -1;
                n9mProtocol.registerPendingStream(streamName, command.getDeviceId(), channelIndex, true, maxPictures);
                n9mProtocol.trackActivePlayback(command.getDeviceId(), channelIndex, streamName);
                // NOTE: CHANNEL encoding for REQUESTREMOTEPLAYBACK is not fully confirmed — the one
                // captured real sample used CHANNEL=13 (not the 1/2/4/8 bitmask seen for live view),
                // suggesting a different convention for playback. Overridable via "channel_raw" for
                // testing against the confirmed-real value while this is being pinned down.
                int playbackChannel = command.getInteger("channel_raw", channelIndex);
                String json = "{\"MODULE\":\"MEDIASTREAMMODEL\",\"OPERATION\":\"REQUESTREMOTEPLAYBACK\","
                        + "\"PARAMETER\":{\"CHANNEL\":" + playbackChannel + ",\"STARTTIME\":\"" + startTime + "\","
                        + "\"ENDTIME\":\"" + endTime + "\",\"IPANDPORT\":\"" + ipAndPort + "\","
                        + "\"STREAMNAME\":\"" + streamName + "\",\"STREAMTYPE\":1,\"VIDEOTYPE\":2,\"PBST\":0},"
                        + "\"SESSION\":\"" + randomSession() + "\"}";
                LOGGER.info("N9M REQUESTREMOTEPLAYBACK deviceId={} channel={} start={} end={} streamName={}",
                        command.getDeviceId(), channelIndex, startTime, endTime, streamName);
                return N9mProtocolDecoder.formatMessage(json);
            }
            default -> {
                return null;
            }
        }
    }

}
