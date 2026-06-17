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

import io.netty.buffer.ByteBuf;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.api.BaseResource;
import org.traccar.database.CommandsManager;
import org.traccar.media.VideoClipManager;
import org.traccar.model.Command;
import org.traccar.model.Device;
import org.traccar.storage.StorageException;

import java.io.InputStream;
import java.util.Map;

@Path("videoclip")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class VideoClipResource extends BaseResource {

    private static final Logger LOGGER = LoggerFactory.getLogger(VideoClipResource.class);

    @Inject
    private VideoClipManager clipManager;

    @Inject
    private CommandsManager commandsManager;

    /**
     * POST /api/videoclip
     * Body: { "deviceId": 1, "channel": 1, "startTime": 1234567890, "endTime": 1234567950 }
     * startTime/endTime are Unix epoch seconds (UTC).
     * Returns: { "clipId": "uuid" }
     */
    @POST
    public Response requestClip(Map<String, Object> body) throws Exception {
        long deviceId = ((Number) body.get("deviceId")).longValue();
        int channel = body.containsKey("channel") ? ((Number) body.get("channel")).intValue() : 1;
        long startTime = ((Number) body.get("startTime")).longValue();
        long endTime = ((Number) body.get("endTime")).longValue();

        if (endTime <= startTime || endTime - startTime > 3600) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid time range (max 1 hour)"))
                    .build();
        }

        permissionsService.checkPermission(Device.class, getUserId(), deviceId);

        String clipId = clipManager.createSession(deviceId, channel, endTime - startTime);
        LOGGER.info("VIDEOCLIP REQUEST deviceId={} ch={} start={} end={} -> clipId={}",
                deviceId, channel, startTime, endTime, clipId);

        Command command = new Command();
        command.setDeviceId(deviceId);
        command.setType(Command.TYPE_VIDEO_DOWNLOAD);
        command.set(Command.KEY_INDEX, channel);
        command.set(Command.KEY_START_TIME, startTime);
        command.set(Command.KEY_END_TIME, endTime);

        commandsManager.sendCommand(command);

        return Response.ok(Map.of("clipId", clipId)).build();
    }

    /**
     * GET /api/videoclip/{clipId}
     * Returns: { "clipId": "...", "status": "pending|recording|ready|error" }
     */
    @GET
    @Path("{clipId}")
    public Response getStatus(@PathParam("clipId") String clipId) throws StorageException {
        VideoClipManager.ClipStatus status = clipManager.getStatus(clipId);
        if (status == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(Map.of(
                "clipId", clipId,
                "status", status.name().toLowerCase(),
                "ready", status == VideoClipManager.ClipStatus.READY
        )).build();
    }

    /**
     * GET /api/videoclip/{clipId}/file
     * Downloads the MPEG-TS clip file when status == ready.
     */
    @GET
    @Path("{clipId}/file")
    @Produces("video/mp2t")
    public Response downloadClip(@PathParam("clipId") String clipId) throws StorageException {
        VideoClipManager.ClipStatus status = clipManager.getStatus(clipId);
        if (status == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if (status != VideoClipManager.ClipStatus.READY) {
            return Response.status(Response.Status.CONFLICT)
                    .entity("Clip not ready yet, status: " + status.name().toLowerCase())
                    .build();
        }
        ByteBuf data = clipManager.getClipData(clipId);
        if (data == null || data.readableBytes() == 0) {
            LOGGER.warn("VIDEOCLIP DOWNLOAD EMPTY clipId={} (no frames received)", clipId);
            return Response.status(Response.Status.NO_CONTENT).build();
        }
        LOGGER.info("VIDEOCLIP DOWNLOAD clipId={} bytes={}", clipId, data.readableBytes());
        StreamingOutput stream = output -> data.getBytes(data.readerIndex(), output, data.readableBytes());
        return Response.ok(stream)
                .header("Content-Disposition", "attachment; filename=\"clip-" + clipId.substring(0, 8) + ".ts\"")
                .build();
    }

    /**
     * DELETE /api/videoclip/{clipId}
     * Frees the in-memory buffer once the client has downloaded it.
     */
    @DELETE
    @Path("{clipId}")
    public Response deleteClip(@PathParam("clipId") String clipId) {
        clipManager.removeSession(clipId);
        return Response.noContent().build();
    }

    /**
     * GET /api/videoclip/rtsp?deviceId=1&rtspUrl=rtsp://...&duration=80
     * Runs ffmpeg against the MDVR's RTSP server and streams the MP4 back.
     */
    @GET
    @Path("rtsp")
    @Produces("video/mp4")
    public Response downloadRtspClip(
            @QueryParam("deviceId") long deviceId,
            @QueryParam("rtspUrl") String rtspUrl,
            @QueryParam("duration") @DefaultValue("80") long duration) throws Exception {

        if (rtspUrl == null || rtspUrl.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "rtspUrl is required")).build();
        }
        if (duration <= 0 || duration > 3600) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "duration must be 1–3600 s")).build();
        }

        permissionsService.checkPermission(Device.class, getUserId(), deviceId);
        LOGGER.info("RTSP CLIP deviceId={} url={} duration={}s", deviceId, rtspUrl, duration);

        final String url = rtspUrl;
        final long dur = duration;

        StreamingOutput stream = output -> {
            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg",
                    "-rtsp_transport", "tcp",
                    "-i", url,
                    "-t", String.valueOf(dur),
                    "-c", "copy",
                    "-f", "mp4",
                    "-movflags", "frag_keyframe+empty_moov",
                    "-loglevel", "warning",
                    "pipe:1");
            pb.redirectErrorStream(false);
            Process process = pb.start();
            try (InputStream is = process.getInputStream()) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = is.read(buf)) != -1) {
                    output.write(buf, 0, n);
                }
            } finally {
                try {
                    int exit = process.waitFor();
                    LOGGER.info("RTSP CLIP ffmpeg done exit={} url={}", exit, url);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        };

        String filename = "clip-" + deviceId + "-" + (System.currentTimeMillis() / 1000) + ".mp4";
        return Response.ok(stream)
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .build();
    }

}
