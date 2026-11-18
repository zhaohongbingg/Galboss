package com.example.galboss.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务端 → 客户端：画一条「红外线瞄准（激光）」。
 *
 * <p>狙击技能锁定目标期间每隔几 tick 发一次（每次只活很短），所以线会跟着目标动；
 * 客户端把它画成一条贯穿目标的红色细线（见 {@code client/fx/SniperBeamRenderer}）。
 *
 * <p>为什么不用粒子：粒子受深度测试影响，会被墙和玩家模型挡住；
 * 瞄准镜那种「贯穿一切的红外线」必须关掉深度测试自己画四边形。
 */
public class S2CSniperBeamPacket {

    private final double x1;
    private final double y1;
    private final double z1;
    private final double x2;
    private final double y2;
    private final double z2;
    /** 存活 tick。 */
    private final int ticks;

    public S2CSniperBeamPacket(Vec3 from, Vec3 to, int ticks) {
        this(from.x, from.y, from.z, to.x, to.y, to.z, ticks);
    }

    public S2CSniperBeamPacket(double x1, double y1, double z1,
                               double x2, double y2, double z2, int ticks) {
        this.x1 = x1;
        this.y1 = y1;
        this.z1 = z1;
        this.x2 = x2;
        this.y2 = y2;
        this.z2 = z2;
        this.ticks = Math.max(1, ticks);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeDouble(this.x1);
        buf.writeDouble(this.y1);
        buf.writeDouble(this.z1);
        buf.writeDouble(this.x2);
        buf.writeDouble(this.y2);
        buf.writeDouble(this.z2);
        buf.writeVarInt(this.ticks);
    }

    public static S2CSniperBeamPacket decode(FriendlyByteBuf buf) {
        return new S2CSniperBeamPacket(buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readVarInt());
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        // 客户端专有类只能间接引用，否则专用服务器加载到这就炸
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.example.galboss.client.fx.SniperBeamRenderer.push(
                new Vec3(this.x1, this.y1, this.z1), new Vec3(this.x2, this.y2, this.z2), this.ticks));
    }
}
