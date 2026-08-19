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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.BaseProtocolDecoder;
import org.traccar.NetworkMessage;
import org.traccar.media.VideoClipManager;
import org.traccar.media.VideoStreamManager;

import jakarta.inject.Inject;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * N9M media (video/audio) connection decoder. See {@link N9mMediaFrameDecoder} for framing state.
 *
 * Handles the CERTIFICATE/CREATESTREAM handshake (correlating the device-chosen {@code STREAMNAME}
 * back to a deviceId/channel via {@link N9mProtocol#consumePendingStream}), then parses the raw
 * post-handshake bytes as a plain H.264 Annex-B elementary stream (confirmed live 2026-08-18 by
 * dumping ~400KB of a real live-view session to disk and scanning it: clean {@code 00 00 00 01}
 * NAL start codes, SPS(7)/PPS(8)/IDR(5) at the very start, then PPS+P-slice(1) pairs repeating every
 * frame — no RTP/SSRC wrapper despite the SSRC/CSRC/PT terminology used in the control-channel
 * MEDIATASKSTART message, which turned out to just be session bookkeeping, not a per-packet header).
 * Each NAL unit found is handed to {@code VideoStreamManager}/{@code VideoClipManager} exactly like
 * {@link Jt1078ProtocolDecoder} does, so the existing HLS/clip pipeline requires no changes.
 *
 * There is no per-NAL timestamp in a raw Annex-B stream (unlike JT1078's own header field). For
 * playback (not live view) the device also sends recorded video much faster than real-time (confirmed
 * live 2026-08-18/19 — a clip capped at ~30 wall-clock seconds came back with 7809 frames of real
 * 1080p footage), so arrival time can't be used as PTS either. Instead, PTS is synthesized from a
 * picture counter times the channel's real frame interval (1000/FPS), with FPS learned from the
 * device's own CONFIGMODEL/MAIN response (see {@link N9mProtocolDecoder}) — falling back to
 * {@link #DEFAULT_FPS} if that hasn't arrived yet. The same picture counter is used to self-close the
 * connection once a playback session has pulled enough frames for the requested clip duration (see
 * {@link N9mProtocol.PendingStream#getMaxPictures()}), instead of relying only on the caller's
 * eventual wall-clock timeout — since the device streams recorded video in bulk rather than at 1x,
 * without this a short clip would otherwise pull down many times more data than needed.
 *
 * No audio handling: despite requesting {@code AUDIOVALID:1} on every {@code REQUESTALIVEVIDEO} (see
 * {@link N9mProtocolEncoder}), a byte-level check of a real live-view capture found no ADTS AAC frames
 * anywhere in the stream — 24 candidate sync-word matches in ~180KB, zero of which chained to another
 * at the expected next-frame offset, consistent with random false positives in compressed H.264 data
 * rather than real audio (confirmed 2026-08-19). This device/channel simply isn't sending audio, so
 * there's nothing here misinterpreting it as corrupt video either.
 *
 * Deliberately does NOT call {@code getDeviceSession()} — same reasoning as {@link Jt1078ProtocolDecoder}.
 * That also means the SIM data-usage tracker's byte-to-device attribution (normally piggybacked on
 * {@code getDeviceSession()}, see {@link org.traccar.BaseProtocolDecoder#getDeviceSession}) has to be
 * done explicitly here too — {@code DataUsageChannelHandler} still counts this channel's raw bytes
 * unconditionally (it's wired in for every TCP server), but without an explicit {@code flushChannel}
 * call those bytes are never credited to a deviceId. This matters far more here than on the control
 * channel: this is the video path, with real clips observed at 15-275MB each, so before this fix that
 * entire volume was invisible to "Estado de SIM" for any device using N9M.
 */
public class N9mMediaProtocolDecoder extends BaseProtocolDecoder {

    private static final Logger LOGGER = LoggerFactory.getLogger(N9mMediaProtocolDecoder.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int HEADER_LENGTH = 12;
    private static final int NAL_TYPE_SLICE = 1;
    private static final int NAL_TYPE_IDR = 5;
    private static final double DEFAULT_FPS = 15.0;

    private boolean handshakeDone;
    private long streamDeviceId;
    private int streamChannel;
    private long frameCount;
    private long pictureCount;
    private Channel mediaChannel;
    private N9mProtocol.PendingStream pendingStream;

    private final ByteBuf pending = Unpooled.buffer();

    private N9mProtocol n9mProtocol;
    private VideoStreamManager streamManager;
    private VideoClipManager clipManager;

    public N9mMediaProtocolDecoder(N9mMediaProtocol protocol) {
        super(protocol);
    }

    @Inject
    public void setN9mProtocol(N9mProtocol n9mProtocol) {
        this.n9mProtocol = n9mProtocol;
    }

    @Inject
    public void setStreamManager(VideoStreamManager streamManager) {
        this.streamManager = streamManager;
    }

    @Inject
    public void setClipManager(VideoClipManager clipManager) {
        this.clipManager = clipManager;
    }

    @Override
    protected Object decode(
            Channel channel, SocketAddress remoteAddress, Object msg) throws Exception {

        ByteBuf buf = (ByteBuf) msg;

        if (!handshakeDone) {
            handleHandshake(channel, remoteAddress, buf);
            return null;
        }

        processMediaBytes(buf);

        return null;
    }

    /**
     * Splits the accumulated byte stream on Annex-B start codes ({@code 00 00 01}, which also matches
     * the 3 trailing bytes of a 4-byte {@code 00 00 00 01} code — any stray leading zero byte is left
     * as legal Annex-B trailing_zero_8bits padding on the END of the previous NAL, not a bug) and
     * emits every NAL unit that is now fully bounded by two start codes. The bytes from the last start
     * code onward are kept buffered, since more of that NAL may still be in flight.
     *
     * Each emitted slice INCLUDES its own leading {@code 00 00 01} start code (only the type byte used
     * for {@code isKeyFrame} is read past it) — {@link org.traccar.media.VideoStreamWriter#write}
     * prepends an AUD NAL with its own start code but does NOT add one before the NAL data itself, so
     * without the NAL keeping its own prefix the reconstructed elementary stream has no boundary
     * between the AUD and the real payload and ffmpeg reads the whole thing as one broken access unit
     * (confirmed live 2026-08-18: "missing picture in access unit with size 271791052").
     */
    private void processMediaBytes(ByteBuf buf) {
        pending.writeBytes(buf);

        int base = pending.readerIndex();
        int len = pending.readableBytes();
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i < len - 2; i++) {
            int idx = base + i;
            if (pending.getByte(idx) == 0 && pending.getByte(idx + 1) == 0 && pending.getByte(idx + 2) == 1) {
                starts.add(idx);
            }
        }

        if (starts.size() < 2) {
            return;
        }

        for (int k = 0; k < starts.size() - 1; k++) {
            int nalStart = starts.get(k);
            int nalEnd = starts.get(k + 1);
            if (nalEnd > nalStart) {
                emitNal(pending.retainedSlice(nalStart, nalEnd - nalStart));
            }
        }

        pending.readerIndex(starts.get(starts.size() - 1));
        pending.discardReadBytes();
    }

    private void emitNal(ByteBuf nal) {
        try {
            if (nal.readableBytes() <= 3) {
                return;
            }
            int nalType = nal.getByte(nal.readerIndex() + 3) & 0x1F;
            boolean isKeyFrame = nalType == NAL_TYPE_IDR;
            boolean isPicture = isKeyFrame || nalType == NAL_TYPE_SLICE;
            if (isPicture) {
                pictureCount++;
            }

            Double fps = n9mProtocol.getChannelFps(streamDeviceId, streamChannel);
            double frameIntervalMs = 1000.0 / (fps != null ? fps : DEFAULT_FPS);
            long timestamp = (long) (Math.max(0, pictureCount - 1) * frameIntervalMs);

            if (getDataUsageManager() != null && mediaChannel != null) {
                getDataUsageManager().flushChannel(mediaChannel, streamDeviceId, false);
            }

            frameCount++;
            if (frameCount <= 10 || isKeyFrame || frameCount % 500 == 0) {
                LOGGER.info("N9M MEDIA NAL #{} deviceId={} channel={} type={} size={} key={} pts={}",
                        frameCount, streamDeviceId, streamChannel, nalType, nal.readableBytes(),
                        isKeyFrame, timestamp);
            }

            streamManager.handleFrame(streamDeviceId, streamChannel, nal, timestamp, isKeyFrame, 0);
            clipManager.handleFrame(streamDeviceId, streamChannel, nal, timestamp, isKeyFrame, 0);

            if (isPicture && pendingStream != null && pendingStream.isPlayback()
                    && pendingStream.getMaxPictures() > 0 && pictureCount >= pendingStream.getMaxPictures()
                    && mediaChannel != null) {
                LOGGER.info("N9M MEDIA playback reached target pictureCount={} deviceId={} channel={} — "
                                + "closing early instead of pulling more bulk-transferred recorded video",
                        pictureCount, streamDeviceId, streamChannel);
                n9mProtocol.sendControlRemotePlaybackStop(streamDeviceId);
                mediaChannel.close();
            }
        } finally {
            nal.release();
        }
    }

    private void handleHandshake(Channel channel, SocketAddress remoteAddress, ByteBuf buf) {
        if (buf.readableBytes() < HEADER_LENGTH) {
            return;
        }
        buf.skipBytes(6);
        int length = buf.readUnsignedShort();
        buf.skipBytes(4);
        if (buf.readableBytes() < length) {
            return;
        }
        byte[] payload = new byte[length];
        buf.readBytes(payload);
        String json = new String(payload, StandardCharsets.UTF_8).trim();

        if (json.isEmpty() || json.charAt(0) != '{') {
            LOGGER.warn("N9M MEDIA expected CREATESTREAM JSON handshake, got non-JSON frame ({} bytes)", length);
            return;
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            LOGGER.warn("N9M MEDIA failed to parse handshake JSON: {}", json, e);
            return;
        }

        String module = root.path("MODULE").asText(null);
        String operation = root.path("OPERATION").asText(null);
        String session = root.path("SESSION").asText("");

        if (!"CERTIFICATE".equals(module) || !"CREATESTREAM".equals(operation)) {
            LOGGER.warn("N9M MEDIA unexpected first message module={} operation={}", module, operation);
            return;
        }

        String streamName = root.path("PARAMETER").path("STREAMNAME").asText(null);

        N9mProtocol.PendingStream resolvedStream = n9mProtocol.consumePendingStream(streamName);
        if (resolvedStream == null) {
            LOGGER.warn("N9M MEDIA CREATESTREAM with unknown/expired STREAMNAME={}", streamName);
            return;
        }

        pendingStream = resolvedStream;
        streamDeviceId = resolvedStream.getDeviceId();
        streamChannel = resolvedStream.getChannel();
        handshakeDone = true;
        mediaChannel = channel;
        n9mProtocol.registerMediaChannel(streamDeviceId, channel);
        if (getDataUsageManager() != null) {
            getDataUsageManager().flushChannel(channel, streamDeviceId, false);
        }

        LOGGER.info("N9M MEDIA CREATESTREAM deviceId={} channel={} playback={} maxPictures={} streamName={}",
                streamDeviceId, streamChannel, resolvedStream.isPlayback(), resolvedStream.getMaxPictures(),
                streamName);

        String response = "{\"MODULE\":\"CERTIFICATE\",\"OPERATION\":\"CREATESTREAM\","
                + "\"RESPONSE\":{\"ERRORCODE\":0,\"STREAMNAME\":\"" + streamName + "\"},"
                + "\"SESSION\":\"" + session + "\"}";
        channel.writeAndFlush(new NetworkMessage(N9mProtocolDecoder.formatMessage(response), remoteAddress));
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        if (handshakeDone) {
            n9mProtocol.unregisterMediaChannel(streamDeviceId, ctx.channel());
            // Deliberately NOT calling streamManager.removeStream() here: for live view this
            // connection closing is usually just N9mProtocol's periodic reconnect (see its
            // startLiveViewLoop javadoc), not the user actually stopping — removeStream() would wipe
            // out every already-finalized HLS segment on EVERY reconnect cycle, leaving the player
            // with an empty playlist most of the time (confirmed live 2026-08-18/19). The bounded
            // segment history (VideoStreamManager.MAX_SEGMENTS) just ages out naturally instead.
            if (pendingStream != null && pendingStream.isPlayback()) {
                // The connection closing (whether from our own early-stop above, the device finishing
                // the recorded segment, or a network drop) means no more frames are coming for this
                // clip — finalize it now instead of leaving MdvrClipResource's poll loop waiting out
                // VideoClipManager's fixed wall-clock timer for no reason. That timer still fires later
                // as a harmless no-op (the session is already gone by then) if this races with it.
                clipManager.finalizeByDevice(streamDeviceId + "_" + streamChannel);
            }
        }
        pending.release();
        LOGGER.info("N9M MEDIA connection closed deviceId={} channel={} totalNals={}",
                streamDeviceId, streamChannel, frameCount);
    }

}
