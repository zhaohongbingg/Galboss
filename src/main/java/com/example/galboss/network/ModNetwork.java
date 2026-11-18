package com.example.galboss.network;

import com.example.galboss.GalBoss;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * 本模组的网络通道。
 *
 * <p>四个包：
 * <ul>
 *   <li>{@link C2SPetActionPacket} —— 客户端界面点按钮，服务端做校验与结算</li>
 *   <li>{@link S2CPetMessagePacket} —— 服务端把宠物的反馈同步给界面</li>
 *   <li>{@link S2CPixelfxPacket} —— 服务端把 boss 技能的序列帧特效广播给附近玩家（纯表现）</li>
 *   <li>{@link S2CRedTruthPacket} —— 服务端把一句「红字」打到附近玩家屏幕正中（纯表现）</li>
 * </ul>
 *
 * <p>所有真实结算都在服务端，客户端只负责发"我想干什么"。
 */
public final class ModNetwork {

    private static final String PROTOCOL = "1";

    private static int nextId = 0;

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(GalBoss.MOD_ID, "main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals);

    private ModNetwork() {
    }

    public static void register() {
        CHANNEL.messageBuilder(C2SPetActionPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2SPetActionPacket::encode)
                .decoder(C2SPetActionPacket::decode)
                .consumerMainThread(C2SPetActionPacket::handle)
                .add();

        CHANNEL.messageBuilder(S2CPetMessagePacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CPetMessagePacket::encode)
                .decoder(S2CPetMessagePacket::decode)
                .consumerMainThread(S2CPetMessagePacket::handle)
                .add();

        // pixelfx 序列帧特效：服务端 → 客户端，客户端纯表现
        CHANNEL.messageBuilder(S2CPixelfxPacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CPixelfxPacket::encode)
                .decoder(S2CPixelfxPacket::decode)
                .consumerMainThread(S2CPixelfxPacket::handle)
                .add();

        // 红字：服务端 → 客户端，屏幕正中的一句绝对断言（客户端按自己的语言解析语言键）
        CHANNEL.messageBuilder(S2CRedTruthPacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CRedTruthPacket::encode)
                .decoder(S2CRedTruthPacket::decode)
                .consumerMainThread(S2CRedTruthPacket::handle)
                .add();

        // 屏震：落雷 / 锤击 / 巨物坠地时抖一下相机（纯表现）
        CHANNEL.messageBuilder(S2CCameraShakePacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CCameraShakePacket::encode)
                .decoder(S2CCameraShakePacket::decode)
                .consumerMainThread(S2CCameraShakePacket::handle)
                .add();

        // 狙击瞄准线：一条贯穿目标的红外线（关深度测试自己画，所以不走粒子）
        CHANNEL.messageBuilder(S2CSniperBeamPacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CSniperBeamPacket::encode)
                .decoder(S2CSniperBeamPacket::decode)
                .consumerMainThread(S2CSniperBeamPacket::handle)
                .add();

        // 被锁定的屏幕提示（只发给被瞄准的那个人）
        CHANNEL.messageBuilder(S2CSniperLockPacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CSniperLockPacket::encode)
                .decoder(S2CSniperLockPacket::decode)
                .consumerMainThread(S2CSniperLockPacket::handle)
                .add();
    }

    /** 半径内的玩家都画一条瞄准线（旁观者也能看见这道红外线）。 */
    public static void sendSniperBeam(Level level, Vec3 from, Vec3 to, double radius, int ticks) {
        broadcast(level, to, radius, new S2CSniperBeamPacket(from, to, ticks));
    }

    /** 半径内的玩家一起震屏。 */
    public static void sendScreenShake(Level level, Vec3 origin, double radius, float strength, int ticks) {
        broadcast(level, origin, radius, new S2CCameraShakePacket(strength, ticks));
    }

    /**
     * 把一个语言键当红字发给半径内的玩家。
     *
     * <p>传的是<b>语言键</b>而不是渲染好的字符串：专用服务端没有客户端语言，
     * 在服务端 {@code getString()} 出来的永远是英文。
     */
    public static void sendRedTruthKey(Level level, Vec3 origin, double radius, String key, int ticks) {
        broadcast(level, origin, radius, S2CRedTruthPacket.ofKey(key, ticks));
    }

    /** 带一个参数的版本：「%s，已死。」这类要填名字/部位的红字。 */
    public static void sendRedTruthKey(Level level, Vec3 origin, double radius, String key, String arg, int ticks) {
        broadcast(level, origin, radius, S2CRedTruthPacket.ofKey(key, arg, ticks));
    }

    /** 字面文本版（调试命令用）。 */
    public static void sendRedTruthLiteral(Level level, Vec3 origin, double radius, String text, int ticks) {
        broadcast(level, origin, radius, S2CRedTruthPacket.ofLiteral(text, ticks));
    }

    private static void broadcast(Level level, Vec3 origin, double radius, Object message) {
        double r2 = radius * radius;
        for (Player player : level.players()) {
            if (player.position().distanceToSqr(origin) <= r2) {
                sendToPlayer(player, message);
            }
        }
    }

    public static void sendToServer(Object message) {
        CHANNEL.sendToServer(message);
    }

    public static void sendToPlayer(Player player, Object message) {
        if (player instanceof ServerPlayer serverPlayer) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> serverPlayer), message);
        }
    }
}
