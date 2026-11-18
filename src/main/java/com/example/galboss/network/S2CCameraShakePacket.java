package com.example.galboss.network;

import com.example.galboss.client.ScreenShake;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务端 → 客户端：让画面震一下（落雷 / 锤击 / 巨物坠地）。
 *
 * <p>纯表现：服务端只给「多强、多少 tick」，真正的抖动在 {@link ScreenShake} 里按正弦衰减算。
 * 客户端专有类只在 DistExecutor 的延迟 lambda 里出现，专用服务器加载这个包不会碰
 * {@code net.minecraft.client.*}。
 */
public class S2CCameraShakePacket {

    /** 抖动幅度（单位：度）。 */
    private final float strength;
    /** 持续 tick。 */
    private final int ticks;

    public S2CCameraShakePacket(float strength, int ticks) {
        this.strength = Math.max(0.0f, Math.min(4.0f, strength));
        this.ticks = Math.max(2, Math.min(60, ticks));
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeFloat(this.strength);
        buf.writeVarInt(this.ticks);
    }

    public static S2CCameraShakePacket decode(FriendlyByteBuf buf) {
        return new S2CCameraShakePacket(buf.readFloat(), buf.readVarInt());
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ScreenShake.push(this.strength, this.ticks));
    }
}
