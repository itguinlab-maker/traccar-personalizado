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
 * Framing for the N9M media connection (opened by the device after REQUESTALIVEVIDEO/
 * REQUESTREMOTEPLAYBACK on the {@link N9mProtocol} control channel).
 *
 * The FIRST frame on this connection is the CERTIFICATE/CREATESTREAM handshake, which uses the exact
 * same 12-byte-header + JSON envelope as the control channel (confirmed by capture). Everything after
 * that is a plain H.264 Annex-B elementary stream — confirmed 2026-08-18 by dumping a real live-view
 * session to disk: clean {@code 00 00 00 01} NAL start codes, no RTP/SSRC wrapper despite the
 * SSRC/CSRC/PT terminology in the control channel's MEDIATASKSTART message (just session bookkeeping,
 * not a per-packet header). This decoder passes raw bytes through unmodified — {@link
 * N9mMediaProtocolDecoder} does the actual NAL-boundary splitting, since TCP read boundaries here have
 * no relation to NAL boundaries.
 */
public class N9mMediaFrameDecoder extends BaseFrameDecoder {

    private static final int HEADER_LENGTH = 12;

    private boolean handshakeDone;

    @Override
    protected Object decode(ChannelHandlerContext ctx, Channel channel, ByteBuf buf) throws Exception {

        if (!handshakeDone) {
            if (buf.readableBytes() < HEADER_LENGTH) {
                return null;
            }
            int startIndex = buf.readerIndex();
            int payloadLength = buf.getUnsignedShort(startIndex + 6);
            int totalLength = HEADER_LENGTH + payloadLength;
            if (buf.readableBytes() < totalLength) {
                return null;
            }
            handshakeDone = true;
            return buf.readRetainedSlice(totalLength);
        }

        if (buf.readableBytes() == 0) {
            return null;
        }
        return buf.readRetainedSlice(buf.readableBytes());
    }

}
