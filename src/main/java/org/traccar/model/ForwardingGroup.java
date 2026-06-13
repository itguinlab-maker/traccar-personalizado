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
package org.traccar.model;

import java.util.HashSet;
import java.util.Set;

/**
 * A named group of devices that should have their counting events forwarded
 * to a specific external REST server.
 */
public class ForwardingGroup {

    private String id;
    private String name;
    private String serverUrl;
    private String username;
    private String password;
    private Set<Long> deviceIds = new HashSet<>();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getServerUrl() { return serverUrl; }
    public void setServerUrl(String serverUrl) { this.serverUrl = serverUrl; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public Set<Long> getDeviceIds() { return deviceIds; }
    public void setDeviceIds(Set<Long> deviceIds) { this.deviceIds = deviceIds != null ? deviceIds : new HashSet<>(); }
}
