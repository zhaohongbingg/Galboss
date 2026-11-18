package com.example.galboss.entity;

import com.example.galboss.particle.ModParticles;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 谢斯塔狙击术的「光箭」：追踪 + **无视障碍**（不撞方块）。
 *
 * <h2>为什么是实体而不是"两秒后直接扣血"</h2>
 * 玩家需要一个能看见的东西飞过来，才知道自己在被谁打、往哪个方向躲（虽然躲不掉）。
 * 光箭飞行 0.1~0.3 秒，期间不判方块碰撞 —— 它从墙里穿过去也不会停，这就是「无视障碍」。
 *
 * <h2>伤害</h2>
 * 命中扣<b>目标当前生命的一半</b>（{@code DAMAGE_FRACTION}）。用魔法伤害源（{@code indirectMagic}），
 * 所以护甲不减少它 —— 狙击的意义就是"打中就掉一半，挡不住"。
 * 因为是"当前生命的一半"，它<b>永远不会一击致死</b>（剩 1 血时只掉 0.5）。
 *
 * <h2>外观</h2>
 * 本体不渲染模型（客户端 {@code NoopRenderer}）：看起来就是一颗高速白光点 + 金色拖尾，
 * 命中时溅血（{@code blood_drop} / {@code blood_splash}）。
 */
public class SniperArrowEntity extends Projectile {

    /** 速度（格/tick）：1.7 ≈ 34 格/秒，视觉上是一道白线。 */
    private static final double SPEED = 1.7;

    /** 最长寿命（tick）：追踪不到就散掉，不会永远飞。 */
    private static final int MAX_LIFE = 60;

    /** 命中判定用的包围盒膨胀量（格）：贴边就算中，不要等人正好站在准心上。 */
    private static final double HIT_INFLATE = 0.35;

    /** 每 tick 把位移拆成几步做扫掠判定（防隧穿，见 tick 里的注释）。 */
    private static final int MOVEMENT_SUBSTEPS = 8;

    /** 伤害比例：目标<b>当前</b>生命 × 此值。 */
    private static final float DAMAGE_FRACTION = 0.5f;

    /** 目标 UUID：只有 UUID 能在读档/区块重载后重新解析。 */
    @Nullable
    private UUID targetUuid;

    private int lifeTicks;

    public SniperArrowEntity(EntityType<? extends SniperArrowEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        // 无视一切方块碰撞：这就是"无视障碍"
        this.setNoGravity(true);
    }

    @Override
    protected void defineSynchedData() {
        // 轨迹完全由服务端算（每 tick 发粒子），客户端不需要同步字段
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    /** 起飞：从射手眼睛朝目标胸口。 */
    public void launch(LivingEntity owner, LivingEntity target) {
        this.setOwner(owner);
        this.targetUuid = target.getUUID();
        Vec3 from = owner.getEyePosition().subtract(0.0, 0.15, 0.0);
        this.setPos(from.x, from.y, from.z);
        Vec3 dir = target.getEyePosition().subtract(from);
        if (dir.lengthSqr() < 1.0E-4) {
            dir = owner.getLookAngle();
        }
        this.setDeltaMovement(dir.normalize().scale(SPEED));
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide()) {
            return;
        }
        if (++this.lifeTicks > MAX_LIFE) {
            com.example.galboss.GalBoss.LOGGER.info("[Sniper] arrow expired at {} (target resolved={})",
                    this.position(), this.resolveTarget() != null);
            this.discard();
            return;
        }

        LivingEntity target = this.resolveTarget();
        if (target != null) {
            // 一点点修正（不是瞬间贴脸）：目标跑动时箭头会拐过去，但看起来仍是"直线飞行的光箭"。
            // 瞄的是身体包围盒中心，不是眼睛 —— 用眼睛当目标点会让箭擦着胸口飞过去。
            Vec3 aim = target.getBoundingBox().getCenter();
            Vec3 desired = aim.subtract(this.position()).normalize().scale(SPEED);
            Vec3 current = this.getDeltaMovement();
            this.setDeltaMovement(current.scale(0.62).add(desired.scale(0.38)).normalize().scale(SPEED));
        }
        Vec3 movement = this.getDeltaMovement();
        // 子步进：1.7 格/tick 的位移一次就能跨过 0.9 格的判定球（隧穿），
        // 必须把这一 tick 的位移拆成若干小步逐段判定，否则"看起来射中了却没掉血"。
        int steps = MOVEMENT_SUBSTEPS;
        for (int i = 0; i < steps; i++) {
            this.setPos(this.getX() + movement.x / steps,
                    this.getY() + movement.y / steps,
                    this.getZ() + movement.z / steps);
            // ⚠ 不能用 distanceTo(target)：那是"脚对脚"的距离，而箭在眼睛高度飞，
            // 垂直差就有 1.5 格，大于旧阈值 1.2 → 箭会从人身上穿过去却不判定（实测踩过）。
            if (target != null && target.getBoundingBox().inflate(HIT_INFLATE).contains(this.position())) {
                this.impact(target);
                return;
            }
        }
        this.spawnTrail();
        // 目标已经没了：飞满寿命自然消散（不撞墙，所以不会提前消失）
        if (target == null && this.lifeTicks > 10) {
            this.discard();
        }
    }

    /**
     * 解析目标。
     *
     * <p>⚠ 玩家必须走 {@code PlayerList.getPlayer(uuid)}：{@code ServerLevel.getEntity(UUID)} 只查实体表，
     * 玩家不在其中（实测现象：光箭飞出去、目标永远是 null，于是打不到人）。
     */
    @Nullable
    private LivingEntity resolveTarget() {
        if (this.targetUuid == null) {
            return null;
        }
        if (this.getServer() != null) {
            var player = this.getServer().getPlayerList().getPlayer(this.targetUuid);
            if (player != null && player.isAlive() && !player.isSpectator()) {
                return player;
            }
        }
        Entity entity = this.level() instanceof ServerLevel server ? server.getEntity(this.targetUuid) : null;
        if (entity instanceof LivingEntity living && living.isAlive() && !living.isSpectator()) {
            return living;
        }
        return null;
    }

    /** 金色拖尾：一颗光点 + 偶尔一颗星屑，读起来是一道飞行的光。 */
    private void spawnTrail() {
        if (!(this.level() instanceof ServerLevel server)) {
            return;
        }
        server.sendParticles(ModParticles.BURST_DOT.get(),
                this.getX(), this.getY(), this.getZ(), 2, 0.04, 0.04, 0.04, 0.0);
        if (this.lifeTicks % 3 == 0) {
            server.sendParticles(ModParticles.BURST_STAR.get(),
                    this.getX(), this.getY(), this.getZ(), 1, 0.06, 0.06, 0.06, 0.0);
        }
    }

    /** 命中：扣当前生命的一半 + 溅血。 */
    private void impact(LivingEntity target) {
        Entity owner = this.getOwner();
        float damage = Math.max(0.5f, target.getHealth() * DAMAGE_FRACTION);
        var source = owner == null
                ? this.damageSources().magic()
                : this.damageSources().indirectMagic(this, owner);
        boolean hurt = target.hurt(source, damage);
        com.example.galboss.GalBoss.LOGGER.info("[Sniper] arrow hit {} for {} (hurt={}, hp {}->{})",
                target.getName().getString(), String.format("%.2f", damage), hurt,
                String.format("%.1f", target.getHealth() + (hurt ? 0.0f : damage)), String.format("%.1f", target.getHealth()));

        if (this.level() instanceof ServerLevel server) {
            Vec3 at = target.getEyePosition().subtract(0.0, 0.35, 0.0);
            // 血花先喷（大、散），血珠跟上（小、受重力）：两段读完才有"被贯穿"的实感
            server.sendParticles(ModParticles.BLOOD_SPLASH.get(),
                    at.x, at.y, at.z, 3, 0.18, 0.18, 0.18, 0.06);
            server.sendParticles(ModParticles.BLOOD_DROP.get(),
                    at.x, at.y, at.z, 14, 0.28, 0.30, 0.28, 0.12);
            server.playSound(null, at.x, at.y, at.z,
                    SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.HOSTILE, 1.2f, 1.45f);
            server.playSound(null, at.x, at.y, at.z,
                    SoundEvents.ARROW_HIT_PLAYER, SoundSource.HOSTILE, 1.0f, 0.85f);
        }
        this.discard();
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (this.targetUuid != null) {
            tag.putUUID("TargetUuid", this.targetUuid);
        }
        tag.putInt("LifeTicks", this.lifeTicks);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.targetUuid = tag.hasUUID("TargetUuid") ? tag.getUUID("TargetUuid") : null;
        this.lifeTicks = tag.getInt("LifeTicks");
    }
}
