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

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import org.traccar.BaseFrameDecoder;

/**
 * N9M control-channel framing (Streamax MDVR JSON-over-TCP protocol).
 *
 * Confirmed by byte-level analysis of real captured traffic (2026-08-17), verified against
 * three independent samples (two device-to-server, one server-to-device), all matching exactly:
 *
 * <pre>
 * byte[0]      = 0x08 (constant)
 * bytes[1-5]   = 0x00 * 5 (reserved)
 * bytes[6-7]   = uint16 BE, length of the JSON payload that follows this header
 * byte[8]      = 0x52 ('R', constant marker)
 * bytes[9-11]  = 0x00 * 3 (reserved)
 * bytes[12..]  = JSON payload, exactly {@code length} bytes (may or may not include a trailing '\n')
 * </pre>
 */
public class N9mFrameDecoder extends BaseFrameDecoder {

    private static final int HEADER_LENGTH = 12;

    @Override
    protected Object decode(ChannelHandlerContext ctx, Channel channel, ByteBuf buf) throws Exception {

        if (buf.readableBytes() < HEADER_LENGTH) {
            return null;
        }

        int startIndex = buf.readerIndex();
        int payloadLength = buf.getUnsignedShort(startIndex + 6);

        int totalLength = HEADER_LENGTH + payloadLength;
        if (buf.readableBytes() < totalLength) {
            return null;
        }

        return buf.readRetainedSlice(totalLength);
    }

}
