package com.example.galboss.network;

import com.example.galboss.client.ClientPetHooks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务端 → 客户端：宠物说了一句话，或者给出了一条反馈。
 *
 * <p>只传语言键和至多一个参数，文本在客户端本地化。界面开着就显示在界面里，
 * 没开就退化成聊天栏消息。
 */
public class S2CPetMessagePacket {

    private final int entityId;
    private final String langKey;
    private final String arg;

    public S2CPetMessagePacket(int entityId, String langKey, String arg) {
        this.entityId = entityId;
        this.langKey = langKey;
        this.arg = arg == null ? "" : arg;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(this.entityId);
        buf.writeUtf(this.langKey);
        buf.writeUtf(this.arg);
    }

    public static S2CPetMessagePacket decode(FriendlyByteBuf buf) {
        return new S2CPetMessagePacket(buf.readVarInt(), buf.readUtf(), buf.readUtf());
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        // 客户端专有类只能这样间接引用，否则专用服务器加载到这个包就会炸
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientPetHooks.onPetMessage(this.entityId, this.langKey, this.arg));
    }
}
