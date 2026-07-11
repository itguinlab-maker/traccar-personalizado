package org.traccar.datausage;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.AttributeKey;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Cuenta los bytes crudos que pasan por el socket en ambas direcciones
 * (todo el consumo de la SIM: posiciones, retransmisiones, respuestas,
 * comandos y video). Los bytes quedan pendientes en el canal hasta que
 * un decoder identifica el dispositivo y los acredita via
 * {@link DataUsageManager#flushChannel}.
 */
public class DataUsageChannelHandler extends ChannelDuplexHandler {

    public static final AttributeKey<AtomicLong> PENDING_RX = AttributeKey.valueOf("dataUsagePendingRx");
    public static final AttributeKey<AtomicLong> PENDING_TX = AttributeKey.valueOf("dataUsagePendingTx");
    public static final AttributeKey<Long> DEVICE_ID = AttributeKey.valueOf("dataUsageDeviceId");

    private final DataUsageManager dataUsageManager;

    public DataUsageChannelHandler(DataUsageManager dataUsageManager) {
        this.dataUsageManager = dataUsageManager;
    }

    private static AtomicLong pending(ChannelHandlerContext ctx, AttributeKey<AtomicLong> key) {
        AtomicLong counter = ctx.channel().attr(key).get();
        if (counter == null) {
            ctx.channel().attr(key).setIfAbsent(new AtomicLong());
            counter = ctx.channel().attr(key).get();
        }
        return counter;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof ByteBuf buf) {
            pending(ctx, PENDING_RX).addAndGet(buf.readableBytes());
        }
        super.channelRead(ctx, msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof ByteBuf buf) {
            pending(ctx, PENDING_TX).addAndGet(buf.readableBytes());
        }
        super.write(ctx, msg, promise);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        Long deviceId = ctx.channel().attr(DEVICE_ID).get();
        if (deviceId != null && dataUsageManager != null) {
            dataUsageManager.flushChannel(ctx.channel(), deviceId, false);
        }
        super.channelInactive(ctx);
    }
}
