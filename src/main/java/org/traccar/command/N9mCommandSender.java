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
package org.traccar.command;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.traccar.model.Command;
import org.traccar.model.Device;
import org.traccar.protocol.N9mProtocol;

import java.util.Collection;
import java.util.List;

/**
 * Routes video commands to the N9M control channel for devices with {@code mdvrMode=n9m}, bypassing
 * the standard {@code DeviceSession} (which stays owned by JT808 for these devices). Selected by
 * {@link CommandSenderManager#getSender(Device)}.
 */
@Singleton
public class N9mCommandSender implements CommandSender {

    private final N9mProtocol n9mProtocol;

    @Inject
    public N9mCommandSender(N9mProtocol n9mProtocol) {
        this.n9mProtocol = n9mProtocol;
    }

    @Override
    public Collection<String> getSupportedCommands() {
        return List.of(
                Command.TYPE_VIDEO_START, Command.TYPE_VIDEO_STOP,
                Command.TYPE_VIDEO_DOWNLOAD, Command.TYPE_VIDEO_QUERY);
    }

    @Override
    public void sendCommand(Device device, Command command) throws Exception {
        if (Command.TYPE_VIDEO_STOP.equals(command.getType())) {
            // Pure local cleanup, no wire message — see N9mProtocol.closeActiveMediaChannel javadoc.
            // Handled here (not in N9mProtocolEncoder) because BaseProtocolEncoder.write() always
            // tries to write whatever encodeCommand() returns, even null, which crashes Netty with
            // an NPE — there's no "supported command that legitimately sends nothing" convention.
            n9mProtocol.stopLiveViewLoop(device.getId());
            n9mProtocol.closeActiveMediaChannel(device.getId());
            return;
        }
        if (Command.TYPE_VIDEO_DOWNLOAD.equals(command.getType())) {
            // The device only has one media task slot — a background live-view restart loop firing
            // mid-download would fight the download for it, so stop the loop first (a fresh "live
            // view" click after the download starts a new one, same as normal).
            n9mProtocol.stopLiveViewLoop(device.getId());
        }
        n9mProtocol.sendControlCommand(device.getId(), command);
        if (Command.TYPE_VIDEO_START.equals(command.getType())) {
            n9mProtocol.startLiveViewLoop(device.getId(), command.getInteger(Command.KEY_INDEX, 1));
        }
    }

}
