package com.example.galboss.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务端 → 客户端：把「你被锁定了」打到玩家屏幕上（狙击技能的预警）。
 *
 * <p>只发给被锁定的那个人（不是半径广播）：被瞄准的提示只有当事人该看见，
 * 旁边的人看到只会变成噪音。渲染在 {@code client/gui/SniperLockOverlay}。
 *
 * @param ticks    总锁定时长（tick）
 * @param release  true = 解除锁定（命中或中断），客户端立刻清掉提示
 */
public class S2CSniperLockPacket {

    private final int ticks;
    private final boolean release;

    public S2CSniperLockPacket(int ticks, boolean release) {
        this.ticks = Math.max(1, ticks);
        this.release = release;
    }

    public static S2CSniperLockPacket lock(int ticks) {
        return new S2CSniperLockPacket(ticks, false);
    }

    public static S2CSniperLockPacket release() {
        return new S2CSniperLockPacket(1, true);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(this.ticks);
        buf.writeBoolean(this.release);
    }

    public static S2CSniperLockPacket decode(FriendlyByteBuf buf) {
        return new S2CSniperLockPacket(buf.readVarInt(), buf.readBoolean());
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            if (this.release) {
                com.example.galboss.client.gui.SniperLockOverlay.release();
            } else {
                com.example.galboss.client.gui.SniperLockOverlay.lock(this.ticks);
            }
        });
    }
}
