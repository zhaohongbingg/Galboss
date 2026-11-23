package com.example.galboss.stake;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 七桩的临时测量开关（不改代码就能开关）。
 *
 * <p>把 {@code stake-debug.on} 放到下面任一位置即开启，删掉即静音（每秒探测一次，不用重启）：
 * <ul>
 *   <li>JVM 工作目录（{@code user.dir}）—— runServer 通常是项目根或 {@code run/}，两者都试</li>
 *   <li>{@code <user.dir>/run/stake-debug.on}</li>
 *   <li>系统属性 {@code -Dgalboss.stakeDebug=true} 强制开启</li>
 * </ul>
 *
 * <p>为什么搞这么多候选：Gradle 的 {@code runServer} 只把 ModLauncher 的 gameDir 设成 {@code run/}，
 * JVM 的 {@code user.dir} 却可能是项目根 —— 只摸一处会让开关"看起来没生效"（踩过两次）。
 * 首次判定时会把真实 CWD 打一行日志，省得以后再猜。
 */
public final class StakeDebug {

    private static final String[] CANDIDATES = {
            "stake-debug.on",
            "run/stake-debug.on",
            "../stake-debug.on"
    };

    private static long lastCheck = Long.MIN_VALUE;

    private static boolean cached = false;

    private static boolean announced = false;

    private StakeDebug() {
    }

    /** 每秒最多探测一次磁盘，避免每条日志都摸一次文件系统。 */
    public static boolean enabled() {
        if (Boolean.getBoolean("galboss.stakeDebug")) {
            return true;
        }
        long now = System.currentTimeMillis();
        if (now - lastCheck < 1000L) {
            return cached;
        }
        lastCheck = now;
        cached = false;
        for (String candidate : CANDIDATES) {
            if (Files.exists(Path.of(candidate))) {
                cached = true;
                if (!announced) {
                    announced = true;
                    System.out.println("[STAKE] 测量开关开启，命中文件 " + candidate);
                }
                break;
            }
        }
        if (!announced) {
            announced = true;
            System.out.println("[STAKE] 测量关闭（cwd=" + System.getProperty("user.dir")
                    + ", 已探测 " + String.join(" / ", CANDIDATES) + "）");
        }
        return cached;
    }

    /**
     * 输出走 {@code System.out} 而不是 LOGGER：实测 LOGGER 这条通道在 runServer 下
     * 会被日志配置吞掉（Beatrice 的 logger 能出、GalBoss.LOGGER 的 [STAKE] 一条都出不来），
     * 而 System.out 一定会落进 .mcdev / latest.log。测量用途，不追求结构化。
     *
     * <p>开关是 JVM 参数 {@code -Dgalboss.stakeDebug=true}，默认关闭（交付时不刷屏）。
     * 实测数据见 {@code docs/STAKE-ABILITIES-TEST.md}。
     */
    public static void log(String pattern, Object... args) {
        if (!Boolean.getBoolean("galboss.stakeDebug")) {
            return;
        }
        String out = new org.apache.logging.log4j.message.ParameterizedMessage(pattern, args).getFormattedMessage();
        System.out.println("[STAKE] " + out);
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
