package com.example.galboss.entity;

import com.example.galboss.boss.DemeritLedger;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ② 只須川 レイ —— 高机动 / 追击。
 *
 * 三人里最难甩掉的一个，核心是「不要让她贴住你，也不要试图跑图」：
 *  - 被动「风纪执行」：目标超过 12 格获得 Speed II，超过 20 格直接瞬移到目标身边
 *  - 「肃清」：蓄力 0.8 秒后高速冲刺，命中 14 伤害 + 强击退，落空则自己加速
 *  - 「连罚」：8 / 8 / 10 / 16 四连段，段间留出可躲的间隔
 *  - 「追击」：玩家用珍珠 / 鞘翅 / 位移拉开 10 格以上时瞬移到侧后方打一套
 *  - P2「风纪最终执行」：攻击 +30%、移速 +20%、攻速 +20%，追击 CD 减半
 */
public class TadasugawaReiEntity extends AbstractFSBossEntity {

    private static final String ID = "tadasugawa_rei";

    // 被动：风纪执行
    private static final double ENFORCE_SPEED_DISTANCE = 12.0;
    private static final double ENFORCE_TELEPORT_DISTANCE = 20.0;
    private static final int ENFORCE_TELEPORT_CD = 5 * 20;

    // 肃清
    private static final double PURGE_CD = 12.0;
    private static final int PURGE_CHARGE_TICKS = 16;   // 0.8 秒
    private static final int PURGE_DASH_TICKS = 18;     // 位移上限，够覆盖 3~24 格的任何距离
    private static final float PURGE_DAMAGE = 14.0f;
    private static final double PURGE_SPEED = 1.5;

    // 连罚
    private static final double COMBO_CD = 22.0;
    private static final float[] COMBO_DAMAGE = {8.0f, 8.0f, 10.0f, 16.0f};
    private static final int COMBO_INTERVAL_MIN = 5;    // 0.25 秒
    private static final int COMBO_INTERVAL_MAX = 7;    // 0.35 秒

    // 追击
    private static final double PURSUIT_CD = 30.0;
    private static final double PURSUIT_CD_PHASE2 = 15.0;
    private static final double PURSUIT_TRIGGER_DISTANCE = 10.0;
    private static final double BLINK_DETECT_DISTANCE = 6.0;  // 单 tick 位移超过这个值视为位移技能
    private static final float PURSUIT_DAMAGE = 12.0f;

    // ---------------------------------------------------------------- 制圧射撃
    private static final double SUPPRESS_CD = 8.0;
    private static final double SUPPRESS_RANGE = 24.0;
    private static final int SUPPRESS_SHOTS = 3;
    private static final int SUPPRESS_SHOTS_PHASE2 = 5;
    private static final float SUPPRESS_DAMAGE = 7.0f;
    private static final float SUPPRESS_DAMAGE_PHASE2 = 6.0f;
    private static final int SUPPRESS_INTERVAL = 5;         // 每发间隔 5 tick = 0.25 秒
    private static final int SUPPRESS_BACKSTEP_TICKS = 10;  // 开火前后撤
    private static final int SUPPRESS_AIM_TICKS = 8;
    private static final double BACKSTEP_SPEED = 0.32;

    // ---------------------------------------------------------------- 警告射击
    private static final double WARNING_CD = 18.0;
    private static final double WARNING_RADIUS = 3.0;
    private static final int WARNING_DELAY = 24;            // 1.2 秒反应窗口
    private static final float WARNING_DAMAGE = 18.0f;

    // ---------------------------------------------------------------- 狙击指令
    private static final double SNIPE_CD = 35.0;
    private static final int SNIPE_CHARGE = 30;             // 1.5 秒
    private static final int SNIPE_CHARGE_PHASE2 = 22;      // 1.1 秒
    private static final float SNIPE_DAMAGE = 35.0f;
    private static final double SNIPE_SPEED = 4.0;
    private static final double SNIPE_KNOCKBACK = 2.6;

    // -------------------------------------------------------------- 近距离射击
    private static final double POINT_BLANK_CD = 12.0;
    private static final double POINT_BLANK_RANGE = 4.0;
    private static final int POINT_BLANK_SHOTS = 2;
    private static final int POINT_BLANK_SHOTS_PHASE2 = 3;
    private static final float POINT_BLANK_DAMAGE = 9.0f;
    private static final int POINT_BLANK_INTERVAL = 4;
    private static final int POINT_BLANK_AIM_TICKS = 5;
    private static final int POINT_BLANK_RETREAT_TICKS = 8;

    // ---------------------------------------------------------------- 交叉火力
    private static final double CROSSFIRE_CD = 45.0;
    private static final double CROSSFIRE_RADIUS = 48.0;

    // -------------------------------------------------------------- 弹道参数
    private static final double BULLET_SPEED_MIN = 2.5;
    private static final double BULLET_SPEED_MAX = 3.0;
    private static final double BULLET_SUBSTEP = 0.4;       // 一 tick 内分小步，避免跨过目标
    private static final double BULLET_HOMING = 0.05;       // 每 tick 的追踪修正比例（轻微）
    private static final int BULLET_LIFE = 40;

    /** 枪技模式，共用一个 goal。 */
    private static final int GUN_POINT_BLANK = 0;
    private static final int GUN_SUPPRESS = 1;
    private static final int GUN_SNIPE = 2;

    private static final DustParticleOptions LASER_DUST =
            new DustParticleOptions(new Vector3f(1.0f, 0.12f, 0.12f), 0.9f);
    private static final DustParticleOptions WARNING_DUST =
            new DustParticleOptions(new Vector3f(1.0f, 0.75f, 0.1f), 1.1f);

    private static final double PHASE2_HEALTH = 0.5;

    /** 玩家上一 tick 的位置，用来识别珍珠 / 鞘翅 / 传送。 */
    private final Map<UUID, Vec3> lastPositions = new HashMap<>();

    /** 飞行中的子弹。 */
    private final List<Bullet> bullets = new ArrayList<>();

    private int enforceTeleportCooldown = 0;
    private int pursuitCooldown = 0;
    private int purgeCd = 0;
    private int comboCd = 0;
    private int suppressCd = 0;
    private int warningCd = 0;
    private int snipeCd = 0;
    private int pointBlankCd = 0;
    private int crossfireCd = 0;
    private boolean pursuitReady = false;
    private boolean phase2 = false;

    /** 警告射击落点的地面位置；非 null 表示警示圈正在倒计时。 */
    private Vec3 warningMarker = null;
    private int warningTicks = 0;

    public TadasugawaReiEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        // 基础血量；实际按参战人数 ×1 / 1.5 / 2 / 2.5（见 AbstractFSBossEntity#applyHealthScaling）
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 500.0)
                .add(Attributes.ATTACK_DAMAGE, 12.0)
                .add(Attributes.MOVEMENT_SPEED, 0.32)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.9)
                .add(Attributes.ARMOR, 10.0)
                .add(Attributes.FOLLOW_RANGE, 64.0);
    }

    @Override
    public FSRole getRole() {
        return FSRole.REI;
    }

    @Override
    protected String getEntityId() {
        return ID;
    }

    @Override
    protected BossEvent.BossBarColor getBossBarColor() {
        return BossEvent.BossBarColor.RED;
    }

    @Override
    protected double getMeleeReach() {
        return 3.0;
    }

    @Override
    protected double getAttackMultiplier() {
        return phase2 ? 1.30 : 1.0;
    }

    @Override
    protected double getSpeedMultiplier() {
        return phase2 ? 1.20 : 1.0;
    }

    @Override
    protected double getAttackSpeedMultiplier() {
        return phase2 ? 1.20 : 1.0;
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        // 三个枪技由 GunplayGoal 统一调度，排在近战技能前面：
        // 她的节奏是「近战压制 → 拉开 → 开枪 → 重新突进」
        this.goalSelector.addGoal(1, new GunplayGoal());
        this.goalSelector.addGoal(2, new PursuitGoal());
        this.goalSelector.addGoal(3, new PurgeGoal());
        this.goalSelector.addGoal(4, new ComboGoal());
    }

    // ------------------------------------------------------------------ 主循环

    @Override
    protected void tickBoss() {
        if (this.level().isClientSide) {
            return;
        }
        if (enforceTeleportCooldown > 0) {
            enforceTeleportCooldown--;
        }
        if (pursuitCooldown > 0) {
            pursuitCooldown--;
        }
        if (suppressCd > 0) suppressCd--;
        if (warningCd > 0) warningCd--;
        if (snipeCd > 0) snipeCd--;
        if (pointBlankCd > 0) pointBlankCd--;
        if (crossfireCd > 0) crossfireCd--;

        checkPhase2();
        tickEnforcement();
        tickPursuitDetection();
        tickBullets();
        tickWarningShot();
        tickCrossfire();
    }

    private void checkPhase2() {
        if (!phase2 && this.getHealth() <= this.getMaxHealth() * (float) PHASE2_HEALTH) {
            phase2 = true;
            playAnim("phase");
            broadcastNearby(getDialogueKey() + "phase2", "§c");
            this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                    SoundEvents.WARDEN_ROAR, SoundSource.HOSTILE, 1.6f, 1.2f);
        }
    }

    /** 被动：风纪执行 —— 拉开距离就加速，拉太远就直接贴脸。 */
    private void tickEnforcement() {
        LivingEntity target = this.getTarget();
        if (target == null || !isBattleActive()) {
            return;
        }
        double distance = this.distanceTo(target);

        if (distance > ENFORCE_SPEED_DISTANCE) {
            this.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 40, 1, false, false, true));
        }

        if (distance > ENFORCE_TELEPORT_DISTANCE && enforceTeleportCooldown <= 0) {
            enforceTeleportCooldown = ENFORCE_TELEPORT_CD;
            Vec3 behind = target.position().add(behindOffset(target, 2.5, 0.0));
            if (this.randomTeleport(behind.x, behind.y, behind.z, true)) {
                // 瞬移本身是一瞬间的事，配上冲刺动画才不会显得是凭空冒出来
                playAnim("dash");
                this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                        SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.2f, 1.0f);
            }
        }
    }

    private Vec3 behindOffset(LivingEntity target, double back, double side) {
        Vec3 look = target.getLookAngle();
        Vec3 back2 = new Vec3(-look.x, 0, -look.z).normalize().scale(back);
        Vec3 side2 = new Vec3(-look.z, 0, look.x).normalize().scale(side);
        return back2.add(side2);
    }

    /** 识别玩家用位移跑出 10 格以上，准备触发追击。 */
    private void tickPursuitDetection() {
        if ((pursuitCooldown > 0 && !pursuitReady) || !isBattleActive()) {
            return;
        }
        for (Player player : nearbyPlayers(ALLY_RADIUS)) {
            Vec3 previous = lastPositions.put(player.getUUID(), player.position());
            if (previous == null) {
                continue;
            }
            double moved = previous.distanceTo(player.position());
            // 珍珠 / 传送 / 冲刺是一 tick 内的瞬移；鞘翅则是持续高速，单独判一次
            boolean blinked = moved >= BLINK_DETECT_DISTANCE;
            boolean flying = player.isFallFlying() && moved > 1.0;
            if (!blinked && !flying) {
                continue;
            }
            double distance = this.distanceTo(player);
            if (distance > PURSUIT_TRIGGER_DISTANCE) {
                this.setTarget(player);
                pursuitReady = true;
                return;
            }
        }
    }

    // -------------------------------------------------------------------- 技能

    /** 追击：瞬移到玩家侧后方并打一套。 */
    private class PursuitGoal extends Goal {

        private int delayTicks = -1;

        private PursuitGoal() {
            this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            return battleActive && pursuitReady && pursuitCooldown <= 0 && getTarget() != null && getTarget().isAlive();
        }

        @Override
        public boolean canContinueToUse() {
            return this.delayTicks > 0;
        }

        @Override
        public void start() {
            this.delayTicks = 4;
            getNavigation().stop();
        }

        @Override
        public void tick() {
            LivingEntity target = getTarget();
            if (target == null) {
                this.delayTicks = 0;
                return;
            }
            getLookControl().setLookAt(target, 30.0f, 30.0f);
            if (--this.delayTicks > 0) {
                return;
            }
            Vec3 spot = target.position().add(behindOffset(target, 2.0, 1.6));
            if (randomTeleport(spot.x, spot.y, spot.z, true)) {
                level().playSound(null, getX(), getY(), getZ(),
                        SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.4f, 0.8f);
                target.hurt(damageSources().mobAttack(TadasugawaReiEntity.this), PURSUIT_DAMAGE);
                Vec3 push = target.position().subtract(position()).normalize().scale(2.4);
                target.push(push.x, 0.45, push.z);
                addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 4 * 20, 1, false, false, true));
                playAnim("dash");
                broadcastNearby(getDialogueKey() + "pursuit", "§c");
            }
        }

        @Override
        public void stop() {
            this.delayTicks = -1;
            pursuitReady = false;
            pursuitCooldown = cd(phase2 ? PURSUIT_CD_PHASE2 : PURSUIT_CD);
            getNavigation().stop();
        }
    }

    /** 肃清：蓄力后高速冲刺。 */
    private class PurgeGoal extends Goal {

        private int chargeTicks = -1;
        private int dashTicks;
        private Vec3 dashDir = Vec3.ZERO;
        private boolean dashing;

        private PurgeGoal() {
            this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            if (!battleActive || purgeCd > 0) {
                return false;
            }
            LivingEntity target = getTarget();
            if (target == null || !target.isAlive()) {
                return false;
            }
            double distance = distanceTo(target);
            return distance > 3.0 && distance < 24.0;
        }

        @Override
        public boolean canContinueToUse() {
            return this.chargeTicks > 0 || this.dashing;
        }

        @Override
        public void start() {
            this.chargeTicks = PURGE_CHARGE_TICKS;
            this.dashing = false;
            getNavigation().stop();
            playAnim("charge");
            broadcastNearby(getDialogueKey() + "purge", "§c");
        }

        @Override
        public void tick() {
            LivingEntity target = getTarget();
            if (target == null) {
                this.chargeTicks = 0;
                this.dashing = false;
                return;
            }

            if (!this.dashing) {
                getLookControl().setLookAt(target, 60.0f, 60.0f);
                getNavigation().stop();
                if (--this.chargeTicks > 0) {
                    return;
                }
                Vec3 direction = target.position().subtract(position());
                this.dashDir = new Vec3(direction.x, 0, direction.z).normalize();
                this.dashing = true;
                this.dashTicks = PURGE_DASH_TICKS;
                // 位移就是从这一 tick 开始的，冲刺动画也必须在这一 tick 才触发
                playAnim("dash");
                level().playSound(null, getX(), getY(), getZ(),
                        SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 1.2f, 1.5f);
                return;
            }

            if (--this.dashTicks <= 0) {
                finish(false);
                return;
            }

            setDeltaMovement(this.dashDir.x * PURGE_SPEED, getDeltaMovement().y, this.dashDir.z * PURGE_SPEED);

            if (distanceTo(target) <= 2.2) {
                target.hurt(damageSources().mobAttack(TadasugawaReiEntity.this), PURGE_DAMAGE);
                Vec3 push = this.dashDir.scale(2.6);
                target.push(push.x, 0.5, push.z);
                target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 2 * 20, 1, false, true, true));
                level().playSound(null, getX(), getY(), getZ(),
                        SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.HOSTILE, 1.4f, 0.9f);
                finish(true);
            }
        }

        private void finish(boolean hit) {
            this.chargeTicks = 0;
            this.dashing = false;
            if (!hit) {
                // 落空反而更快，鼓励玩家躲而不是站桩
                addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 2 * 20, 2, false, false, true));
            }
        }

        @Override
        public void stop() {
            this.chargeTicks = -1;
            this.dashing = false;
            purgeCd = cd(PURGE_CD);
        }
    }

    /** 连罚：四连段，每段间隔 0.25~0.35 秒。 */
    private class ComboGoal extends Goal {

        private int stage;
        private int waitTicks;

        private ComboGoal() {
            this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            if (!battleActive || comboCd > 0) {
                return false;
            }
            LivingEntity target = getTarget();
            return target != null && target.isAlive() && distanceToSqr(target) < 16.0;
        }

        @Override
        public boolean canContinueToUse() {
            return this.stage < COMBO_DAMAGE.length && getTarget() != null && getTarget().isAlive();
        }

        @Override
        public void start() {
            this.stage = 0;
            this.waitTicks = 0;
            getNavigation().stop();
            playAnim("combo");
            broadcastNearby(getDialogueKey() + "combo", "§c");
        }

        @Override
        public void tick() {
            LivingEntity target = getTarget();
            if (target == null) {
                this.stage = COMBO_DAMAGE.length;
                return;
            }
            getLookControl().setLookAt(target, 30.0f, 30.0f);

            if (this.waitTicks > 0) {
                this.waitTicks--;
                return;
            }
            if (distanceToSqr(target) > 16.0) {
                this.stage = COMBO_DAMAGE.length;
                return;
            }

            target.hurt(damageSources().mobAttack(TadasugawaReiEntity.this), COMBO_DAMAGE[this.stage]);
            level().playSound(null, getX(), getY(), getZ(),
                    SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.HOSTILE, 1.0f, 1.2f);
            this.stage++;
            this.waitTicks = COMBO_INTERVAL_MIN
                    + TadasugawaReiEntity.this.random.nextInt(COMBO_INTERVAL_MAX - COMBO_INTERVAL_MIN + 1);
        }

        @Override
        public void stop() {
            this.stage = COMBO_DAMAGE.length;
            comboCd = cd(COMBO_CD);
            getNavigation().stop();
        }
    }

    // -------------------------------------------------------------------- 枪械
    //
    // 礼的枪不是「远程平A」，而是把战斗节奏拉成
    // 近战压制 → 后撤布位 → 射击 → 重新突进。
    //
    // 子弹不做成实体，而是服务端自己推进的虚拟弹道：每 tick 分小步前进并做命中判定，
    // 视觉上用粒子拖出曳光。这样不用新增实体 / 模型 / 贴图，命中判定也完全由服务端裁定。

    /** 一发飞行中的子弹。 */
    private static final class Bullet {

        Vec3 pos;
        Vec3 vel;
        int life;
        LivingEntity lockOn;      // 轻微追踪的目标；null = 纯直线（狙击弹不追踪）
        float damage;
        boolean pierce;           // 命中后不消失
        boolean slowness;         // 命中挂缓慢 I 1 秒
        boolean weakness;         // 命中挂虚弱 I 3 秒
        double knockback;         // 命中后推开的力度
        boolean armorPiercing;    // 用魔法伤害源，穿盾
        final List<UUID> alreadyHit = new ArrayList<>();   // 穿透弹不要反复命中同一个人
    }

    /** 从枪口朝瞄准点打出一发子弹。 */
    private void fireBullet(Vec3 aimPoint, LivingEntity lockOn, double speed, float damage,
                            boolean pierce, boolean slowness, boolean weakness, double knockback,
                            boolean armorPiercing) {
        Vec3 from = this.getEyePosition().add(this.getLookAngle().scale(0.6));
        Vec3 direction = aimPoint.subtract(from);
        if (direction.lengthSqr() < 1.0E-6) {
            return;
        }
        Bullet bullet = new Bullet();
        bullet.pos = from;
        bullet.vel = direction.normalize().scale(speed);
        bullet.life = BULLET_LIFE;
        bullet.lockOn = lockOn;
        bullet.damage = damage;
        bullet.pierce = pierce;
        bullet.slowness = slowness;
        bullet.weakness = weakness;
        bullet.knockback = knockback;
        bullet.armorPiercing = armorPiercing;
        this.bullets.add(bullet);

        muzzleFx(from);
        this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                SoundEvents.CROSSBOW_SHOOT, SoundSource.HOSTILE, 1.6f, 0.9f);
    }

    private void tickBullets() {
        if (this.bullets.isEmpty()) {
            return;
        }
        Iterator<Bullet> it = this.bullets.iterator();
        while (it.hasNext()) {
            Bullet bullet = it.next();
            if (--bullet.life <= 0 || !advanceBullet(bullet)) {
                it.remove();
            }
        }
    }

    /** 推进一发子弹，返回 false 表示它该消失了。 */
    private boolean advanceBullet(Bullet bullet) {
        // 轻微追踪：每 tick 只朝目标方向偏一点点，玩家横向走位仍然甩得掉
        if (bullet.lockOn != null && bullet.lockOn.isAlive()) {
            Vec3 want = bullet.lockOn.getEyePosition().subtract(bullet.pos);
            if (want.lengthSqr() > 1.0E-6) {
                bullet.vel = bullet.vel.normalize().scale(1.0 - BULLET_HOMING)
                        .add(want.normalize().scale(BULLET_HOMING))
                        .normalize()
                        .scale(bullet.vel.length());
            }
        }

        int steps = Math.max(1, (int) Math.ceil(bullet.vel.length() / BULLET_SUBSTEP));
        Vec3 slice = bullet.vel.scale(1.0 / steps);
        for (int i = 0; i < steps; i++) {
            Vec3 to = bullet.pos.add(slice);
            Player victim = firstPlayerHit(bullet, bullet.pos, to);
            if (victim != null) {
                onBulletHit(bullet, victim);
                if (!bullet.pierce) {
                    return false;
                }
            }
            if (this.level().clip(new ClipContext(bullet.pos, to,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this)).getType() != HitResult.Type.MISS) {
                impactFx(to);
                return false;
            }
            bullet.pos = to;
        }
        tracerFx(bullet.pos);
        return true;
    }

    /** 这一段位移上最先命中的、且还没被这发子弹打过的玩家。 */
    private Player firstPlayerHit(Bullet bullet, Vec3 from, Vec3 to) {
        Player best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Player player : this.level().getEntitiesOfClass(Player.class, new AABB(from, to).inflate(0.35))) {
            if (!player.isAlive() || player.isSpectator() || bullet.alreadyHit.contains(player.getUUID())) {
                continue;
            }
            if (player.getBoundingBox().inflate(0.25).clip(from, to).isEmpty()) {
                continue;
            }
            double distance = from.distanceToSqr(player.position());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = player;
            }
        }
        return best;
    }

    private void onBulletHit(Bullet bullet, Player victim) {
        bullet.alreadyHit.add(victim.getUUID());
        // 狙击弹走魔法伤害源：原版 bypasses_shield 包含 bypasses_armor，
        // 而 bypasses_armor 里有 magic，所以盾牌和护甲都挡不住
        DamageSource source = bullet.armorPiercing
                ? this.damageSources().magic()
                : this.damageSources().mobAttack(this);
        // 记过增伤平时由 DemeritLedger 的 LivingHurtEvent 统一处理，但 magic() 没有实体可追溯
        // （getSource().getEntity() 是 null），事件认不出这发是礼打的，所以这一路要自己乘一遍。
        float damage = bullet.armorPiercing
                ? DemeritLedger.scaleDamage(victim, bullet.damage)
                : bullet.damage;
        victim.hurt(source, damage);

        if (bullet.slowness) {
            victim.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 20, 0, false, true, true));
        }
        if (bullet.weakness) {
            victim.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 3 * 20, 0, false, true, true));
        }
        if (bullet.knockback > 0.0) {
            Vec3 push = bullet.vel.normalize().scale(bullet.knockback);
            victim.push(push.x, 0.35, push.z);
        }
        this.level().playSound(null, victim.getX(), victim.getY(), victim.getZ(),
                SoundEvents.ARROW_HIT_PLAYER, SoundSource.HOSTILE, 1.2f, 1.4f);
        if (this.level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.CRIT,
                    victim.getX(), victim.getY() + 1.0, victim.getZ(), 6, 0.25, 0.25, 0.25, 0.12);
        }
    }

    // ------------------------------------------------------------------ 枪械特效

    private void muzzleFx(Vec3 at) {
        if (this.level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.FLAME, at.x, at.y, at.z, 4, 0.06, 0.06, 0.06, 0.0);
            serverLevel.sendParticles(ParticleTypes.SMOKE, at.x, at.y, at.z, 3, 0.08, 0.08, 0.08, 0.01);
        }
    }

    private void tracerFx(Vec3 at) {
        if (this.level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.END_ROD, at.x, at.y, at.z, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    private void impactFx(Vec3 at) {
        if (this.level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.CRIT, at.x, at.y, at.z, 8, 0.15, 0.15, 0.15, 0.2);
            serverLevel.sendParticles(ParticleTypes.SMOKE, at.x, at.y, at.z, 4, 0.1, 0.1, 0.1, 0.02);
        }
    }

    /** 狙击指令的红色瞄准线：沿视线铺一串红色尘粒。 */
    private void laserFx(Vec3 from, Vec3 to) {
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        int points = Math.max(2, (int) Math.round(from.distanceTo(to) / 0.4));
        Vec3 step = to.subtract(from).scale(1.0 / points);
        Vec3 point = from;
        for (int i = 0; i <= points; i++) {
            serverLevel.sendParticles(LASER_DUST, point.x, point.y, point.z, 1, 0.0, 0.0, 0.0, 0.0);
            point = point.add(step);
        }
    }

    /** 警告射击落点的地面警示圈。 */
    private void warningCircleFx(Vec3 center) {
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        int points = 24;
        for (int i = 0; i < points; i++) {
            double angle = Math.PI * 2.0 * i / points;
            serverLevel.sendParticles(WARNING_DUST,
                    center.x + Math.cos(angle) * WARNING_RADIUS,
                    center.y + 0.15,
                    center.z + Math.sin(angle) * WARNING_RADIUS,
                    1, 0.0, 0.0, 0.0, 0.0);
        }
        serverLevel.sendParticles(ParticleTypes.LAVA, center.x, center.y + 0.2, center.z, 2, 0.4, 0.1, 0.4, 0.0);
    }

    // ---------------------------------------------------------------- 警告射击

    /** 警告射击：先朝玩家脚边开一枪标出落点，1.2 秒后同一位置落下第二枪。 */
    private void tickWarningShot() {
        if (this.warningMarker != null) {
            warningCircleFx(this.warningMarker);
            if (--this.warningTicks <= 0) {
                detonateWarning();
            }
            return;
        }
        if (warningCd > 0 || !isBattleActive()) {
            return;
        }
        LivingEntity target = this.getTarget();
        if (target == null || !target.isAlive()) {
            return;
        }
        warningCd = cd(WARNING_CD);
        this.warningMarker = target.position();
        this.warningTicks = WARNING_DELAY;

        muzzleFx(this.getEyePosition().add(this.getLookAngle().scale(0.6)));
        this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                SoundEvents.CROSSBOW_SHOOT, SoundSource.HOSTILE, 1.4f, 1.1f);
        broadcastNearby(getDialogueKey() + "warning_shot", "§e");
    }

    private void detonateWarning() {
        Vec3 center = this.warningMarker;
        this.warningMarker = null;
        if (center == null) {
            return;
        }
        for (Player player : nearbyPlayers(WARNING_RADIUS + 1.0)) {
            // 及时离开圈里的人完全不受影响，这就是那 1.2 秒窗口的意义
            if (player.position().distanceTo(center) > WARNING_RADIUS) {
                continue;
            }
            player.hurt(this.damageSources().mobAttack(this), WARNING_DAMAGE);
            Vec3 push = flatten(player.position().subtract(center)).scale(2.8);
            player.push(push.x, 0.7, push.z);
        }
        if (this.level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.EXPLOSION,
                    center.x, center.y + 0.3, center.z, 3, 0.4, 0.2, 0.4, 0.0);
            serverLevel.sendParticles(ParticleTypes.CRIT,
                    center.x, center.y + 0.5, center.z, 24, 1.2, 0.3, 1.2, 0.4);
        }
        this.level().playSound(null, center.x, center.y, center.z,
                SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 1.4f, 1.3f);
    }

    // ---------------------------------------------------------------- 交叉火力

    /** 交叉火力：礼点名一个玩家，桐香限制走位、郁子贴身，自己远程压制。 */
    private void tickCrossfire() {
        if (crossfireCd > 0 || !isBattleActive()) {
            return;
        }
        LivingEntity target = this.getTarget();
        if (!(target instanceof Player player) || !player.isAlive()) {
            return;
        }
        List<AbstractFSBossEntity> allies = nearbyBosses(CROSSFIRE_RADIUS);
        if (allies.isEmpty()) {
            // 单打独斗时这不算联动技，不浪费这次 CD
            return;
        }
        crossfireCd = cd(CROSSFIRE_CD);
        suppressCd = 0;

        broadcastNearby(getDialogueKey() + "crossfire", "§4");
        this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                SoundEvents.BELL_RESONATE, SoundSource.HOSTILE, 1.8f, 0.8f);
        for (AbstractFSBossEntity ally : allies) {
            ally.setTarget(player);
            ally.respondToCrossfire(player);
        }
    }

    @Override
    public void respondToCrossfire(Player target) {
        super.respondToCrossfire(target);
        // 被点名时礼也把追击与压制准备好，形成三方夹击
        pursuitCooldown = 0;
        suppressCd = 0;
    }

    /** 去掉 Y 分量并归一化，得到一个水平方向。 */
    private static Vec3 flatten(Vec3 v) {
        Vec3 flat = new Vec3(v.x, 0.0, v.z);
        return flat.lengthSqr() < 1.0E-6 ? new Vec3(0.0, 0.0, 1.0) : flat.normalize();
    }

    // ---------------------------------------------------------------- 枪械技能

    /**
     * 三个枪技共用的状态机。
     *
     * 不拆成三个 goal：它们都要「站定 + 瞄准 + 连续开火」，拆开会互相抢 MOVE / LOOK 标志位，
     * 还会同一时间各自开火。按距离和 CD 在里面挑一个模式执行。
     */
    private class GunplayGoal extends Goal {

        private static final int PHASE_DONE = 0;
        private static final int PHASE_BACKSTEP = 1;
        private static final int PHASE_AIM = 2;
        private static final int PHASE_FIRE = 3;
        private static final int PHASE_CHARGE = 4;
        private static final int PHASE_RETREAT = 5;

        private int mode = GUN_SUPPRESS;
        private int phase = PHASE_DONE;
        private int phaseTicks;
        private int shotsTotal;
        private int shotIndex;
        private int shotTimer;

        private GunplayGoal() {
            this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            if (!battleActive) {
                return false;
            }
            LivingEntity target = getTarget();
            if (target == null || !target.isAlive()) {
                return false;
            }
            double distance = distanceTo(target);
            // 贴脸反制最优先，其次是能一枪定音的狙击，最后才是常规压制
            if (distance <= POINT_BLANK_RANGE && pointBlankCd <= 0) {
                this.mode = GUN_POINT_BLANK;
                return true;
            }
            if (distance > 3.0 && distance <= SUPPRESS_RANGE && snipeCd <= 0) {
                this.mode = GUN_SNIPE;
                return true;
            }
            if (distance > 3.0 && distance <= SUPPRESS_RANGE && suppressCd <= 0) {
                this.mode = GUN_SUPPRESS;
                return true;
            }
            return false;
        }

        @Override
        public boolean canContinueToUse() {
            LivingEntity target = getTarget();
            return this.phase != PHASE_DONE && battleActive && target != null && target.isAlive();
        }

        @Override
        public void start() {
            getNavigation().stop();
            switch (this.mode) {
                case GUN_POINT_BLANK -> {
                    this.phase = PHASE_AIM;
                    this.phaseTicks = POINT_BLANK_AIM_TICKS;
                    playAnim("shoot");
                    broadcastNearby(getDialogueKey() + "point_blank", "§c");
                }
                case GUN_SNIPE -> {
                    this.phase = PHASE_CHARGE;
                    this.phaseTicks = phase2 ? SNIPE_CHARGE_PHASE2 : SNIPE_CHARGE;
                    playAnim("shoot");
                    broadcastNearby(getDialogueKey() + "snipe", "§4");
                    level().playSound(null, getX(), getY(), getZ(),
                            SoundEvents.CROSSBOW_LOADING_MIDDLE, SoundSource.HOSTILE, 1.6f, 0.7f);
                }
                default -> {
                    this.phase = PHASE_BACKSTEP;
                    this.phaseTicks = SUPPRESS_BACKSTEP_TICKS;
                    broadcastNearby(getDialogueKey() + "suppress", "§c");
                }
            }
        }

        @Override
        public void tick() {
            LivingEntity target = getTarget();
            if (target == null) {
                this.phase = PHASE_DONE;
                return;
            }
            getLookControl().setLookAt(target, 60.0f, 60.0f);
            switch (this.phase) {
                case PHASE_BACKSTEP -> tickBackstep(target);
                case PHASE_AIM -> tickAim();
                case PHASE_FIRE -> tickFire(target);
                case PHASE_CHARGE -> tickCharge(target);
                case PHASE_RETREAT -> tickRetreat(target);
                default -> this.phase = PHASE_DONE;
            }
        }

        private void tickBackstep(LivingEntity target) {
            Vec3 away = flatten(position().subtract(target.position()));
            setDeltaMovement(away.x * BACKSTEP_SPEED, getDeltaMovement().y, away.z * BACKSTEP_SPEED);
            if (--this.phaseTicks <= 0) {
                this.phase = PHASE_AIM;
                this.phaseTicks = SUPPRESS_AIM_TICKS;
                playAnim("shoot");
            }
        }

        private void tickRetreat(LivingEntity target) {
            Vec3 away = flatten(position().subtract(target.position()));
            setDeltaMovement(away.x * BACKSTEP_SPEED, getDeltaMovement().y, away.z * BACKSTEP_SPEED);
            if (--this.phaseTicks <= 0) {
                this.phase = PHASE_DONE;
            }
        }

        private void tickAim() {
            getNavigation().stop();
            if (--this.phaseTicks > 0) {
                return;
            }
            this.phase = PHASE_FIRE;
            this.shotsTotal = this.mode == GUN_POINT_BLANK
                    ? (phase2 ? POINT_BLANK_SHOTS_PHASE2 : POINT_BLANK_SHOTS)
                    : (phase2 ? SUPPRESS_SHOTS_PHASE2 : SUPPRESS_SHOTS);
            this.shotIndex = 0;
            this.shotTimer = 0;
        }

        private void tickFire(LivingEntity target) {
            getNavigation().stop();
            if (--this.shotTimer > 0) {
                return;
            }
            if (this.shotIndex >= this.shotsTotal) {
                if (this.mode == GUN_POINT_BLANK) {
                    // 打完立刻后撤并短加速，把距离重新拉回枪械射程
                    this.phase = PHASE_RETREAT;
                    this.phaseTicks = POINT_BLANK_RETREAT_TICKS;
                    addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 2 * 20, 1, false, false, true));
                } else {
                    this.phase = PHASE_DONE;
                }
                return;
            }
            fireShot(target, this.shotIndex);
            this.shotIndex++;
            this.shotTimer = this.mode == GUN_POINT_BLANK ? POINT_BLANK_INTERVAL : SUPPRESS_INTERVAL;
        }

        /** 连射：首发直线、中间预判、最后一发打当前位置并带击退。 */
        private void fireShot(LivingEntity target, int index) {
            boolean last = index == this.shotsTotal - 1;
            if (this.mode == GUN_POINT_BLANK) {
                fireBullet(target.getEyePosition(), target, BULLET_SPEED_MAX, POINT_BLANK_DAMAGE,
                        false, false, false, last ? 1.2 : 0.0, false);
                return;
            }
            Vec3 aim = index == 0 || last ? target.getEyePosition() : predictedAim(target);
            fireBullet(aim, target,
                    BULLET_SPEED_MIN + TadasugawaReiEntity.this.random.nextDouble()
                            * (BULLET_SPEED_MAX - BULLET_SPEED_MIN),
                    phase2 ? SUPPRESS_DAMAGE_PHASE2 : SUPPRESS_DAMAGE,
                    false, true, false, last ? 0.7 : 0.0, false);
        }

        /** 略微预判玩家的移动方向：按子弹大致飞行时间取一半提前量。 */
        private Vec3 predictedAim(LivingEntity target) {
            double distance = getEyePosition().distanceTo(target.getEyePosition());
            double flightTicks = distance / BULLET_SPEED_MAX;
            return target.getEyePosition().add(target.getDeltaMovement().scale(flightTicks * 0.5));
        }

        private void tickCharge(LivingEntity target) {
            getNavigation().stop();
            // 红色瞄准线一直画到开火，玩家能清楚看到自己被瞄着
            laserFx(getEyePosition(), target.getEyePosition());
            if (--this.phaseTicks > 0) {
                return;
            }
            // 不追踪：最后时刻横向移动就能躲掉
            fireBullet(target.getEyePosition(), null, SNIPE_SPEED, SNIPE_DAMAGE,
                    true, false, true, SNIPE_KNOCKBACK, true);
            broadcastNearby(getDialogueKey() + "snipe_fire", "§4");
            level().playSound(null, getX(), getY(), getZ(),
                    SoundEvents.CROSSBOW_SHOOT, SoundSource.HOSTILE, 2.0f, 0.5f);
            this.phase = PHASE_DONE;
        }

        @Override
        public void stop() {
            this.phase = PHASE_DONE;
            switch (this.mode) {
                case GUN_POINT_BLANK -> pointBlankCd = cd(POINT_BLANK_CD);
                case GUN_SNIPE -> snipeCd = cd(SNIPE_CD);
                default -> suppressCd = cd(SUPPRESS_CD);
            }
            getNavigation().stop();
        }
    }

    @Override
    protected void castUltimate() {
        // 最终执行期间礼的大招就是「全套枪械 + 追击」一起转好，直接压上来
        this.setTarget(this.getTarget());
        pursuitReady = true;
        pursuitCooldown = 0;
        purgeCd = 0;
        suppressCd = 0;
        snipeCd = 0;
    }

    @Override
    protected void onAllyLost() {
        // 场面变少以后礼会立刻换到追击节奏
        pursuitCooldown = 0;
    }
}
