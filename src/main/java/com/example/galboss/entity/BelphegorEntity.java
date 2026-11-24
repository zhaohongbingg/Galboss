package com.example.galboss.entity;

import com.example.galboss.stake.StakeFx;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 炼狱七桩 · 贝尔菲戈尔（懒惰）—— 阵地堡垒、靠减伤拖延。
 *
 * <table>
 *   <tr><td>核心被动</td><td><b>懒惰结界</b>：常驻 60% 减伤；玩家靠近到 5 格以内时减伤失效</td></tr>
 *   <tr><td>主动 1</td><td><b>迟缓力场</b>：紫黑力场 8 秒，场内玩家缓慢 III + 虚弱 II</td></tr>
 *   <tr><td>主动 2</td><td><b>梦游代劳</b>：召 2 只血厚傀儡，自身受到的一半伤害转移给傀儡</td></tr>
 * </table>
 */
public class BelphegorEntity extends AbstractPurgatoryStakeEntity {

    private static final double SAFE_RADIUS = 5.0D;
    private static final double FIELD_RADIUS = 6.0D;
    private static final int FIELD_TICKS = 160;
    private static final DustParticleOptions ASH_CYAN =
            new DustParticleOptions(new Vector3f(0.55F, 0.72F, 0.72F), 1.1F);

    /** 当前力场的中心与到期时间（0 表示没有力场）。 */
    private Vec3 fieldCenter = null;
    private long fieldUntil = 0L;

    /** 自己召出来的傀儡（只认这些，不会误用野生怪）。 */
    private final Set<UUID> puppets = new HashSet<>();

    public BelphegorEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
    }

    @Override
    public String assetId() {
        return "belphegor";
    }

    @Override
    protected int skillOneCooldown() {
        return 140;
    }

    @Override
    protected int skillTwoCooldown() {
        return 300;
    }

    // ---------------------------------------------------------------- 被动：懒惰结界

    @Override
    public boolean hurt(DamageSource source, float amount) {
        float incoming = amount;

        // 半分给傀儡
        LivingEntity puppet = firstLivingPuppet();
        if (puppet != null) {
            float half = incoming * 0.5F;
            puppet.invulnerableTime = 0;
            puppet.hurt(source, half);
            incoming -= half;
        }

        // 60% 减伤不放这儿：那是在护甲/抗性之前改入参，顺序不对。
        // 真正的减伤在 event/SlothGuard（LivingHurtEvent，护甲/抗性之后）。
        return super.hurt(source, incoming);
    }

    /** 玩家是否在 radius 内（供 SlothGuard 判断"减伤是否生效"）。 */
    public boolean playerWithinRadius(double radius) {
        return playerWithin(radius);
    }

    private boolean playerWithin(double radius) {
        if (!(this.level() instanceof ServerLevel level)) {
            return false;
        }
        return !StakeFx.playersInRadius(level, this.position(), radius).isEmpty();
    }

    private LivingEntity firstLivingPuppet() {
        if (puppets.isEmpty() || !(this.level() instanceof ServerLevel level)) {
            return null;
        }
        for (LivingEntity around : StakeFx.inRadius(level, this, this.position(), 24.0D)) {
            if (around instanceof Husk husk && puppets.contains(husk.getUUID()) && husk.isAlive()) {
                return husk;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 被动 tick：力场维持

    @Override
    protected void stakePassiveTick() {
        if (!(this.level() instanceof ServerLevel level)) {
            return;
        }
        // 半休眠漂浮姿态：低重力缓降 + 慵懒结界激活时的灰青环粒子
        this.fallDistance = 0.0F;
        if (this.tickCount % 12 == 0) {
            StakeFx.effect(this, MobEffects.SLOW_FALLING, 30, 0);
            if (!playerWithin(SAFE_RADIUS)) {
                StakeFx.ring(level, this.position().add(0, 0.9, 0), 1.2D, 10, ASH_CYAN);
            }
        }

        if (fieldCenter == null || level.getGameTime() > fieldUntil) {
            fieldCenter = null;
            return;
        }
        // 迟缓力场：箭矢/投射物进入后减速并下坠（每 tick 生效）
        AABB fieldBox = AABB.ofSize(fieldCenter, FIELD_RADIUS * 2.0D, FIELD_RADIUS * 2.0D, FIELD_RADIUS * 2.0D);
        for (Projectile proj : level.getEntitiesOfClass(Projectile.class, fieldBox)) {
            if (proj.position().distanceToSqr(fieldCenter) > FIELD_RADIUS * FIELD_RADIUS || proj.onGround()) {
                continue;
            }
            Vec3 v = proj.getDeltaMovement();
            proj.setDeltaMovement(v.x * 0.72D, Math.min(v.y * 0.72D, v.y) - 0.08D, v.z * 0.72D);
            proj.hurtMarked = true;
            if (this.tickCount % 3 == 0) {
                level.sendParticles(ASH_CYAN, proj.getX(), proj.getY(), proj.getZ(), 1, 0, 0, 0, 0);
            }
        }

        if (this.tickCount % 10 != 0) {
            // 药水每 tick 重刷会把日志/同步打爆（实测刷屏），统一 10 tick 续一次
            return;
        }
        StakeFx.ring(level, fieldCenter.add(0, 0.1, 0), FIELD_RADIUS, 24, ParticleTypes.WITCH);
        StakeFx.ring(level, fieldCenter.add(0, 0.15, 0), FIELD_RADIUS, 12, ASH_CYAN);
        for (LivingEntity inside : StakeFx.inRadius(level, this, fieldCenter, FIELD_RADIUS)) {
            if (!(inside instanceof net.minecraft.world.entity.player.Player)) {
                continue;
            }
            StakeFx.effect(inside, MobEffects.MOVEMENT_SLOWDOWN, 30, 2); // 缓慢 III
            StakeFx.effect(inside, MobEffects.WEAKNESS, 30, 1);          // 虚弱 II
        }
    }

    // ---------------------------------------------------------------- 主动 1：迟缓力场

    @Override
    protected void castSkillOne(ServerLevel level, LivingEntity target) {
        this.fieldCenter = target.position();
        this.fieldUntil = level.getGameTime() + FIELD_TICKS;
        StakeFx.ring(level, fieldCenter.add(0, 0.1, 0), FIELD_RADIUS, 40, ParticleTypes.WITCH);
        StakeFx.sound(level, fieldCenter, SoundEvents.SOUL_ESCAPE, 1.4F, 0.6F);
    }


    // ---------------------------------------------------------------- 主动 2：梦游代劳

    @Override
    protected void castSkillTwo(ServerLevel level, LivingEntity target) {
        int alive = 0;
        for (LivingEntity around : StakeFx.inRadius(level, this, this.position(), 24.0D)) {
            if (around instanceof Husk husk && puppets.contains(husk.getUUID()) && husk.isAlive()) {
                alive++;
            }
        }
        int toSpawn = Math.max(0, 3 - alive);
        for (int i = 0; i < toSpawn; i++) {
            Husk husk = EntityType.HUSK.create(level);
            if (husk == null) {
                continue;
            }
            double angle = Math.PI * 2.0D * i / Math.max(1, toSpawn);
            husk.moveTo(this.getX() + Math.cos(angle) * 2.0D, this.getY(), this.getZ() + Math.sin(angle) * 2.0D,
                    this.getYRot(), 0.0F);
            husk.setPersistenceRequired();
            husk.setCustomName(Component.literal("梦游傀儡"));
            if (husk.getAttribute(Attributes.MAX_HEALTH) != null) {
                husk.getAttribute(Attributes.MAX_HEALTH).setBaseValue(60.0D);
            }
            husk.setHealth(60.0F);
            if (husk.getAttribute(Attributes.MOVEMENT_SPEED) != null) {
                // 慢速
                husk.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.16D);
            }
            level.addFreshEntity(husk);
            puppets.add(husk.getUUID());
            StakeFx.butterfly(level, husk.position(), 6, 0.6D);
        }
        StakeFx.sound(level, this.position(), SoundEvents.SOUL_ESCAPE, 1.2F, 0.8F);
        com.example.galboss.network.ModNetwork.sendRedTruthLiteral(level, this.position(), 40.0D,
                "「别急……我们有很多时间。」", 50);
    }
}
