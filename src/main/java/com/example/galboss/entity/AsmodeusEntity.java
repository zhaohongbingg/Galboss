package com.example.galboss.entity;

import com.example.galboss.GalBoss;
import com.example.galboss.network.ModNetwork;
import com.example.galboss.stake.StakeFx;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 炼狱七桩 · 阿斯莫德（色欲）—— 幻象骗招、牵引共享。
 *
 * <table>
 *   <tr><td>核心被动</td><td><b>迷乱之香</b>：玩家近距离直视本体超过 2 秒 → 反胃 + 镜头抖动（"迷醉"）</td></tr>
 *   <tr><td>主动 1</td><td><b>镜花水月</b>：召 2 个只有 1 血的分身；分身被杀，本体回血并让凶手吃精神反伤</td></tr>
 *   <tr><td>主动 2</td><td><b>红线牵引</b>：把玩家拉近并连上红线，连接期间玩家回多少血，本体跟着回</td></tr>
 * </table>
 *
 * <p>"简单实现"的取舍：
 * <ul>
 *   <li>「25% 攻击丢失率」没有真的拦截伤害，用<b>反胃</b>（准星乱摆，实战里就是打空）近似 ——
 *       真做丢失率要挂 {@code LivingHurtEvent} 并按玩家区分，成本和收益不划算。</li>
 *   <li>分身/连接关系存在内存里，不写 NBT：服务器重启后分身会变成普通的 1 血精英、连接自动失效。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = GalBoss.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class AsmodeusEntity extends AbstractPurgatoryStakeEntity {

    /** 分身 UUID → 本体 UUID。 */
    private static final Map<UUID, UUID> CLONES = new HashMap<>();

    private static final double GAZE_RANGE = 6.0D;
    private static final int GAZE_TICKS = 40;

    /** 玩家 UUID → 已连续直视的 tick 数。 */
    private final Map<UUID, Integer> gazeTicks = new HashMap<>();
    /** 被迷醉的玩家 UUID → 迷醉结束时间（期间攻击阿斯莫德有 25% 概率落空）。 */
    private static final Map<UUID, Long> INFATUATED_UNTIL = new HashMap<>();

    /** 红线连着的玩家（连接期间同步回血）。 */
    private UUID linked;
    private float linkedLastHealth;
    private long linkUntil;

    /** 自己是不是分身。 */
    private boolean clone;
    private UUID ownerId;

    private static final float CLONE_DEATH_HEAL = 20.0F;
    private static final float MIND_BACKLASH = 6.0F;

    public AsmodeusEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
    }

    @Override
    public String assetId() {
        return "asmodeus";
    }

    @Override
    protected int skillOneCooldown() {
        return 180;
    }

    @Override
    protected int skillTwoCooldown() {
        return 120;
    }

    /** 分身不放技能（否则分身会继续放镜花水月，指数爆炸）。 */
    @Override
    protected boolean usesAbilities() {
        return !this.clone;
    }

    /** 分身不算「七桩之一」，不然它们会挡住黄金乡的登场判定。 */
    @Override
    public boolean countsAsStake() {
        return !this.clone;
    }

    /** 分身只用来骗招，不造成伤害（否则 1 血分身照样能打出 10 点，比设计表更坑）。 */
    @Override
    public boolean doHurtTarget(net.minecraft.world.entity.Entity target) {
        return !this.clone && super.doHurtTarget(target);
    }

    // ---------------------------------------------------------------- 被动：迷乱之香

    @Override
    protected void stakePassiveTick() {
        if (!(this.level() instanceof ServerLevel level) || this.tickCount % 5 != 0) {
            return;
        }
        Iterator<Map.Entry<UUID, Integer>> it = gazeTicks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> entry = it.next();
            Player player = level.getPlayerByUUID(entry.getKey());
            if (player == null || !player.isAlive() || this.distanceTo(player) > GAZE_RANGE) {
                it.remove();
                continue;
            }
            if (isLookingAtMe(player)) {
                int ticks = entry.getValue() + 5;
                if (ticks >= GAZE_TICKS) {
                    StakeFx.effect(player, MobEffects.CONFUSION, 120, 0); // 屏幕扭曲
                    INFATUATED_UNTIL.put(player.getUUID(), level.getGameTime() + 120L);
                    ModNetwork.sendScreenShake(level, player.position(), 4.0D, 0.25F, 8);
                    StakeFx.sound(level, player.position(), SoundEvents.AMBIENT_CAVE.value(), 0.8F, 1.6F);
                    it.remove();
                    continue;
                }
                entry.setValue(ticks);
            } else {
                entry.setValue(0);
            }
        }
        for (Player player : StakeFx.playersInRadius(level, this.position(), GAZE_RANGE)) {
            gazeTicks.putIfAbsent(player.getUUID(), 0);
        }

        // 红线连接的同步回血
        if (linked != null && level.getGameTime() <= linkUntil) {
            Player player = level.getPlayerByUUID(linked);
            if (player != null) {
                float gain = player.getHealth() - linkedLastHealth;
                if (gain > 0.05F) {
                    this.heal(gain);
                    StakeFx.butterfly(level, this.position(), 3, 0.4D);
                }
                linkedLastHealth = player.getHealth();
            }
        }
    }

    private boolean isLookingAtMe(Player player) {
        Vec3 to = this.getEyePosition().subtract(player.getEyePosition());
        if (to.lengthSqr() < 1.0E-4D) {
            return false;
        }
        return player.getLookAngle().dot(to.normalize()) > 0.97D;
    }

    // ---------------------------------------------------------------- 主动 1：镜花水月

    @Override
    protected void castSkillOne(ServerLevel level, LivingEntity target) {
        // 上限：本体最多同时保留 2 个分身（上限判断防的是「上一轮的分身还没清掉又召一轮」）
        int existing = 0;
        for (UUID ignored : CLONES.values()) {
            if (ignored.equals(this.getUUID())) {
                existing++;
            }
        }
        if (existing >= 2) {
            return;
        }
        for (int i = 0; i < 2 - existing; i++) {
            Entity created = this.getType().create(level);
            if (!(created instanceof AsmodeusEntity cloneStake)) {
                continue;
            }
            double angle = Math.PI * 2.0D * i / 2.0D + Math.PI / 2.0D;
            cloneStake.moveTo(this.getX() + Math.cos(angle) * 2.5D, this.getY(), this.getZ() + Math.sin(angle) * 2.5D,
                    this.getYRot(), 0.0F);
            cloneStake.setCloneOf(this.getUUID());
            cloneStake.setPersistenceRequired();
            cloneStake.setHealth(1.0F);
            level.addFreshEntity(cloneStake);
            CLONES.put(cloneStake.getUUID(), this.getUUID());
            StakeFx.butterfly(level, cloneStake.position(), 8, 0.8D);
        }
        StakeFx.sound(level, this.position(), SoundEvents.ILLUSIONER_MIRROR_MOVE, 1.2F, 1.0F);
    }

    private void setCloneOf(UUID ownerId) {
        this.clone = true;
        this.ownerId = ownerId;
        if (this.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH) != null) {
            this.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(1.0D);
        }
        this.setHealth(1.0F);
    }

    /** 分身被杀：本体回血，凶手吃精神反伤。 */
    @Override
    public void die(net.minecraft.world.damagesource.DamageSource source) {
        if (this.clone && !this.level().isClientSide && this.level() instanceof ServerLevel level) {
            CLONES.remove(this.getUUID());
            Entity killer = source.getEntity();
            if (ownerId != null && level.getEntity(ownerId) instanceof AsmodeusEntity owner) {
                owner.heal(CLONE_DEATH_HEAL);
                StakeFx.butterfly(level, owner.position(), 10, 0.6D);
            }
            if (killer instanceof LivingEntity livingKiller) {
                StakeFx.hurt(level, this, livingKiller, MIND_BACKLASH);
                StakeFx.effect(livingKiller, MobEffects.CONFUSION, 100, 0);
            }
        }
        super.die(source);
    }


    // ---------------------------------------------------------------- 主动 2：红线牵引

    @Override
    protected void castSkillTwo(ServerLevel level, LivingEntity target) {
        Vec3 from = this.getEyePosition();
        StakeFx.line(level, from, target.getEyePosition(), ParticleTypes.HEART, 2);
        StakeFx.sound(level, from, SoundEvents.AMETHYST_BLOCK_CHIME, 1.2F, 1.4F);

        StakeFx.pull(target, this.position(), 1.0D);
        StakeFx.effect(target, MobEffects.GLOWING, 200, 0);
        if (target instanceof Player player) {
            this.linked = player.getUUID();
            this.linkedLastHealth = player.getHealth();
            this.linkUntil = level.getGameTime() + 200;
        }
        StakeFx.hurt(level, this, target, 5.0F);
        com.example.galboss.network.ModNetwork.sendRedTruthLiteral(level, this.position(), 40.0D,
                "「你已经被我的线拴住了。」", 50);
    }

    /** 迷醉状态下攻击阿斯莫德有 25% 概率落空（Miss）。 */
    @SubscribeEvent
    public static void onLivingAttack(LivingAttackEvent event) {
        if (!(event.getEntity() instanceof AsmodeusEntity asmodeus) || asmodeus.level().isClientSide) {
            return;
        }
        Entity attacker = event.getSource().getEntity();
        if (!(attacker instanceof Player player)) {
            return;
        }
        Long until = INFATUATED_UNTIL.get(player.getUUID());
        if (until == null || asmodeus.level().getGameTime() > until) {
            INFATUATED_UNTIL.remove(player.getUUID());
            return;
        }
        if (player.getRandom().nextFloat() < 0.25F) {
            event.setCanceled(true);
            if (asmodeus.level() instanceof ServerLevel server) {
                StakeFx.sound(server, asmodeus.position(), SoundEvents.ILLUSIONER_MIRROR_MOVE, 0.9F, 1.4F);
                server.sendParticles(ParticleTypes.HEART,
                        asmodeus.getX(), asmodeus.getY() + 1.4, asmodeus.getZ(), 4, 0.3, 0.3, 0.3, 0.02);
            }
            player.displayClientMessage(Component.literal("迷醉使你的攻击落空了！"), true);
        }
    }
}
