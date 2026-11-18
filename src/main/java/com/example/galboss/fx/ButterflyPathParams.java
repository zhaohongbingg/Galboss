package com.example.galboss.fx;

import com.google.gson.JsonObject;
import net.minecraft.util.GsonHelper;

/**
 * 黄金蝶的飞行螺旋参数（截图里那组向量方程在 MC 里的落地版）。
 *
 * <h2>方程对照</h2>
 * 截图给的是
 * <pre>r(t) = R·cos(ωt)·i + R·sin(ωt)·j + (v·t)·k</pre>
 * 三个基向量的 MC 对应关系：
 * <table border="1">
 *   <tr><th>截图</th><th>MC 里</th><th>说明</th></tr>
 *   <tr><td>i</td><td>{@code S}（水平侧向）</td><td>{@code F × up}，与飞行方向垂直且水平</td></tr>
 *   <tr><td>j</td><td>{@code U}（竖直）</td><td>{@code S × F}，再乘 {@code verticalSquash}</td></tr>
 *   <tr><td>k（v·t）</td><td>飞行轴前进</td><td><b>由实体自己走</b>，不在偏移里重复计算</td></tr>
 * </table>
 *
 * <h2>为什么"水平方向"只能落成压扁的螺线</h2>
 * 水平方向的圆需要一个竖直的转轴，而蝴蝶是<b>朝目标水平前进</b>的 —— 前进轴一水平，
 * 垂直于它的圆就必然落在竖直平面里。所以「水平」在这里只有两种可落地的读法：
 * <ul>
 *   <li>{@code verticalSquash = 0}：纯左右摆动（扁成一条水平波浪），没有上下起伏；</li>
 *   <li>{@code verticalSquash = 0.4~0.6}：偏水平的螺线，左右摆幅大于上下摆幅（默认 0.55）。</li>
 * </ul>
 *
 * <h2>ω 怎么来的</h2>
 * 截图给的是 {@code ω = -0.7 rad/s}（周期约 9 秒），对 3.5 秒寿命的蝴蝶来说慢了一个数量级，
 * 而且它是「从固定原点出发」的绝对路径，没有追踪。这里改成用<b>螺距</b>反推：
 * {@code ω = 2π · 实际速度 / pitch}（弧度 / tick），实际速度每 tick 从 {@code deltaMovement} 取，
 * 所以同一批里速度不同的蝴蝶会各自转不同的快慢。
 *
 * <h2>调参</h2>
 * 全部字段可由 {@code config/galboss/fx/butterfly_path.json} 覆盖（只写要改的字段即可），
 * 改完 {@code /galboss fx reload} 立刻生效，不用重编译：
 * <pre>{ "kind": "path", "radius": 1.4, "pitch": 5.0, "verticalSquash": 0.0 }</pre>
 *
 * <p>这个类是服务端可用的（不引用任何客户端类）。
 */
public record ButterflyPathParams(
        /** 螺旋半径（格）。0 = 不摆，直线飞。 */
        double radius,
        /** 螺距（格 / 圈）：转一圈前进多少格。越小转得越急。 */
        double pitch,
        /** 竖直摆幅系数：0 = 纯水平左右摆，1 = 完整圆。 */
        double verticalSquash,
        /** 旋向：+1 / -1。截图的 ω 是负的，所以默认 -1。 */
        double spin,
        /** 每几 tick 落一个尾迹点。越小越连续，越大越像虚线。 */
        int spawnIntervalTicks,
        /**
         * 飞行速度（格 / tick）。
         *
         * <p>它同时决定两件事：追不追得上人，以及<b>螺旋能不能转圆</b>。
         * 截图的 {@code ω = -0.7 rad/s}（9 秒一圈）对应的是 0.9 格/秒那种慢速，
         * 而 MC 里 0.62 格/tick = 12.4 格/秒，快了一个数量级 —— 所以那个比例必须降速才复刻得出来。
         */
        double speed) {

    /** 定义名：{@code config/galboss/fx/butterfly_path.json}。 */
    public static final String DEFINITION_ID = "butterfly_path";

    /** 名义飞行速度（格 / tick）。{@code ButterflyMissileEntity.SPEED} 直接引用它，避免两处漂移。 */
    public static final double NOMINAL_SPEED = 0.62;

    /**
     * 缺省值。半径 1.3 格：蝶身 0.7 格，摆出去能看出螺旋，又不至于飞成一个大圈。
     *
     * <p>螺距 8.0 是<b>算出来的</b>，不是拍脑袋：速度 0.62 格/tick、落点间隔 2 tick 时，
     * 每圈 12.9 tick → 每圈 6.4 个采样点，刚好在「能读成圆」的门槛（6）之上。
     * 想转得更急就得同时把 {@code spawnIntervalTicks} 调到 1，否则会变成折线。
     */
    public static final ButterflyPathParams DEFAULT =
            new ButterflyPathParams(1.30, 8.00, 0.55, -1.0, 2, NOMINAL_SPEED);

    /**
     * 角速度（弧度 / tick）：转一圈走 {@code pitch} 格，每 tick 走 {@code speed} 格。
     *
     * @param speed 当前实际速度（格 / tick），从 {@code deltaMovement.length()} 取
     */
    public double omegaRadPerTick(double speed) {
        return 2.0 * Math.PI * Math.max(speed, 1.0E-3) / Math.max(this.pitch, 1.0E-2);
    }

    /** 一圈要多少 tick。 */
    public double ticksPerTurn(double speed) {
        return 2.0 * Math.PI / Math.max(omegaRadPerTick(speed), 1.0E-6);
    }

    /** 每圈采到几个尾迹点：低于 6 就会看出「折线」而不是螺旋。 */
    public double samplesPerTurn(double speed) {
        return ticksPerTurn(speed) / Math.max(this.spawnIntervalTicks, 1);
    }

    /** 是否启用螺旋（半径为 0 时退化成直线，走原逻辑）。 */
    public boolean enabled() {
        return this.radius > 1.0E-4;
    }

    /**
     * 从 {@link EffectLibrary} 读参数；没有定义 / 字段缺失时逐项回落到 {@link #DEFAULT}。
     *
     * <p>逐字段回退（而不是整份丢弃）是故意的：这样 {@code config} 里只写
     * {@code {"verticalSquash": 0}} 也能生效。
     */
    public static ButterflyPathParams load() {
        JsonObject json = EffectLibrary.definition(DEFINITION_ID).orElse(null);
        if (json == null) {
            return DEFAULT;
        }
        try {
            return new ButterflyPathParams(
                    clamp(GsonHelper.getAsDouble(json, "radius", DEFAULT.radius), 0.0, 8.0),
                    clamp(GsonHelper.getAsDouble(json, "pitch", DEFAULT.pitch), 0.25, 64.0),
                    clamp(GsonHelper.getAsDouble(json, "verticalSquash", DEFAULT.verticalSquash), 0.0, 1.0),
                    GsonHelper.getAsDouble(json, "spin", DEFAULT.spin) < 0.0 ? -1.0 : 1.0,
                    (int) clamp(GsonHelper.getAsInt(json, "spawnIntervalTicks", DEFAULT.spawnIntervalTicks), 1, 20),
                    clamp(GsonHelper.getAsDouble(json, "speed", DEFAULT.speed), 0.05, 4.0));
        } catch (Exception e) {
            com.example.galboss.GalBoss.LOGGER.warn(
                    "[fx] {} 参数解析失败，回落到默认值: {}", DEFINITION_ID, e.toString());
            return DEFAULT;
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
