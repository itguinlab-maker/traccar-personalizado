package org.traccar.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.traccar.ProtocolTest;
import org.traccar.model.Command;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class Jt808ProtocolEncoderTest extends ProtocolTest {

    @Disabled
    @Test
    public void testEncode() throws Exception {

        var encoder = inject(new Jt808ProtocolEncoder(null));

        Command command = new Command();
        command.setDeviceId(1);
        command.setType(Command.TYPE_ENGINE_STOP);

        verifyCommand(encoder, command, binary("7e81050001080201000027001ff0467e"));

    }

    @Test
    public void testEncodeVideoQuery() throws Exception {

        var encoder = inject(new Jt808ProtocolEncoder(null));
        var channel = new EmbeddedChannel(inject(new Jt808ProtocolDecoder(null)));

        Command command = new Command();
        command.setDeviceId(1);
        command.setType(Command.TYPE_VIDEO_QUERY);
        command.set(Command.KEY_INDEX, 1);
        command.set(Command.KEY_START_TIME, 1781648245L); // 2026-06-16 17:17:25 GMT-5
        command.set(Command.KEY_END_TIME, 1781648310L);   // 2026-06-16 17:18:30 GMT-5

        Object encoded = encoder.encodeCommand(channel, command);
        assertNotNull(encoded, "0x9205 debe codificarse");
        assertInstanceOf(ByteBuf.class, encoded);

        String hex = ByteBufUtil.hexDump((ByteBuf) encoded);
        assertTrue(hex.startsWith("7e9205"), "el mensaje debe ser 0x9205: " + hex);
        assertTrue(hex.contains("260616171725"), "debe contener hora inicio BCD: " + hex);
        assertTrue(hex.contains("260616171830"), "debe contener hora fin BCD: " + hex);

    }

}
