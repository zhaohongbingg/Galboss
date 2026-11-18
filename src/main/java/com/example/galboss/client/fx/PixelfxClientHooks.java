package com.example.galboss.client.fx;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 客户端侧的特效入口。
 *
 * <p>所有从 common 代码（网络包、{@code fx.PixelfxFx}）过来的调用都收敛到这里，
 * 好处是 common 侧的类一个 {@code net.minecraft.client.*} 都不用碰 —— 这个类在专用服务器上
 * 根本不会被加载。
 */
public final class PixelfxClientHooks {

    private PixelfxClientHooks() {
    }

    /**
     * 本地直接播放（单机 / 客户端调用 {@code PixelfxFx.play} 时走这里）。
     *
     * @param lifeTicks 0 = 一次性；>0 = 寿命上限
     */
    public static void spawnLocal(Level level, Vec3 pos, String slug, float scale, float yaw, int lifeTicks) {
        // level 参数只用来判空：真正的时钟取客户端世界，避免拿到服务/客户端两份 gameTime
        if (level == null || Minecraft.getInstance().level == null) {
            return;
        }
        PixelfxRenderer.spawn(pos, slug, scale, yaw, lifeTicks);
    }

    /** S2C 包落地点（{@code consumerMainThread}，已经在客户端主线程上）。 */
    public static void onPacket(String slug, double x, double y, double z,
                                float scale, float yaw, int lifeTicks) {
        if (Minecraft.getInstance().level == null) {
            return;
        }
        PixelfxRenderer.spawn(new Vec3(x, y, z), slug, scale, yaw, lifeTicks);
    }
}
