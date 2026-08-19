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

import org.traccar.BaseProtocol;
import org.traccar.PipelineBuilder;
import org.traccar.TrackerServer;
import org.traccar.config.Config;

import jakarta.inject.Inject;

/**
 * N9M media (video/audio) ingest server — the address devices are told to dial back to via
 * {@code IPANDPORT} in REQUESTALIVEVIDEO/REQUESTREMOTEPLAYBACK on the {@link N9mProtocol} control
 * channel. Receive-only (no encoder, no commands declared), same structural role as
 * {@link Jt1078Protocol} for JT808/JT1078.
 */
public class N9mMediaProtocol extends BaseProtocol {

    @Inject
    public N9mMediaProtocol(Config config) {
        addServer(new TrackerServer(config, getName(), false) {
            @Override
            protected void addProtocolHandlers(PipelineBuilder pipeline, Config config) {
                pipeline.addLast(new N9mMediaFrameDecoder());
                pipeline.addLast(new N9mMediaProtocolDecoder(N9mMediaProtocol.this));
            }
        });
    }

}
