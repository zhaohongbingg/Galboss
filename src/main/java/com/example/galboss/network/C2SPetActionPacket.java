package com.example.galboss.network;

import com.example.galboss.GalBoss;
import com.example.galboss.entity.FsPetEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 → 服务端：玩家在宠物界面上做了一个操作。
 *
 * <p>只带"宠物实体 id + 动作 + 参数"，物品一律以服务端手上的为准（送礼取主手），
 * 所以客户端改包也拿不到额外好处。
 */
public class C2SPetActionPacket {

    public static final byte ACTION_GIFT = 0;
    public static final byte ACTION_TOGGLE_SIT = 1;
    public static final byte ACTION_CHAT = 2;
    public static final byte ACTION_TRADE = 3;

    /** 界面操作的最大有效距离（格），防止隔着半张地图遥控宠物交易。 */
    private static final double MAX_DISTANCE_SQR = 64.0 * 64.0;

    private final int entityId;
    private final byte action;
    private final int arg;

    public C2SPetActionPacket(int entityId, byte action, int arg) {
        this.entityId = entityId;
        this.action = action;
        this.arg = arg;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(this.entityId);
        buf.writeByte(this.action);
        buf.writeVarInt(this.arg);
    }

    public static C2SPetActionPacket decode(FriendlyByteBuf buf) {
        return new C2SPetActionPacket(buf.readVarInt(), buf.readByte(), buf.readVarInt());
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        if (player == null) {
            return;
        }
        Entity entity = player.level().getEntity(this.entityId);
        if (!(entity instanceof FsPetEntity pet)) {
            // 「面板点了没反应」这类问题最难查：包可能压根没到、也可能到了但被这里挡掉。
            // 三条日志（客户端发出 / 服务端收到 / 服务端判定结果）合起来能把链路一刀切开。
            GalBoss.LOGGER.warn("[panel] 收到动作 {} 但实体 {} 不是搭档（实际: {}）",
                    this.action, this.entityId,
                    entity == null ? "null（未加载/维度不符）" : entity.getType());
            return;
        }
        if (player.distanceToSqr(pet) > MAX_DISTANCE_SQR || !pet.isOwnedBy(player)) {
            GalBoss.LOGGER.warn("[panel] 动作 {} 被拒：距离²={} 归属={}",
                    this.action, player.distanceToSqr(pet), pet.isOwnedBy(player));
            return;
        }
        GalBoss.LOGGER.debug("[panel] player={} 动作={} 搭档={}",
                player.getName().getString(), this.action, this.entityId);

        switch (this.action) {
            case ACTION_GIFT -> pet.giftFromPanel(player);
            case ACTION_TOGGLE_SIT -> pet.toggleSit(player);
            case ACTION_CHAT -> pet.tryChat(player, this.arg);
            case ACTION_TRADE -> pet.tryTrade(player, this.arg);
            default -> {
            }
        }
    }
}
