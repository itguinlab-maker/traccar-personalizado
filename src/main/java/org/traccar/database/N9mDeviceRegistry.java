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
package org.traccar.database;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.model.Device;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Request;

/**
 * Resolves N9M's device identifier (DSNO, a chip-id string like "00E4006A50") to a Traccar deviceId.
 *
 * N9M identifies devices by DSNO, a different value from the JT808 uniqueId/terminalId already used
 * for the same physical device — so the standard uniqueId-based session/device lookup can't be reused.
 * DSNO is stored as a free-form device attribute ({@code n9mSerial}), the same convention already used
 * for {@code mdvrIp}/{@code mdvrUser} elsewhere in this codebase. Device attributes aren't indexed in
 * storage, so lookups scan all devices — this only happens on N9M CONNECT (once per connection, not
 * per-keepalive), which is infrequent enough for a small fleet that a fresh scan is fine.
 */
@Singleton
public class N9mDeviceRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(N9mDeviceRegistry.class);
    private static final String ATTRIBUTE_DSNO = "n9mSerial";

    private final Storage storage;

    @Inject
    public N9mDeviceRegistry(Storage storage) {
        this.storage = storage;
    }

    public Long lookup(String dsno) {
        if (dsno == null || dsno.isBlank()) {
            return null;
        }
        try {
            for (Device device : storage.getObjects(Device.class, new Request(new Columns.All()))) {
                if (dsno.equals(device.getString(ATTRIBUTE_DSNO))) {
                    return device.getId();
                }
            }
        } catch (Exception e) {
            LOGGER.warn("N9M device registry lookup failed for DSNO={}", dsno, e);
        }
        return null;
    }

}
