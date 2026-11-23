package com.example.galboss.network;

import com.example.galboss.client.fx.PixelfxClientHooks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务端 → 客户端：在**两个实体之间**拉伸播放一个 pixelfx 序列帧特效。
 *
 * <p>和 {@link S2CPixelfxPacket} 的区别：那个带的是固定坐标，这个带的是两个实体 id，
 * 客户端每帧自己去查实体当前位置，所以链子能跟着移动的目标走。
 *
 * <p>只传 id 不传坐标还有一个好处：客户端读到的是**插值后**的位置（{@code getPosition(partialTick)}），
 * 60fps 下链子是平滑的，不会一格一格跳。
 */
public class S2CPixelfxLinkPacket {

    private final String slug;
    /** 起点实体 id。 */
    private final int entityA;
    /** 终点实体 id。 */
    private final int entityB;
    private final float scale;
    /** 0 = 一次性；>0 = 寿命上限。 */
    private final int lifeTicks;

    public S2CPixelfxLinkPacket(String slug, int entityA, int entityB, float scale, int lifeTicks) {
        this.slug = slug == null ? "" : slug;
        this.entityA = entityA;
        this.entityB = entityB;
        this.scale = scale;
        this.lifeTicks = lifeTicks;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(this.slug);
        buf.writeVarInt(this.entityA);
        buf.writeVarInt(this.entityB);
        buf.writeFloat(this.scale);
        buf.writeVarInt(this.lifeTicks);
    }

    public static S2CPixelfxLinkPacket decode(FriendlyByteBuf buf) {
        return new S2CPixelfxLinkPacket(
                buf.readUtf(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readFloat(),
                buf.readVarInt());
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        // 客户端专有逻辑只能这样间接引用，否则专用服务器加载到这个包就会炸
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> PixelfxClientHooks.onLinkPacket(
                this.slug, this.entityA, this.entityB, this.scale, this.lifeTicks));
    }
}
