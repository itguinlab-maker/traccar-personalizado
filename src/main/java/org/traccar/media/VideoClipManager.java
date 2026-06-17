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
package org.traccar.media;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Singleton
public class VideoClipManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(VideoClipManager.class);

    public enum ClipStatus { PENDING, RECORDING, READY, ERROR }

    private static final int FINALIZE_EXTRA_SECONDS = 15;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, ClipSession> sessionsById = new ConcurrentHashMap<>();
    private final Map<String, String> sessionByDevice = new ConcurrentHashMap<>(); // deviceId_channel -> clipId

    @Inject
    public VideoClipManager() {
    }

    public String createSession(long deviceId, int channel, long durationSeconds) {
        String clipId = UUID.randomUUID().toString();
        String key = deviceId + "_" + channel;

        // Cancel any prior session for this device+channel
        String oldId = sessionByDevice.remove(key);
        if (oldId != null) {
            ClipSession old = sessionsById.remove(oldId);
            if (old != null) {
                old.release();
            }
        }

        ClipSession session = new ClipSession(clipId);
        sessionsById.put(clipId, session);
        sessionByDevice.put(key, clipId);

        long delay = durationSeconds + FINALIZE_EXTRA_SECONDS;
        ScheduledFuture<?> future = scheduler.schedule(
                () -> finalizeByDevice(key), delay, TimeUnit.SECONDS);
        session.setFinalizeFuture(future);

        LOGGER.info("VIDEOCLIP SESSION CREATED clipId={} key={} timeout={}s", clipId, key, delay);
        return clipId;
    }

    public void handleFrame(
            long deviceId, int channel, ByteBuf nalData, long timestamp, boolean isKeyFrame, int payloadType) {
        String clipId = sessionByDevice.get(deviceId + "_" + channel);
        if (clipId == null) {
            return;
        }
        ClipSession session = sessionsById.get(clipId);
        if (session != null) {
            if (session.getStatus() == ClipStatus.PENDING) {
                LOGGER.info("VIDEOCLIP FIRST FRAME deviceId={} ch={} clipId={}", deviceId, channel, clipId);
            }
            session.addFrame(nalData, timestamp, isKeyFrame, payloadType);
        }
    }

    public void finalizeByDevice(String deviceKey) {
        String clipId = sessionByDevice.remove(deviceKey);
        if (clipId == null) {
            return;
        }
        ClipSession session = sessionsById.get(clipId);
        if (session != null) {
            if (session.getStatus() == ClipStatus.PENDING) {
                session.markError();
                LOGGER.warn("VIDEOCLIP NO FRAMES RECEIVED key={} clipId={} — device did not stream back",
                        deviceKey, clipId);
            } else {
                session.markReady();
                LOGGER.info("VIDEOCLIP FINALIZED key={} clipId={} bytes={}",
                        deviceKey, clipId, session.getData().readableBytes());
            }
        }
    }

    public ClipStatus getStatus(String clipId) {
        ClipSession session = sessionsById.get(clipId);
        return session != null ? session.getStatus() : null;
    }

    public ByteBuf getClipData(String clipId) {
        ClipSession session = sessionsById.get(clipId);
        if (session == null || session.getStatus() != ClipStatus.READY) {
            return null;
        }
        return session.getData();
    }

    public void removeSession(String clipId) {
        ClipSession session = sessionsById.remove(clipId);
        if (session != null) {
            session.release();
        }
    }

    static class ClipSession {

        private final VideoStreamWriter writer = new VideoStreamWriter();
        private final ByteBuf buffer = Unpooled.buffer();
        private ClipStatus status = ClipStatus.PENDING;
        private long firstTimestamp;
        private ScheduledFuture<?> finalizeFuture;

        ClipSession(String clipId) {
        }

        void setFinalizeFuture(ScheduledFuture<?> future) {
            this.finalizeFuture = future;
        }

        synchronized void addFrame(ByteBuf nalData, long timestamp, boolean isKeyFrame, int payloadType) {
            if (status != ClipStatus.PENDING && status != ClipStatus.RECORDING) {
                return;
            }
            status = ClipStatus.RECORDING;
            if (firstTimestamp == 0) {
                firstTimestamp = timestamp;
            }
            writer.write(buffer, nalData, timestamp - firstTimestamp, isKeyFrame, payloadType);
        }

        synchronized void markReady() {
            if (status == ClipStatus.PENDING || status == ClipStatus.RECORDING) {
                status = ClipStatus.READY;
            }
        }

        synchronized void markError() {
            if (status == ClipStatus.PENDING || status == ClipStatus.RECORDING) {
                status = ClipStatus.ERROR;
            }
        }

        synchronized ClipStatus getStatus() {
            return status;
        }

        synchronized ByteBuf getData() {
            return buffer.duplicate();
        }

        synchronized void release() {
            if (finalizeFuture != null) {
                finalizeFuture.cancel(false);
                finalizeFuture = null;
            }
            buffer.release();
        }
    }

}
