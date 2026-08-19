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
import io.netty.channel.Channel;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.BaseProtocol;
import org.traccar.NetworkMessage;
import org.traccar.PipelineBuilder;
import org.traccar.TrackerServer;
import org.traccar.config.Config;
import org.traccar.model.Command;

import java.net.SocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * N9M control channel (Streamax MDVR JSON-over-TCP protocol, reverse-engineered 2026-08-17).
 *
 * Deliberately does NOT participate in the standard {@code getDeviceSession()}/{@code ConnectionManager}
 * session-ownership mechanism, so it never steals command routing away from JT808 (which remains the
 * sole owner of the device's general command session) — same reasoning as {@link Jt1078Protocol}. Commands
 * reach this protocol only via {@code org.traccar.command.N9mCommandSender}, which is selected by
 * {@code CommandSenderManager} for devices with the {@code mdvrMode=n9m} attribute.
 *
 * Must be a Guice singleton: both {@code ServerManager} (server startup) and {@code N9mCommandSender}
 * (command dispatch) inject this class directly, and both need the SAME instance so the in-memory
 * control-channel/pending-stream registries below are shared.
 */
@Singleton
public class N9mProtocol extends BaseProtocol {

    private static final Logger LOGGER = LoggerFactory.getLogger(N9mProtocol.class);

    public static final class PendingStream {
        private final long deviceId;
        private final int channel;
        private final boolean playback;
        private final int maxPictures;

        public PendingStream(long deviceId, int channel, boolean playback, int maxPictures) {
            this.deviceId = deviceId;
            this.channel = channel;
            this.playback = playback;
            this.maxPictures = maxPictures;
        }

        public long getDeviceId() {
            return deviceId;
        }

        public int getChannel() {
            return channel;
        }

        public boolean isPlayback() {
            return playback;
        }

        /**
         * Cap on decoded pictures (NAL type 1/5) before the media connection self-closes, or -1 for
         * unlimited (live view). See {@link N9mMediaProtocolDecoder} for why this exists: playback
         * ignores our requested END time and streams recorded video far faster than real-time, so
         * without this a short clip request would otherwise pull down many times more data/time than
         * needed before our own wall-clock safety timer eventually cuts it off.
         */
        public int getMaxPictures() {
            return maxPictures;
        }
    }

    private final Map<Long, Channel> controlChannels = new ConcurrentHashMap<>();
    private final Map<Long, SocketAddress> controlAddresses = new ConcurrentHashMap<>();
    private final Map<Channel, Long> channelDevices = new ConcurrentHashMap<>();
    private final Map<String, PendingStream> pendingStreams = new ConcurrentHashMap<>();
    private final Map<Long, Channel> activeMediaChannels = new ConcurrentHashMap<>();
    private final Map<Integer, CompletableFuture<JsonNode>> pendingFileListQueries = new ConcurrentHashMap<>();
    private final AtomicInteger fileListSerial = new AtomicInteger(1);
    private final Map<String, Double> channelFps = new ConcurrentHashMap<>();
    private final Map<Long, String> activePlaybackStreamNames = new ConcurrentHashMap<>();
    private final Map<Long, Integer> activePlaybackChannels = new ConcurrentHashMap<>();
    private final Map<Long, ScheduledFuture<?>> liveViewLoops = new ConcurrentHashMap<>();
    private final Map<Long, ScheduledFuture<?>> liveViewExpirations = new ConcurrentHashMap<>();
    private final ScheduledExecutorService liveViewScheduler = Executors.newSingleThreadScheduledExecutor();

    // The device only ever sends ONE keyframe per media session (confirmed live 2026-08-18/19, both
    // for live view and playback) — with no confirmed command to request a fresh one mid-session, HLS
    // segmenting (which needs periodic keyframes to cut independently-decodable segments, see
    // VideoStreamManager) has nothing to align to after the first segment. The workaround is to
    // periodically tear down and re-request REQUESTALIVEVIDEO — each new session reliably starts with
    // a fresh SPS/PPS/IDR (confirmed repeatedly), which VideoStreamManager already uses to cut a new
    // segment. This trades a brief (~1-2s) freeze every cycle for indefinitely-watchable live view.
    private static final int LIVE_RESTART_SECONDS = 8;
    private static final int LIVE_FIRST_RESTART_SECONDS = 3;

    // Safety cap: the frontend doesn't reliably send TYPE_VIDEO_STOP on tab close/navigation (same gap
    // noted for the plain stop command), so without this an abandoned live view would restart forever,
    // wasting the device's single media task slot and bandwidth indefinitely — same "can't happen in
    // production" concern raised about the earlier stuck-live-view bug. Re-clicking "live view" resets
    // this timer (startLiveViewLoop cancels and reschedules it), so active viewing is never cut short.
    private static final int LIVE_MAX_MINUTES = 10;

    @Inject
    public N9mProtocol(Config config) {
        setSupportedDataCommands(
                Command.TYPE_VIDEO_START,
                Command.TYPE_VIDEO_STOP,
                Command.TYPE_VIDEO_DOWNLOAD,
                Command.TYPE_VIDEO_QUERY);
        addServer(new TrackerServer(config, getName(), false) {
            @Override
            protected void addProtocolHandlers(PipelineBuilder pipeline, Config config) {
                pipeline.addLast(new N9mFrameDecoder());
                pipeline.addLast(new N9mProtocolEncoder(N9mProtocol.this));
                pipeline.addLast(new N9mProtocolDecoder(N9mProtocol.this));
            }
        });
    }

    public void registerControlChannel(long deviceId, Channel channel, SocketAddress remoteAddress) {
        Channel previous = controlChannels.put(deviceId, channel);
        if (previous != null && previous != channel) {
            channelDevices.remove(previous);
        }
        controlAddresses.put(deviceId, remoteAddress);
        channelDevices.put(channel, deviceId);
        LOGGER.info("N9M control channel registered deviceId={} remoteAddress={}", deviceId, remoteAddress);
    }

    public void unregisterControlChannel(Channel channel) {
        Long deviceId = channelDevices.remove(channel);
        if (deviceId != null) {
            controlChannels.remove(deviceId, channel);
            controlAddresses.remove(deviceId);
            LOGGER.info("N9M control channel unregistered deviceId={}", deviceId);
        }
    }

    public boolean hasControlChannel(long deviceId) {
        return controlChannels.containsKey(deviceId);
    }

    public Long getDeviceIdForChannel(Channel channel) {
        return channelDevices.get(channel);
    }

    public void sendControlCommand(long deviceId, Command command) {
        Channel channel = controlChannels.get(deviceId);
        SocketAddress remoteAddress = controlAddresses.get(deviceId);
        if (channel == null) {
            throw new RuntimeException("No active N9M control connection for device " + deviceId);
        }
        sendDataCommand(channel, remoteAddress, command);
    }

    public void registerPendingStream(
            String streamName, long deviceId, int channel, boolean playback, int maxPictures) {
        pendingStreams.put(streamName, new PendingStream(deviceId, channel, playback, maxPictures));
    }

    public PendingStream consumePendingStream(String streamName) {
        return pendingStreams.remove(streamName);
    }

    /**
     * Per-channel frame rate learned from the device's own CONFIGMODEL/MAIN config (sent right after
     * CONNECT, see {@link N9mProtocolDecoder}) — used to synthesize correct playback-speed PTS values
     * and to size {@link PendingStream#getMaxPictures()}, since raw Annex-B media frames carry no
     * per-frame timestamp of their own.
     */
    public void setChannelFps(long deviceId, int channel, double fps) {
        channelFps.put(deviceId + "_" + channel, fps);
    }

    public Double getChannelFps(long deviceId, int channel) {
        return channelFps.get(deviceId + "_" + channel);
    }

    /**
     * Tracks the currently-open media (video/audio) connection for a device, so a new request can
     * force-close any leftover one first — see {@link #closeActiveMediaChannel}. Without this, a
     * media task that never gets a proper stop (browser tab closed, network drop, etc.) leaves the
     * device reporting "TASK FULL"/"LACK OF RESOURCE" for ALL future video requests — including the
     * counting-event clip download, which is the feature that actually matters in production —
     * until the device is manually rebooted. There's no confirmed wire-level "stop" command for N9M
     * yet, so this closes OUR side of the TCP connection instead: on an embedded device that's
     * normally enough to make it notice (write failure / EOF) and free the associated task slot.
     */
    public void registerMediaChannel(long deviceId, Channel channel) {
        activeMediaChannels.put(deviceId, channel);
    }

    public void unregisterMediaChannel(long deviceId, Channel channel) {
        activeMediaChannels.remove(deviceId, channel);
    }

    /**
     * Closes any existing media channel and waits (up to 1s) for the close to actually complete,
     * plus a short extra pause — confirmed against the real device that closing our side alone,
     * immediately followed by a new REQUESTREMOTEPLAYBACK/REQUESTALIVEVIDEO, still gets rejected with
     * "TASK FULL": the device needs a moment to notice the disconnect and free its task slot before
     * it will accept a new one. Blocking briefly here (called from a Netty I/O thread on this
     * infrequent, human-triggered path) is an accepted tradeoff, same as the polling waits already
     * used elsewhere in this codebase (e.g. MdvrClipResource).
     */
    public void closeActiveMediaChannel(long deviceId) {
        sendControlRemotePlaybackStop(deviceId);
        Channel previous = activeMediaChannels.remove(deviceId);
        if (previous != null && previous.isActive()) {
            LOGGER.info("N9M closing stale media channel for deviceId={} before starting a new request", deviceId);
            previous.close().awaitUninterruptibly(1000);
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Remembers the channel/streamName of a REQUESTREMOTEPLAYBACK session so it can be stopped with
     * the device's own native {@code CONTROLREMOTEPLAYBACK} command (see {@link
     * #sendControlRemotePlaybackStop}) instead of relying solely on closing our side of the TCP
     * connection. Deliberately never tracked for live view (REQUESTALIVEVIDEO) — CONTROLREMOTEPLAYBACK
     * is specific to playback sessions per the confirmed capture.
     */
    public void trackActivePlayback(long deviceId, int channel, String streamName) {
        activePlaybackStreamNames.put(deviceId, streamName);
        activePlaybackChannels.put(deviceId, channel);
    }

    /**
     * Sends the device's own {@code MEDIASTREAMMODEL/CONTROLREMOTEPLAYBACK} command with
     * {@code PALYBACKCMD:0} (sic — real firmware typo, confirmed in the passive capture) to ask it to
     * stop a playback session cleanly, before falling back to closing our side of the TCP connection
     * (still done unconditionally right after this, in {@link #closeActiveMediaChannel} — this is a
     * best-effort addition, not a replacement, since we've never confirmed on this device/firmware
     * that the device actually honors it). No-ops if there's no tracked active playback (e.g. the
     * current session is live view, or there simply isn't one) or no control connection to send on.
     */
    public void sendControlRemotePlaybackStop(long deviceId) {
        String streamName = activePlaybackStreamNames.remove(deviceId);
        Integer channel = activePlaybackChannels.remove(deviceId);
        if (streamName == null) {
            return;
        }
        Channel controlChannel = controlChannels.get(deviceId);
        SocketAddress remoteAddress = controlAddresses.get(deviceId);
        if (controlChannel == null) {
            return;
        }
        String json = "{\"MODULE\":\"MEDIASTREAMMODEL\",\"OPERATION\":\"CONTROLREMOTEPLAYBACK\","
                + "\"PARAMETER\":{\"CHANNEL\":" + (channel != null ? channel : 1) + ",\"PALYBACKCMD\":0,\"PT\":4,"
                + "\"STREAMNAME\":\"" + streamName + "\"},\"SESSION\":\"" + UUID.randomUUID() + "\"}";
        LOGGER.info("N9M CONTROLREMOTEPLAYBACK stop deviceId={} streamName={}", deviceId, streamName);
        controlChannel.writeAndFlush(new NetworkMessage(N9mProtocolDecoder.formatMessage(json), remoteAddress));
    }

    /**
     * Starts (or restarts) the periodic re-request loop that keeps a live view watchable beyond its
     * first HLS segment — see {@link #LIVE_RESTART_SECONDS}. Idempotent: calling this again (e.g. the
     * user re-clicking "live view") cancels and replaces any existing loop for the device rather than
     * stacking a second one, since the device only supports one media task at a time regardless.
     *
     * The first restart fires sooner than the steady-state interval ({@link #LIVE_FIRST_RESTART_SECONDS})
     * — {@code VideoStreamManager} only lists a segment once a keyframe finalizes it (see its javadoc),
     * so without this the player's playlist would stay empty for a full {@code LIVE_RESTART_SECONDS}
     * after clicking "live view" instead of the first clip being ready almost immediately.
     */
    public void startLiveViewLoop(long deviceId, int channel) {
        stopLiveViewLoop(deviceId);
        ScheduledFuture<?> future = liveViewScheduler.scheduleAtFixedRate(() -> {
            try {
                Command restart = new Command();
                restart.setDeviceId(deviceId);
                restart.setType(Command.TYPE_VIDEO_START);
                restart.set(Command.KEY_INDEX, channel);
                sendControlCommand(deviceId, restart);
            } catch (Exception e) {
                LOGGER.warn("N9M live view restart failed deviceId={} channel={}", deviceId, channel, e);
            }
        }, LIVE_FIRST_RESTART_SECONDS, LIVE_RESTART_SECONDS, TimeUnit.SECONDS);
        liveViewLoops.put(deviceId, future);

        ScheduledFuture<?> expiration = liveViewScheduler.schedule(() -> {
            LOGGER.info("N9M live view loop deviceId={} auto-stopped after {} min safety cap",
                    deviceId, LIVE_MAX_MINUTES);
            stopLiveViewLoop(deviceId);
            closeActiveMediaChannel(deviceId);
        }, LIVE_MAX_MINUTES, TimeUnit.MINUTES);
        ScheduledFuture<?> previousExpiration = liveViewExpirations.put(deviceId, expiration);
        if (previousExpiration != null) {
            previousExpiration.cancel(false);
        }
    }

    public void stopLiveViewLoop(long deviceId) {
        ScheduledFuture<?> future = liveViewLoops.remove(deviceId);
        if (future != null) {
            future.cancel(false);
        }
        ScheduledFuture<?> expiration = liveViewExpirations.remove(deviceId);
        if (expiration != null) {
            expiration.cancel(false);
        }
    }

    /**
     * Sends STORM/QUERYFILELIST directly (bypassing the Command/CommandSender abstraction, which is
     * fire-and-forget only) and returns a future completed when the matching RESPONSE arrives —
     * correlated by SERIAL, resolved by {@link N9mProtocolDecoder} calling
     * {@link #completeFileListQuery}. Needed because REQUESTREMOTEPLAYBACK only succeeds when given
     * the EXACT start/end of a real recording segment (confirmed live 2026-08-17 — an arbitrary
     * window around the event time always failed with "TASK FULL"/ERRORCODE:26), so the counting-event
     * download flow must look up the real segment boundaries first.
     */
    public CompletableFuture<JsonNode> queryFileList(
            long deviceId, int channelMask, String startTime, String endTime) {
        Channel channel = controlChannels.get(deviceId);
        SocketAddress remoteAddress = controlAddresses.get(deviceId);
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        if (channel == null) {
            future.completeExceptionally(
                    new RuntimeException("No active N9M control connection for device " + deviceId));
            return future;
        }
        int serial = fileListSerial.incrementAndGet();
        pendingFileListQueries.put(serial, future);
        // STREAMTYPE:1 — same finding as GETCALENDAR: STREAMTYPE:0 consistently returns
        // SENDFILECOUNT:0 on this device/firmware even for days with confirmed real footage.
        String json = "{\"MODULE\":\"STORM\",\"OPERATION\":\"QUERYFILELIST\",\"SESSION\":\"" + UUID.randomUUID()
                + "\",\"PARAMETER\":{\"SERIAL\":" + serial + ",\"STARTTIME\":\"" + startTime + "\",\"ENDTIME\":\""
                + endTime + "\",\"CHANNEL\":" + channelMask + ",\"STREAMTYPE\":1,\"FILETYPE\":65535,"
                + "\"RFSTORAGE\":0}}";
        channel.writeAndFlush(new NetworkMessage(N9mProtocolDecoder.formatMessage(json), remoteAddress));
        return future;
    }

    public void completeFileListQuery(int serial, JsonNode response) {
        CompletableFuture<JsonNode> future = pendingFileListQueries.remove(serial);
        if (future != null) {
            future.complete(response);
        }
    }

}
