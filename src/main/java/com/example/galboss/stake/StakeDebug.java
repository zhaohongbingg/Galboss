package com.example.galboss.stake;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 七桩的临时测量开关（不改代码就能开关）。
 *
 * <p>只要运行目录（{@code run/}）里存在 {@code stake-debug.on} 文件，就把技能/伤害/药水的每一次
 * 落地打进日志（前缀 {@code [STAKE]}）。删掉文件即静音，不用重启服务器。
 *
 * <p>为什么用文件而不是配置项：实测窗口很短（一轮战斗 60~90 秒），要能"开→打→关"，
 * 走 Forge config 得重载配置、走 JVM 参数得重启；文件探测最省事。
 */
public final class StakeDebug {

    private static final Path FLAG = Path.of("stake-debug.on");

    private static long lastCheck = Long.MIN_VALUE;

    private static boolean cached = false;

    private StakeDebug() {
    }

    /** 每秒最多探测一次文件，避免每条日志都摸一次磁盘。 */
    public static boolean enabled() {
        long now = System.currentTimeMillis();
        if (now - lastCheck < 1000L) {
            return cached;
        }
        lastCheck = now;
        cached = Files.exists(FLAG);
        return cached;
    }

    public static void log(String pattern, Object... args) {
        if (enabled()) {
            com.example.galboss.GalBoss.LOGGER.info("[STAKE] " + pattern, args);
        }
    }

    /** 给七桩打 assetId，其它实体打类型名。 */
    public static String label(Object entity) {
        if (entity instanceof com.example.galboss.entity.AbstractPurgatoryStakeEntity stake) {
            return stake.assetId();
        }
        if (entity instanceof net.minecraft.world.entity.Entity e) {
            return e.getType().toShortString();
        }
        return "?";
    }
}
