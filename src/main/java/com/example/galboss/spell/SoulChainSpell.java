package com.example.galboss.spell;

import com.example.galboss.GalBoss;
import com.example.galboss.fx.PixelfxFx;
import com.example.galboss.fx.PixelfxSlugs;
import com.example.galboss.particle.ModParticles;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 魂链：把施法者周围的怪物串成一条共伤链——链上任一成员受伤，其余成员同步承受一部分。
 *
 * <p>和 {@link ChainLightningSpell} 的区别：链雷是「电一跳一跳地传过去、伤害递减」，
 * 魂链是「一段时间内共享痛觉」，本质是减益而不是输出手段。
 *
 * <p>反制：链只锁**施放瞬间**在范围内的怪，之后跑进来的不算；打死或走远（>24 格）会自动脱链。
 */
@Mod.EventBusSubscriber(modid = GalBoss.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class SoulChainSpell extends Spell {

    /** 施放时抓取成员的范围（格）。 */
    private static final double LINK_RADIUS = 10.0;
    /** 最多链几只，防止把半个区块的怪全串起来。 */
    private static final int MAX_MEMBERS = 6;
    /** 链持续时长（tick）。 */
    private static final int DURATION = 200;
    /** 成员之间拉开多远就自动脱链（格）。 */
    private static final double LEASH = 24.0;
    /** 伤害复制比例：任一成员挨打，其余成员各承受原伤害的这个比例。 */
    private static final float SHARE_RATIO = 0.5f;
    /** 弧光重绘间隔（tick）。 */
    private static final int ARC_INTERVAL = 5;
    /** 脚下封印阵的重刷间隔 / 单枚寿命（tick）。成员会移动，只能按位置重刷。 */
    private static final int MARKER_INTERVAL = 25;
    private static final int MARKER_LIFE = 28;

    /** 魂之索的纵向粗细倍数（横向由两点距离自动决定）。defaultScale 0.02 × 36px × 0.8 ≈ 0.58 格粗。 */
    private static final float LINK_SCALE = 0.8f;

    /** 成员 UUID → 所在链路。服务端全局表，供伤害事件 O(1) 查询。 */
    private static final Map<UUID, SoulChainSpell> BY_MEMBER = new ConcurrentHashMap<>();

    /**
     * 伤害传播时的重入保护。传播过程中对成员调用 {@code hurt()} 会再次触发
     * {@link LivingDamageEvent}，不加这个标志会 A→B→A 无限递归。
     */
    private static final ThreadLocal<Boolean> SYNCING = ThreadLocal.withInitial(() -> false);

    private final List<LivingEntity> members = new ArrayList<>();
    private boolean linked;
    private boolean dissolved;

    public SoulChainSpell(Level level, Vec3 origin, @Nullable LivingEntity caster) {
        super(level, origin, caster);
        if (level.isClientSide()) {
            return;
        }
        AABB box = new AABB(origin, origin).inflate(LINK_RADIUS);
        List<LivingEntity> candidates = level.getEntitiesOfClass(LivingEntity.class, box,
                e -> e.isAlive()
                        && !e.isSpectator()
                        && e != caster
                        && e instanceof Mob
                        && e.distanceToSqr(origin) <= LINK_RADIUS * LINK_RADIUS);
        candidates.sort(Comparator.comparingDouble(e -> e.distanceToSqr(origin)));
        for (LivingEntity candidate : candidates) {
            if (this.members.size() >= MAX_MEMBERS) {
                break;
            }
            this.members.add(candidate);
        }
    }

    /** 以施法者自身位置为圆心施放。 */
    public static void cast(Level level, Vec3 origin, @Nullable LivingEntity caster) {
        SpellEngine.cast(new SoulChainSpell(level, origin, caster));
    }

    /** 链上是否还有活着的成员。少于 2 个就没什么「共伤」可言了。 */
    private boolean worthKeeping() {
        int alive = 0;
        for (LivingEntity member : this.members) {
            if (member.isAlive()) {
                alive++;
            }
        }
        return alive >= 2;
    }

    @Override
    protected void step() {
        if (!(this.level instanceof ServerLevel server)) {
            return;
        }

        if (!this.linked) {
            this.linked = true;
            if (!this.worthKeeping()) {
                // 周围没有足够的怪，直接收场，不要留个空链挂在表里
                this.dissolve(server, false);
                return;
            }
            for (LivingEntity member : this.members) {
                BY_MEMBER.put(member.getUUID(), this);
            }
            this.playLinkFx(server);
            return;
        }

        // 成员死亡 / 跑太远 → 脱链
        this.members.removeIf(member -> {
            if (!member.isAlive() || member.distanceToSqr(this.origin) > LEASH * LEASH) {
                BY_MEMBER.remove(member.getUUID());
                return true;
            }
            return false;
        });

        if (!this.worthKeeping() || this.age >= DURATION) {
            this.dissolve(server, this.age >= DURATION);
            return;
        }

        if (this.age % MARKER_INTERVAL == 0) {
            this.spawnNodeMarkers(server);
        }
    }

    /** 断开链路：清全局表 + 消散演出。 */
    private void dissolve(ServerLevel server, boolean natural) {
        if (this.dissolved) {
            return;
        }
        this.dissolved = true;
        for (LivingEntity member : this.members) {
            BY_MEMBER.remove(member.getUUID());
            if (member.isAlive()) {
                server.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                        member.getX(), member.getY() + 1.0, member.getZ(),
                        6, 0.3, 0.4, 0.3, 0.02);
            }
        }
        if (natural) {
            server.playSound(null, this.origin.x, this.origin.y, this.origin.z,
                    SoundEvents.AMETHYST_BLOCK_BREAK, SoundSource.HOSTILE, 0.8f, 1.3f);
        }
    }

    private void playLinkFx(ServerLevel server) {
        PixelfxFx.play(server, this.origin.add(0.0, 0.08, 0.0),
                PixelfxSlugs.TOHKA_CONFINEMENT_SIGIL, 1.1f, 0.0f);
        server.playSound(null, this.origin.x, this.origin.y, this.origin.z,
                SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.HOSTILE, 1.0f, 1.5f);
        for (LivingEntity member : this.members) {
            server.sendParticles(ModParticles.BURST_DOT.get(),
                    member.getX(), member.getY() + 1.0, member.getZ(),
                    3, 0.2, 0.3, 0.2, 0.01);
        }
        this.spawnLinks(server);
    }

    /**
     * 拉起魂之索：施法者 → 每个成员各一条，成员之间再连成一圈。
     *
     * <p>用 {@link PixelfxFx#playLink} 而不是在 tick 里画粒子——素材会沿两点连线拉伸，
     * 且位置由客户端每帧从实体实时读取，所以目标跑动时索会跟着弯，不会拖在原地。
     * 所有索共用同一张贴图，合批渲染器会把它们并进同一次 Draw Call。
     */
    private void spawnLinks(ServerLevel server) {
        LivingEntity hub = this.caster != null && this.caster.isAlive() ? this.caster : null;
        if (hub != null) {
            for (LivingEntity member : this.members) {
                if (member.isAlive()) {
                    PixelfxFx.playLink(server, hub, member,
                            PixelfxSlugs.TOHKA_SOUL_CHAIN, LINK_SCALE, DURATION);
                }
            }
        }
        int size = this.members.size();
        if (size >= 2) {
            for (int i = 0; i < size; i++) {
                LivingEntity a = this.members.get(i);
                LivingEntity b = this.members.get((i + 1) % size);
                if (a != b && a.isAlive() && b.isAlive()) {
                    PixelfxFx.playLink(server, a, b,
                            PixelfxSlugs.TOHKA_SOUL_CHAIN, LINK_SCALE, DURATION);
                }
            }
        }
    }

    /**
     * 每个成员脚下挂一枚封印阵，让「谁被链住了」一眼可辨。
     * pixelfx 是静态贴图，成员会跑，所以按固定间隔在新位置重刷一枚。
     */
    private void spawnNodeMarkers(ServerLevel server) {
        for (LivingEntity member : this.members) {
            if (!member.isAlive()) {
                continue;
            }
            PixelfxFx.playLooping(server, member.position().add(0.0, 0.06, 0.0),
                    PixelfxSlugs.TOHKA_CONFINEMENT_SIGIL, MARKER_LIFE, 0.85f, 0.0f);
        }
    }

    // ------------------------------------------------------------------ 伤害同步

    /**
     * 链上任一成员挨打时，把伤害复制一部分给其余成员。
     *
     * <p>用 {@link LivingDamageEvent}（护甲/抗性结算后的最终伤害）而不是 {@code LivingHurtEvent}，
     * 否则「同步」过去的伤害会比本体实际承受的多。
     */
    @SubscribeEvent
    public static void onLivingDamage(LivingDamageEvent event) {
        if (SYNCING.get()) {
            return;
        }
        LivingEntity victim = event.getEntity();
        SoulChainSpell link = BY_MEMBER.get(victim.getUUID());
        if (link == null || link.dissolved) {
            return;
        }
        float amount = event.getAmount();
        if (amount <= 0.0f) {
            return;
        }
        link.propagate(victim, amount);
    }

    private void propagate(LivingEntity from, float amount) {
        float share = amount * SHARE_RATIO;
        if (share < 0.05f) {
            return;
        }
        var damageSource = this.caster == null
                ? this.level.damageSources().magic()
                : this.level.damageSources().indirectMagic(this.caster, this.caster);

        SYNCING.set(true);
        try {
            for (LivingEntity member : List.copyOf(this.members)) {
                if (member == from || !member.isAlive()) {
                    continue;
                }
                member.hurt(damageSource, share);
                if (this.level instanceof ServerLevel server) {
                    server.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                            member.getX(), member.getY() + 1.0, member.getZ(),
                            4, 0.25, 0.35, 0.25, 0.02);
                }
            }
        } finally {
            SYNCING.set(false);
        }
    }

    @Override
    public int durationTicks() {
        return DURATION + 1;
    }

    @Override
    public String id() {
        return "soul_chain";
    }
}
