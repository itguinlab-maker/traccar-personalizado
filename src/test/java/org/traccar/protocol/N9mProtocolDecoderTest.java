package org.traccar.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import org.junit.jupiter.api.Test;
import org.traccar.ProtocolTest;
import org.traccar.database.N9mDeviceRegistry;
import org.traccar.model.Position;

import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Cobertura del canal de control N9M. N9M es la fuente de conteo de pasajeros y el canal de
 * comandos de video, así que una regresión aquí rompe el producto principal: estos tests existen
 * para que un despliegue no tumbe el conteo en silencio.
 *
 * El decoder resuelve el dispositivo por DSNO (atributo {@code n9mSerial}) y no por el uniqueId
 * de JT808, por eso hay que inyectar un {@link N9mDeviceRegistry} simulado y registrar el canal
 * con un CONNECT antes de poder mandarle eventos.
 */
public class N9mProtocolDecoderTest extends ProtocolTest {

    private static final String DSNO = "00E4006A50";

    private N9mProtocolDecoder createDecoder() throws Exception {
        // N9mProtocol real no se puede instanciar aquí: su constructor levanta un TrackerServer
        // y deriva el nombre del protocolo del nombre de la clase. Basta con simularlo.
        var protocol = mock(N9mProtocol.class);
        when(protocol.getName()).thenReturn("n9m");
        var decoder = inject(new N9mProtocolDecoder(protocol));

        var registry = mock(N9mDeviceRegistry.class);
        when(registry.lookup(DSNO)).thenReturn(1L);
        decoder.setDeviceRegistry(registry);
        decoder.setN9mConnectionManager(mock(org.traccar.session.ConnectionManager.class));
        return decoder;
    }

    /** Arma la trama N9M: marcador 0x08, 5 reservados, longitud (2B), 0x52, 3 reservados, JSON. */
    private ByteBuf frame(String json) {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        ByteBuf buf = io.netty.buffer.Unpooled.buffer();
        buf.writeByte(0x08);
        buf.writeZero(5);
        buf.writeShort(payload.length);
        buf.writeByte(0x52);
        buf.writeZero(3);
        buf.writeBytes(payload);
        return buf;
    }

    private Object decode(N9mProtocolDecoder decoder, Channel channel, String json) throws Exception {
        return decoder.decode(channel, mock(SocketAddress.class), frame(json));
    }

    /** Registra el canal como perteneciente al DSNO conocido: sin esto el decoder descarta todo. */
    private Channel connect(N9mProtocolDecoder decoder) throws Exception {
        Channel channel = mock(Channel.class);
        when(channel.writeAndFlush(any())).thenReturn(null);
        decode(decoder, channel, "{\"MODULE\":\"CERTIFICATE\",\"OPERATION\":\"CONNECT\","
                + "\"PARAMETER\":{\"DSNO\":\"" + DSNO + "\"},\"SESSION\":\"s1\"}");
        return channel;
    }

    @Test
    public void testCountingEvent() throws Exception {
        var decoder = createDecoder();
        Channel channel = connect(decoder);

        Object decoded = decode(decoder, channel,
                "{\"MODULE\":\"DEVEMM\",\"OPERATION\":\"UPPSTATISTICS\",\"SESSION\":\"s1\","
                        + "\"PARAMETER\":{\"CMDNO\":1,\"UPP\":3,\"DOWNP\":1,\"DOORID\":1,"
                        + "\"P\":{\"W\":\"6.26577\",\"J\":\"-75.54688\",\"T\":\"20260912093000\"}}}");

        assertNotNull(decoded, "UPPSTATISTICS debe producir una posición");
        Position position = (Position) decoded;

        // Conteo: es el dato que se factura, tiene que llegar exacto.
        assertEquals(3, position.getInteger("passengersOn"));
        assertEquals(1, position.getInteger("passengersOff"));
        assertEquals("counting_event", position.getString("streamax.status"));
        assertEquals("n9m", position.getString("streamax.source"));

        // GPS del instante del evento: es lo que diferencia a N9M de JT808 para conteo.
        assertTrue(position.getValid());
        assertEquals(6.26577, position.getLatitude(), 0.00001);
        assertEquals(-75.54688, position.getLongitude(), 0.00001);
    }

    /**
     * La huella del evento es la clave de deduplicación que usa FilterHandler. Sin ella, la
     * retransmisión del MDVR tras reconectar infla los totales (medido en campo: +49%).
     */
    @Test
    public void testCountingEventHasDeduplicationKey() throws Exception {
        var decoder = createDecoder();
        Channel channel = connect(decoder);

        Position position = (Position) decode(decoder, channel,
                "{\"MODULE\":\"DEVEMM\",\"OPERATION\":\"UPPSTATISTICS\",\"SESSION\":\"s1\","
                        + "\"PARAMETER\":{\"CMDNO\":1,\"UPP\":2,\"DOWNP\":0,\"DOORID\":1,"
                        + "\"P\":{\"W\":\"6.1\",\"J\":\"-75.1\",\"T\":\"20260912100000\"}}}");

        String raw = position.getString("streamax.raw");
        assertNotNull(raw, "streamax.raw es obligatorio para deduplicar retransmisiones");
        assertTrue(raw.contains("UPP"), "la huella debe conservar el payload del evento");
    }

    /** Dos eventos distintos deben producir huellas distintas, o se descartarían como duplicados. */
    @Test
    public void testDifferentEventsProduceDifferentKeys() throws Exception {
        var decoder = createDecoder();
        Channel channel = connect(decoder);

        Position first = (Position) decode(decoder, channel,
                "{\"MODULE\":\"DEVEMM\",\"OPERATION\":\"UPPSTATISTICS\",\"SESSION\":\"s1\","
                        + "\"PARAMETER\":{\"CMDNO\":1,\"UPP\":1,\"DOWNP\":0,\"DOORID\":1,"
                        + "\"P\":{\"W\":\"6.1\",\"J\":\"-75.1\",\"T\":\"20260912100000\"}}}");
        Position second = (Position) decode(decoder, channel,
                "{\"MODULE\":\"DEVEMM\",\"OPERATION\":\"UPPSTATISTICS\",\"SESSION\":\"s1\","
                        + "\"PARAMETER\":{\"CMDNO\":2,\"UPP\":5,\"DOWNP\":0,\"DOORID\":1,"
                        + "\"P\":{\"W\":\"6.1\",\"J\":\"-75.1\",\"T\":\"20260912100500\"}}}");

        assertTrue(!first.getString("streamax.raw").equals(second.getString("streamax.raw")),
                "eventos con distinto conteo/hora no pueden compartir huella");
    }

    /** El mapeo de puerta debe conservar el id original del equipo para poder auditar errores. */
    @Test
    public void testDoorMapping() throws Exception {
        var decoder = createDecoder();
        Channel channel = connect(decoder);

        Position position = (Position) decode(decoder, channel,
                "{\"MODULE\":\"DEVEMM\",\"OPERATION\":\"UPPSTATISTICS\",\"SESSION\":\"s1\","
                        + "\"PARAMETER\":{\"CMDNO\":1,\"UPP\":2,\"DOWNP\":0,\"DOORID\":1,"
                        + "\"P\":{\"W\":\"6.1\",\"J\":\"-75.1\",\"T\":\"20260912100000\"}}}");

        assertEquals(1, position.getInteger("streamax.doorId"));
        assertEquals("front", position.getString("streamax.doorEffective"));
        assertEquals(2, position.getInteger("passengersOnFront"));
    }

    /** Un evento de una puerta trasera debe sumar a los atributos Rear, no a los Front. */
    @Test
    public void testRearDoorMapping() throws Exception {
        var decoder = createDecoder();
        Channel channel = connect(decoder);

        Position position = (Position) decode(decoder, channel,
                "{\"MODULE\":\"DEVEMM\",\"OPERATION\":\"UPPSTATISTICS\",\"SESSION\":\"s1\","
                        + "\"PARAMETER\":{\"CMDNO\":1,\"UPP\":0,\"DOWNP\":4,\"DOORID\":2,"
                        + "\"P\":{\"W\":\"6.1\",\"J\":\"-75.1\",\"T\":\"20260912100000\"}}}");

        assertEquals("rear", position.getString("streamax.doorEffective"));
        assertEquals(4, position.getInteger("passengersOffRear"));
        assertEquals(4, position.getInteger("passengersOff"));
    }

    /** Un evento de un DSNO no registrado no puede atribuirse a otro vehículo. */
    @Test
    public void testUnknownDeviceIsIgnored() throws Exception {
        var decoder = createDecoder();
        Channel channel = mock(Channel.class);
        when(channel.writeAndFlush(any())).thenReturn(null);

        assertNull(decode(decoder, channel,
                "{\"MODULE\":\"CERTIFICATE\",\"OPERATION\":\"CONNECT\","
                        + "\"PARAMETER\":{\"DSNO\":\"DESCONOCIDO\"},\"SESSION\":\"s1\"}"),
                "un DSNO no registrado no debe resolver ningún dispositivo");
    }

    /** El keepalive mantiene la sesión viva pero no debe generar posiciones. */
    @Test
    public void testKeepaliveProducesNoPosition() throws Exception {
        var decoder = createDecoder();
        Channel channel = connect(decoder);

        assertNull(decode(decoder, channel,
                "{\"MODULE\":\"CERTIFICATE\",\"OPERATION\":\"KEEPALIVE\",\"SESSION\":\"s1\"}"));
    }

    /** Tramas que no son JSON (pings binarios del equipo) no deben tumbar el decoder. */
    @Test
    public void testNonJsonFrameIsIgnored() throws Exception {
        var decoder = createDecoder();
        Channel channel = connect(decoder);

        assertNull(decode(decoder, channel, "no-es-json"));
    }
}
