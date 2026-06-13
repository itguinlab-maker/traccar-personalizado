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
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.api.BaseResource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * POST /api/externalcounting
 *
 * Forwards a batch of counting events to an external REST server.
 *
 * Request body:
 * {
 *   "serverUrl":  "http://example.com/api/counts",
 *   "username":   "user",           // optional
 *   "password":   "pass",           // optional
 *   "events": [
 *     {
 *       "deviceId":   1,
 *       "deviceName": "Bus 01",
 *       "plate":      "ABC123",
 *       "fixTime":    "2026-05-26T12:00:00.000Z",
 *       "latitude":   4.123,
 *       "longitude":  -74.456,
 *       "doorId":     0,
 *       "doorLabel":  "Delantera",
 *       "boardings":  5,
 *       "alightings": 3
 *     },
 *     ...
 *   ]
 * }
 *
 * Response: { "sent": N, "status": <HTTP status from external server>, "body": "..." }
 */
@Path("externalcounting")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ExternalCountingResource extends BaseResource {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExternalCountingResource.class);

    @Inject
    private ObjectMapper objectMapper;

    @POST
    public Response send(Map<String, Object> body) throws Exception {
        String serverUrl = (String) body.get("serverUrl");
        String username = (String) body.getOrDefault("username", "");
        String password = (String) body.getOrDefault("password", "");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) body.get("events");

        if (serverUrl == null || serverUrl.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "serverUrl is required")).build();
        }
        if (events == null || events.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "events list is required and cannot be empty")).build();
        }

        LOGGER.info("EXTERNAL COUNTING sending {} events to {}", events.size(), serverUrl);

        String payload = objectMapper.writeValueAsString(events);

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(URI.create(serverUrl))
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30));

        if (username != null && !username.isBlank()) {
            String credentials = username + ":" + password;
            String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            reqBuilder.header("Authorization", "Basic " + encoded);
        }

        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        HttpResponse<String> resp = http.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());

        LOGGER.info("EXTERNAL COUNTING response status={} body={}", resp.statusCode(), resp.body());

        return Response.ok(Map.of(
                "sent", events.size(),
                "status", resp.statusCode(),
                "body", resp.body() != null ? resp.body() : ""
        )).build();
    }
}
