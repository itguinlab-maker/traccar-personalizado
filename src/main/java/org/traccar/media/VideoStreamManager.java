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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class VideoStreamManager {

    private static final int MAX_SEGMENTS = 5;

    private final Map<String, DeviceStream> streams = new ConcurrentHashMap<>();

    @Inject
    public VideoStreamManager() {
    }

    public void handleFrame(
            long deviceId, int channel, ByteBuf nalData, long timestamp, boolean isKeyFrame, int payloadType) {
        DeviceStream stream = streams.computeIfAbsent(deviceId + "_" + channel, k -> new DeviceStream());
        stream.addFrame(nalData, timestamp, isKeyFrame, payloadType);
    }

    public String getPlaylist(long deviceId, int channel) {
        DeviceStream stream = streams.get(deviceId + "_" + channel);
        return stream != null ? stream.getPlaylist() : DeviceStream.EMPTY_PLAYLIST;
    }

    public void removeStream(long deviceId, int channel) {
        DeviceStream stream = streams.remove(deviceId + "_" + channel);
        if (stream != null) {
            stream.release();
        }
    }

    public ByteBuf getSegment(long deviceId, int channel, int index) {
        DeviceStream stream = streams.get(deviceId + "_" + channel);
        return stream != null ? stream.getSegment(index) : null;
    }

    static class DeviceStream {

        private static final double DEFAULT_TARGET_DURATION = 5.0;

        private static final int NAL_TYPE_SPS = 7;
        private static final int NAL_TYPE_PPS = 8;

        private final VideoStreamWriter writer = new VideoStreamWriter();
        private final LinkedHashMap<Integer, ByteBuf> segments = new LinkedHashMap<>();
        private final LinkedHashMap<Integer, Double> segmentDurations = new LinkedHashMap<>();
        private ByteBuf currentSegment;
        private int segmentIndex;
        private long firstTimestamp;
        private long segmentStartTimestamp;
        private long lastTimestamp;
        private ByteBuf cachedSps;
        private ByteBuf cachedPps;
        private boolean justSawSps;

        /**
         * Segments are cut ONLY on a real keyframe (never on a playlist poll — see {@link
         * #getPlaylist}) so every segment starts with an IDR the MPEG-TS file needs to be
         * independently decodable. This matters more than it used to: unlike JT1078 (which sends
         * keyframes on its own regular interval), N9M devices send at most one real keyframe per media
         * session (confirmed live 2026-08-18/19), so segment boundaries here come from
         * {@code N9mProtocol}'s periodic live-view reconnect loop, not the device's own encoder GOP.
         *
         * Starting a keyframe alone isn't enough, though: this device only ever sends a PROPERLY
         * FORMED PPS once, immediately after the session's one SPS — every later PPS "resend" (seen
         * before nearly every subsequent frame) carries a corrupted {@code pps_id} that ffmpeg rejects
         * ("pps_id ... out of range"), so any segment that doesn't happen to still contain that
         * original pair has no usable parameter sets at all ("non-existing PPS 0 referenced",
         * width/height 0 — confirmed live 2026-08-18/19). So the SPS and the PPS immediately following
         * it are cached here and re-injected at the start of every new segment, not just the first.
         */
        synchronized void addFrame(ByteBuf nalData, long timestamp, boolean isKeyFrame, int payloadType) {
            cacheParameterSets(nalData);

            if (isKeyFrame && currentSegment != null) {
                finalizeSegment();
            }

            boolean freshSegment = currentSegment == null;
            if (freshSegment) {
                currentSegment = Unpooled.buffer();
                segmentStartTimestamp = timestamp;
                if (firstTimestamp == 0) {
                    firstTimestamp = timestamp;
                }
            }
            lastTimestamp = timestamp;

            long pts = timestamp - firstTimestamp;
            if (freshSegment && isKeyFrame && cachedSps != null && cachedPps != null) {
                writer.write(currentSegment, cachedSps, pts, true, payloadType);
                writer.write(currentSegment, cachedPps, pts, false, payloadType);
                writer.write(currentSegment, nalData, pts, false, payloadType);
            } else {
                writer.write(currentSegment, nalData, pts, isKeyFrame, payloadType);
            }
        }

        private void cacheParameterSets(ByteBuf nalData) {
            if (nalData.readableBytes() <= 3) {
                return;
            }
            int nalType = nalData.getByte(nalData.readerIndex() + 3) & 0x1F;
            if (nalType == NAL_TYPE_SPS) {
                if (cachedSps != null) {
                    cachedSps.release();
                }
                cachedSps = Unpooled.copiedBuffer(nalData);
                justSawSps = true;
            } else if (nalType == NAL_TYPE_PPS && justSawSps) {
                if (cachedPps != null) {
                    cachedPps.release();
                }
                cachedPps = Unpooled.copiedBuffer(nalData);
                justSawSps = false;
            } else {
                justSawSps = false;
            }
        }

        private void finalizeSegment() {
            double durationSeconds = Math.max(0.1, (lastTimestamp - segmentStartTimestamp) / 1000.0);
            segments.put(segmentIndex, currentSegment);
            segmentDurations.put(segmentIndex, durationSeconds);
            segmentIndex++;
            currentSegment = null;

            while (segments.size() > MAX_SEGMENTS) {
                Integer oldest = segments.keySet().iterator().next();
                segments.remove(oldest).release();
                segmentDurations.remove(oldest);
            }
        }

        synchronized void release() {
            if (currentSegment != null) {
                currentSegment.release();
            }
            for (ByteBuf segment : segments.values()) {
                segment.release();
            }
            if (cachedSps != null) {
                cachedSps.release();
            }
            if (cachedPps != null) {
                cachedPps.release();
            }
        }

        static final String EMPTY_PLAYLIST =
                "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:5\n#EXT-X-MEDIA-SEQUENCE:0\n";

        /**
         * Only lists already-finalized (keyframe-bounded) segments — does NOT force-cut whatever is
         * still accumulating in {@code currentSegment} just because someone asked for the playlist.
         * Doing that used to let a player's own polling cadence (typically faster than this device's
         * keyframe interval) slice segments at arbitrary non-keyframe points, producing .ts files with
         * no SPS/PPS/PAT/PMT that failed to decode (confirmed live 2026-08-18/19: "non-existing PPS 0
         * referenced", width/height 0) — even though the underlying frame data was otherwise fine.
         */
        synchronized String getPlaylist() {
            if (segments.isEmpty()) {
                return EMPTY_PLAYLIST;
            }

            int firstIndex = segments.keySet().iterator().next();
            double targetDuration = DEFAULT_TARGET_DURATION;
            for (double duration : segmentDurations.values()) {
                targetDuration = Math.max(targetDuration, Math.ceil(duration));
            }

            StringBuilder sb = new StringBuilder();
            sb.append("#EXTM3U\n");
            sb.append("#EXT-X-VERSION:3\n");
            sb.append("#EXT-X-TARGETDURATION:").append((int) targetDuration).append("\n");
            sb.append("#EXT-X-MEDIA-SEQUENCE:").append(firstIndex).append("\n");

            for (int key : segments.keySet()) {
                sb.append("#EXTINF:").append(segmentDurations.get(key)).append(",\n");
                sb.append(key).append(".ts\n");
            }

            return sb.toString();
        }

        synchronized ByteBuf getSegment(int index) {
            return segments.get(index);
        }
    }

}
