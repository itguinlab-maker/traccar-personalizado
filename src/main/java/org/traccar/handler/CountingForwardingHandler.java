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
package org.traccar.handler;

import jakarta.inject.Inject;
import org.traccar.forwarding.ExternalForwardingManager;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;

/**
 * Runs after {@link DatabaseHandler} in the position pipeline.
 * When a position represents a counting event it is forwarded (fire-and-forget)
 * to whichever external server is configured for the device's forwarding group.
 */
public class CountingForwardingHandler extends BasePositionHandler {

    private final ExternalForwardingManager forwardingManager;
    private final CacheManager cacheManager;

    @Inject
    public CountingForwardingHandler(ExternalForwardingManager forwardingManager, CacheManager cacheManager) {
        this.forwardingManager = forwardingManager;
        this.cacheManager = cacheManager;
    }

    @Override
    public void onPosition(Position position, Callback callback) {
        try {
            Object status = position.getAttributes().get("streamax.status");
            if ("counting_event".equals(status)) {
                int boardings = ((Number) position.getAttributes().getOrDefault("passengersOn", 0)).intValue();
                int alightings = ((Number) position.getAttributes().getOrDefault("passengersOff", 0)).intValue();
                if (boardings > 0 || alightings > 0) {
                    Device device = cacheManager.getObject(Device.class, position.getDeviceId());
                    String deviceName = device != null ? device.getName() : String.valueOf(position.getDeviceId());
                    forwardingManager.forwardIfConfigured(position, deviceName, deviceName);
                }
            }
        } finally {
            callback.processed(false);
        }
    }
}
