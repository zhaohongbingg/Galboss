package com.example.galboss.item;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 炼狱之柱的「按住右键连续突刺」—— 站着按住就戳，**不会把玩家推着走**。
 *
 * <h2>这一版和上一版的三个区别</h2>
 * <ol>
 *   <li><b>去掉自推位移</b>。上一版每 tick 往朝向写一次水平速度（抄 tprt 骑枪的冲刺），
 *       结果按住右键人自己就往前滑。现在只做判定，移动完全交给玩家自己。</li>
 *   <li><b>判定盒按柱子模型尺寸算</b>，不再拍一个 3.5×1.5 的大盒子：柱子 geo 的实测包围盒是
 *       握把前方 20.73u（1.30 格）、后方 8.68u（0.54 格）、横截面半径 2.8u（0.175 格），
 *       这里直接用这三个数当判定体，只额外加一点手感容差（见下面的常量注释）。</li>
 *   <li><b>判定是绕朝向的定向盒 + 三轴分离测试</b>（前向 / 侧向 / 竖直），所以
 *       站在柱子侧后方、或者隔着一段高度的目标不会被误伤；命中体积和看到的柱身对齐。</li>
 * </ol>
 *
 * <h2>参考</h2>
 * 判定思路沿用 <b>TanPowasd/tprt</b>（LGPL-3.0）{@code LanceChargeModifier} 的
 * 「沿朝向的矩形 + 分离轴测试 + 包围盒前表面起算」。上游是骑枪（要有速度才算冲锋），
 * 本模组按需求去掉了速度门槛、也去掉了冲刺推力，并按模型实测尺寸重算了体积；
 * 因为上游是 LGPL-3.0、本模组是 MIT，这里是按同一套算法重写的，不是逐行复制。
 *
 * <h2>几何（单位：格，全部来自 geo/item/purgatory_pillar.geo.json 的 bbox）</h2>
 * <pre>
 *   柱尖  +20.73u / 16 = 1.296 格      ← 握把到柱尖
 *   柱尾   -8.68u / 16 = 0.543 格      ← 握把到柱尾（会伸到身后一点，判定也照算）
 *   半径    2.80u / 16 = 0.175 格      ← 柱身横截面
 * </pre>
 * 容差：前向 +0.35 格（让"擦到边"也能命中，柱尖是收尖的）、横向/竖直 +0.30 格。
 */
public final class PurgatoryPillarThrust {

    // ---------------------------------------------------------------- 判定体尺寸

    /** 握把到柱尖（格）：geo +Y 20.73u。 */
    private static final double TIP_REACH = 20.73D / 16.0D;

    /** 握把到柱尾（格）：geo -Y 8.68u。 */
    private static final double BUTT_REACH = 8.68D / 16.0D;

    /** 柱身横截面半径（格）：geo 的 X/Z 半宽 2.8u。 */
    private static final double BODY_RADIUS = 2.80D / 16.0D;

    /** 前向容差：柱尖是收尖的，贴边也该算命中。 */
    private static final double FRONT_TOLERANCE = 0.35D;

    /** 横向 / 竖直容差：给"擦身而过"留一点人情，但不再像 3.5 格大盒子那样夸张。 */
    private static final double SIDE_TOLERANCE = 0.30D;

    /** 竖直半高：柱身握在胯侧，判定带覆盖「膝到大腿」。 */
    private static final double HALF_HEIGHT = BODY_RADIUS + SIDE_TOLERANCE;

    /** 手心相对玩家脚底的高度（格）。玩家的手自然下垂时约在手肘以下，取 0.95。 */
    private static final double HAND_HEIGHT = 0.95D;

    /** 手心相对身体中线的横向偏移（格）。右手在身体右侧约 0.30。 */
    private static final double HAND_OFFSET = 0.30D;

    // ---------------------------------------------------------------- 战斗节奏

    /** 同一目标两次结算之间至少隔 4 tick（= 需求里的「伤害频率 4 tick 一次」）。 */
    static final int DAMAGE_INTERVAL_TICKS = 4;

    /** 单次结算伤害，与左键一致。 */
    static final float THRUST_DAMAGE = 10.0f;

    /** 命中击退强度（原版剑是 0.4）。 */
    private static final double THRUST_KNOCKBACK = 0.5D;

    // ---------------------------------------------------------------- 状态

    /** 服务端每名「按住右键」玩家的状态。客户端不需要。 */
    private static final Map<UUID, State> ACTIVE = new ConcurrentHashMap<>();

    private PurgatoryPillarThrust() {
    }

    private static final class State {
        final InteractionHand hand;
        /** 目标 UUID → 上次结算的 gameTime。 */
        final Map<UUID, Long> lastHit = new HashMap<>(8);

        State(InteractionHand hand) {
            this.hand = hand;
        }
    }

    /** 开始按住右键（服务端）。 */
    static void begin(Player player, InteractionHand hand) {
        ACTIVE.put(player.getUUID(), new State(hand));
    }

    /** 松手 / 被打断 / 换物品。 */
    static void end(Player player) {
        ACTIVE.remove(player.getUUID());
    }

    // ---------------------------------------------------------------- 每 tick

    /**
     * 由 {@code PurgatoryPillarItem#onUseTick} 每 tick 调用（客户端与服务端都会走到）。
     *
     * <p>**这里不动玩家的速度**：右键只做判定。站着、走着、跳着都一样，
     * 只要身前判定体里有活体，就按 4 tick 的节奏结算。
     */
    static void tick(Player player) {
        if (player.level().isClientSide) {
            return;
        }
        State state = ACTIVE.get(player.getUUID());
        if (state == null) {
            begin(player, player.getUsedItemHand());
            return;
        }
        if (player.level() instanceof ServerLevel serverLevel) {
            sweep(serverLevel, player, state);
        }
    }

    // ---------------------------------------------------------------- 判定

    /** 身前定向盒内的活体：每目标最多每 {@link #DAMAGE_INTERVAL_TICKS} tick 吃一次 10 点。 */
    private static void sweep(ServerLevel level, Player player, State state) {
        long now = level.getGameTime();
        Vec3 forward = horizontalLook(player);
        Vec3 side = new Vec3(-forward.z, 0.0D, forward.x);
        Vec3 grip = gripPosition(player, forward, side);

        // 判定体：以握把为基准，向后 BUTT_REACH、向前 TIP_REACH + 容差
        Vec3 butt = grip.subtract(forward.scale(BUTT_REACH));
        Vec3 tip = grip.add(forward.scale(TIP_REACH + FRONT_TOLERANCE));
        Vec3 center = butt.add(tip).scale(0.5D);
        double halfLength = butt.distanceTo(tip) * 0.5D;
        double halfWidth = BODY_RADIUS + SIDE_TOLERANCE;

        // 粗筛：包住定向盒的轴对齐盒（竖直方向按手心上下各 HALF_HEIGHT 取）
        double pad = Math.max(halfLength, Math.max(halfWidth, HALF_HEIGHT));
        AABB broad = new AABB(
                Math.min(butt.x, tip.x) - pad, grip.y - HALF_HEIGHT - 0.5D, Math.min(butt.z, tip.z) - pad,
                Math.max(butt.x, tip.x) + pad, grip.y + HALF_HEIGHT + 0.5D, Math.max(butt.z, tip.z) + pad);

        DamageSource source = level.damageSources().playerAttack(player);
        List<LivingEntity> candidates = level.getEntitiesOfClass(LivingEntity.class, broad,
                entity -> entity != player
                        && entity.isAlive()
                        && player.canAttack(entity)
                        && player.hasLineOfSight(entity));

        for (LivingEntity target : candidates) {
            Long last = state.lastHit.get(target.getUUID());
            if (last != null && now - last < DAMAGE_INTERVAL_TICKS) {
                continue;
            }
            if (!overlapsOrientedBox(target.getBoundingBox(), center, forward, side, halfLength, halfWidth)) {
                continue;
            }

            boolean hit;
            try {
                // 4 tick 一次的节奏必须清无敌帧，否则会被原版的受伤间隔吞掉
                target.invulnerableTime = 0;
                hit = target.hurt(source, THRUST_DAMAGE);
            } finally {
                // 原版疾跑攻击会取消疾跑 + 把速度打到 0.6 倍：这里虽然不自推，也不能把玩家自己的移动刹掉
                if (player.isSprinting()) {
                    player.setSprinting(true);
                }
            }
            if (!hit) {
                continue;
            }
            state.lastHit.put(target.getUUID(), now);
            com.example.galboss.stake.StakeDebug.log("pillar hit target={} hp={} tick={}",
                    target.getName().getString(), target.getHealth(), now);
            // knockback 内部是 v/2 - normalize(ratio)*s，传 -forward 才会被推向前方
            target.knockback(THRUST_KNOCKBACK, -forward.x, -forward.z);
            level.sendParticles(ParticleTypes.CRIT,
                    target.getX(), target.getY() + target.getBbHeight() * 0.5D, target.getZ(),
                    6, 0.2D, 0.2D, 0.2D, 0.05D);
        }
    }

    /** 手心位置近似：脚底 + 0.95 高、身体右侧 0.30 格。 */
    private static Vec3 gripPosition(Player player, Vec3 forward, Vec3 side) {
        // side 已经是「右」方向（forward 顺时针 90°）：(-f.z, 0, f.x)
        return player.position()
                .add(side.scale(HAND_OFFSET))
                .add(0.0D, HAND_HEIGHT, 0.0D);
    }

    /** 只看水平朝向（yBodyRot）：柱子在胯侧横持，抬头低头不该把判定带到天上/地下。 */
    private static Vec3 horizontalLook(Player player) {
        double yaw = Math.toRadians(player.yBodyRot);
        return new Vec3(-Math.sin(yaw), 0.0D, Math.cos(yaw));
    }

    /**
     * 目标包围盒是否与「绕朝向的定向盒」相交 —— 三条轴上的分离测试：
     * 前向（柱身长度）、侧向、竖直。三条都不分离才算命中。
     *
     * <p>比「膨胀 AABB」准确得多：柱子侧后方 1 格外的目标、以及明显高/低于柱身的目标
     * 都不会再被误伤，而柱尖正前方 1.6 格内的目标能正常吃到伤害。
     */
    private static boolean overlapsOrientedBox(AABB target, Vec3 center, Vec3 forward, Vec3 side,
                                               double halfLength, double halfWidth) {
        double dx = (target.minX + target.maxX) * 0.5D - center.x;
        double dy = (target.minY + target.maxY) * 0.5D - center.y;
        double dz = (target.minZ + target.maxZ) * 0.5D - center.z;
        double halfX = target.getXsize() * 0.5D;
        double halfY = target.getYsize() * 0.5D;
        double halfZ = target.getZsize() * 0.5D;

        // 前向轴
        double along = Math.abs(dx * forward.x + dz * forward.z);
        double alongTarget = halfX * Math.abs(forward.x) + halfZ * Math.abs(forward.z) + halfY * Math.abs(forward.y);
        if (along > halfLength + alongTarget) {
            return false;
        }
        // 侧向轴
        double across = Math.abs(dx * side.x + dz * side.z);
        double acrossTarget = halfX * Math.abs(side.x) + halfZ * Math.abs(side.z);
        if (across > halfWidth + acrossTarget) {
            return false;
        }
        // 竖直轴（side × forward 方向就是世界 Y，因为 forward/side 都在水平面上）
        double vertical = Math.abs(dy);
        return vertical <= HALF_HEIGHT + halfY;
    }

    /** 供调试读的当前「按住右键」人数。 */
    public static int activeThrusts() {
        return ACTIVE.size();
    }

    /** 供 {@code onUseTick} 判断是否已在记账号。 */
    static boolean isActive(Player player, Level level) {
        return !level.isClientSide && ACTIVE.containsKey(player.getUUID());
    }
}
