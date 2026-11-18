package com.example.galboss.network;

import com.example.galboss.client.gui.RedTruthOverlay;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务端 → 客户端：把一句话作为「红字」打在屏幕上（贝阿朵莉切的游戏主宰权能）。
 *
 * <p>红字是纯表现，但<b>不能把翻好的字符串传过去</b>：专用服务端没有客户端语言，
 * {@code Component#getString()} 在服务端永远拿 en_us，中英文客户端会一起看到英文。
 * 所以这里传的是<b>语言键</b>，由客户端按自己的语言解析（{@link #i18n} = true）；
 * 调试命令那种随便打一句话的场合才传字面量。
 *
 * <p>渲染在 {@link RedTruthOverlay}，走原版 HUD，不碰世界渲染层。
 */
public class S2CRedTruthPacket {

    /** 字面文本，或（{@link #i18n} 为 true 时）语言键。 */
    private final String text;
    /** 语言键的 %1$s 参数；空串表示没有参数。 */
    private final String arg;
    /** true = text 是语言键，交给客户端按本地语言解析。 */
    private final boolean i18n;
    /** 停留时长（tick）。 */
    private final int ticks;

    public S2CRedTruthPacket(String text, String arg, boolean i18n, int ticks) {
        this.text = text == null ? "" : text;
        this.arg = arg == null ? "" : arg;
        this.i18n = i18n;
        this.ticks = Math.max(10, ticks);
    }

    /** 语言键版本（正式流程用这个）。 */
    public static S2CRedTruthPacket ofKey(String key, int ticks) {
        return new S2CRedTruthPacket(key, "", true, ticks);
    }

    /** 语言键 + 一个参数版本。 */
    public static S2CRedTruthPacket ofKey(String key, String arg, int ticks) {
        return new S2CRedTruthPacket(key, arg, true, ticks);
    }

    /** 字面文本版本（调试命令用）。 */
    public static S2CRedTruthPacket ofLiteral(String text, int ticks) {
        return new S2CRedTruthPacket(text, "", false, ticks);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(this.text);
        buf.writeUtf(this.arg);
        buf.writeBoolean(this.i18n);
        buf.writeVarInt(this.ticks);
    }

    public static S2CRedTruthPacket decode(FriendlyByteBuf buf) {
        return new S2CRedTruthPacket(buf.readUtf(), buf.readUtf(), buf.readBoolean(), buf.readVarInt());
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        // 客户端专有类只能这样间接引用，否则专用服务器加载到这个包就会炸
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> RedTruthOverlay.push(
                this.text, this.arg, this.i18n, this.ticks));
    }
}
