package com.example.galboss.fx;

import com.example.galboss.GalBoss;
// 注意：客户端类的引用只允许出现在 DistExecutor 的延迟 lambda 里（见下方 playInternal）。
import com.example.galboss.client.fx.PixelfxClientHooks;
import com.example.galboss.network.ModNetwork;
import com.example.galboss.network.S2CPixelfxPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * pixelfx 序列帧特效的对外入口（契约 §5）。
 *
 * <p>这一层是<b>双端可用</b>的，也是整个特效通道里唯一允许被技能代码直接调用的门面：
 * <ul>
 *   <li>服务端：把请求打包成 {@link S2CPixelfxPacket}，广播给特效位置 64 格内、能看到它的玩家；</li>
 *   <li>客户端：不绕网络，直接落到本地渲染器。</li>
 * </ul>
 *
 * <p><b>硬约束</b>：本类及其所在的 {@code fx} 包不允许出现 {@code net.minecraft.client.*}。
 * 专用服务器上没有任何客户端类，一旦引用就是 NoClassDefFoundError。客户端代码全部放在
 * {@code com.example.galboss.client.fx} 下，通过 {@link DistExecutor} 的延迟 lambda 触达 ——
 * 服务端只会看到那个 lambda 的字面量，不会去解析里面的客户端类。
 *
 * <p>特效生命周期、帧选择、朝向都由客户端根据 {@code assets/galboss/pixelfx/<slug>.json} 决定，
 * 服务端只传「在哪、哪个 slug、多大、转多少度、活多久」。
 */
public final class PixelfxFx {

    /** 服务端广播半径（格）。再远的玩家看不见，没必要占带宽。 */
    public static final double BROADCAST_RADIUS = 64.0;

    /** 未知 slug 的警告去重（服务端日志不刷屏）。 */
    private static final Set<String> WARNED_SLUGS = ConcurrentHashMap.newKeySet();

    private PixelfxFx() {
    }

    /**
     * 按元数据里的 {@code defaultScale} / 默认朝向播放一次。
     *
     * @param level 特效所在世界；null 时安全地什么都不做
     * @param pos   特效锚点：ground 是地面接触点，billboard 是画面原点（pivot = pixelfx 的 meta.origin）
     * @param slug  契约 §3 的 slug
     */
    public static void play(Level level, Vec3 pos, String slug) {
        play(level, pos, slug, 1.0f, 0.0f);
    }

    /**
     * 播放一次（one-shot 特效播完自动消失）。
     *
     * @param scale 缩放<b>倍数</b>：1.0 = 元数据里的 {@code defaultScale}；&lt;=0 也按 1.0 处理
     * @param yawDegrees ground 锚点绕 Y 轴的朝向（MC 习惯，正值俯视顺时针）；billboard 锚点忽略
     */
    public static void play(Level level, Vec3 pos, String slug, float scale, float yawDegrees) {
        playInternal(level, pos, slug, 0, scale, yawDegrees);
    }

    /**
     * 播放一个循环特效（如 {@code tohka_phase2_eclipse}），{@code lifeTicks} 后自动结束。
     *
     * <p>循环是元数据里 {@code loop} 决定的；这里只负责给「什么时候收掉」定一个上限，
     * 免得一个过场特效永远挂在场上。
     */
    public static void playLooping(Level level, Vec3 pos, String slug, int lifeTicks, float scale, float yawDegrees) {
        playInternal(level, pos, slug, Math.max(1, lifeTicks), scale, yawDegrees);
    }

    /** 循环特效的简写：只给寿命，缩放/朝向走默认。 */
    public static void playLooping(Level level, Vec3 pos, String slug, int lifeTicks) {
        playInternal(level, pos, slug, Math.max(1, lifeTicks), 1.0f, 0.0f);
    }

    /**
     * 统一入口。
     *
     * @param lifeTicks 0 表示一次性特效（播完由客户端移除）；&gt;0 表示寿命上限
     */
    private static void playInternal(Level level, Vec3 pos, String slug, int lifeTicks, float scale, float yawDegrees) {
        // 可空安全：技能里可能出现「目标已死/已离开世界」的瞬间，宁可不播也不要 NPE
        if (level == null || pos == null || slug == null || slug.isEmpty()) {
            return;
        }
        if (!PixelfxSlugs.isKnown(slug)) {
            if (WARNED_SLUGS.add(slug)) {
                GalBoss.LOGGER.warn("[pixelfx] 未知特效 slug \"{}\"，已跳过；已知 slug: {}",
                        slug, PixelfxSlugs.asText());
            }
            return;
        }

        float safeScale = scale > 0.0f ? scale : 1.0f;
        float safeYaw = Float.isFinite(yawDegrees) ? yawDegrees : 0.0f;

        if (level.isClientSide) {
            // 客户端本地播放（命令、调试、单机预测）。客户端类只在 lambda 里出现。
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> PixelfxClientHooks
                    .spawnLocal(level, pos, slug, safeScale, safeYaw, lifeTicks));
            return;
        }

        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        S2CPixelfxPacket packet = new S2CPixelfxPacket(
                slug, pos.x, pos.y, pos.z, safeScale, safeYaw, lifeTicks);
        double radiusSq = BROADCAST_RADIUS * BROADCAST_RADIUS;
        for (ServerPlayer player : serverLevel.players()) {
            if (player.distanceToSqr(pos.x, pos.y, pos.z) <= radiusSq) {
                ModNetwork.sendToPlayer(player, packet);
            }
        }
    }
}
