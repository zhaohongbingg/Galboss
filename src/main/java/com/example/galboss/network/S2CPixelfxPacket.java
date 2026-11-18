package com.example.galboss.network;

import com.example.galboss.client.fx.PixelfxClientHooks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务端 → 客户端：在指定位置播放一个 pixelfx 序列帧特效。
 *
 * <p>客户端纯表现，服务端只负责「告诉谁、在哪、播什么」。所有帧数 / 循环 / 朝向 / 贴图
 * 都由客户端按 {@code assets/galboss/pixelfx/<slug>.json} 自己解析，这个包刻意只带
 * 最少的信息，避免版本不一致时把资源细节也传坏。
 *
 * <p>与 {@link S2CPetMessagePacket} 同一个套路：客户端专有类只在
 * {@link DistExecutor} 的延迟 lambda 里出现，专用服务器加载这个包不会碰到
 * {@code net.minecraft.client.*}。
 */
public class S2CPixelfxPacket {

    private final String slug;
    private final double x;
    private final double y;
    private final double z;
    private final float scale;
    private final float yaw;
    /** 0 = 一次性特效（播完即消失）；>0 = 寿命上限（循环特效用）。 */
    private final int lifeTicks;

    public S2CPixelfxPacket(String slug, double x, double y, double z,
                            float scale, float yaw, int lifeTicks) {
        this.slug = slug == null ? "" : slug;
        this.x = x;
        this.y = y;
        this.z = z;
        this.scale = scale;
        this.yaw = yaw;
        this.lifeTicks = lifeTicks;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(this.slug);
        buf.writeDouble(this.x);
        buf.writeDouble(this.y);
        buf.writeDouble(this.z);
        buf.writeFloat(this.scale);
        buf.writeFloat(this.yaw);
        buf.writeVarInt(this.lifeTicks);
    }

    public static S2CPixelfxPacket decode(FriendlyByteBuf buf) {
        return new S2CPixelfxPacket(
                buf.readUtf(),
                buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readFloat(), buf.readFloat(),
                buf.readVarInt());
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        // 客户端专有逻辑只能这样间接引用，否则专用服务器加载到这个包就会炸
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> PixelfxClientHooks.onPacket(
                this.slug, this.x, this.y, this.z, this.scale, this.yaw, this.lifeTicks));
    }
}
