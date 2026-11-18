package com.example.galboss.entity;

import com.example.galboss.fx.ButterflyPathParams;
import com.example.galboss.particle.ModParticles;
import com.example.galboss.ritual.SacrificeLedger;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 「黄金蝶」——贝阿朵莉切把远程法术换成一只<b>会飞过来的蝴蝶</b>。
 *
 * <h2>为什么做成实体而不是「服务端粒子 + 到点扣血」</h2>
 * 粒子方案看起来一样，但它没有「飞行中」这个概念：玩家没法打断、没法看见它绕过来、
 * 一旦丢包就凭空命中。做成真正的 {@link Projectile} 之后，命中判定、闪避、被盾挡、
 * 被别的模组的反制手段拦下来，全都走原版规则，只是<b>它的外观由粒子构成</b>。
 *
 * <h2>外观</h2>
 * 这个实体本身<b>不渲染任何模型</b>（客户端注册的是 {@code NoopRenderer}）：
 * 每 tick 在服务端向当前位置发一只 {@code galboss:golden_butterfly} 粒子，
 * 粒子自带飞行抖动与发光层，看起来就是一只金色镂空蝴蝶在追你。命中瞬间交给
 * {@link GoldenButterflyBurst}（星芒 + 光环 + 蝶群 + 余晖）。
 *
 * <h2>数值</h2>
 * 6 点魔法伤害、0.62 格/tick、转向率限制在 0.26（追得上走路的人，追不上疾跑 + 拐弯），
 * 最长 70 tick（3.5 秒）后自行消散 —— 不会有一只蝴蝶绕着你转一辈子。
 */
public class ButterflyMissileEntity extends Projectile {

    /** 命中伤害（魔法）。 */
    private static final float DAMAGE = 6.0f;

    /**
     * 飞行速度（格 / tick）由 {@link ButterflyPathParams#speed()} 提供（默认 0.62 ≈ 12.4 格/秒）。
     *
     * <p>放在 config 里是因为它和螺旋是一对：想复刻截图那种「比较圆的慢螺线」，
     * 必须把速度降下来，否则一圈只剩一两个 tick，眼睛只能看到抖动。
     */

    /** 每 tick 允许朝目标修正的向量权重：越小越「笨」，越大越黏人。 */
    private static final double TURN = 0.26;

    /** 寿命上限（tick）。超时就按「落空」处理，只在原地散一下。 */
    private static final int MAX_LIFE = 70;

    /** 命中判定半径（在实体包围箱外再扩这么多格）。蝴蝶本体只有 0.7 格，给玩家一点容错。 */
    private static final double HIT_INFLATE = 0.55;

    /**
     * 尾迹螺旋参数（半径 / 螺距 / 竖直压扁 / 旋向 / 落点间隔）。
     *
     * <p>从 {@code config/galboss/fx/butterfly_path.json} 读，起飞时取一次快照；
     * 没有那份 config 就用 {@link ButterflyPathParams#DEFAULT}。见该类顶部的方程对照。
     */
    private ButterflyPathParams pathParams = ButterflyPathParams.DEFAULT;

    private int lifeTicks;

    public ButterflyMissileEntity(EntityType<? extends ButterflyMissileEntity> type, Level level) {
        super(type, level);
        // 位移自己算（追踪 + 直线），不要让原版重力/摩擦插一脚
        this.noPhysics = true;
    }

    @Override
    protected void defineSynchedData() {
        // 没有需要额外同步的字段：朝向就是 deltaMovement，位置由原版实体同步负责
    }

    /** 蝴蝶不该被箭/三叉戟那种「命中实体」逻辑吃掉，也不该被推动。 */
    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    /**
     * 起飞：绑定主人与目标，给出朝向目标的初速度。
     *
     * <p>{@code speedFactor} 让同一批蝴蝶**速度不一样**：全同速会同点同向并排飞，
     * 加上蝴蝶粒子的加色辉光，看起来就是一团白雾而不是「一群蝴蝶」。
     * 0.85~1.15 的差异足够让它们在两秒内拉开距离。
     */
    public void launch(LivingEntity owner, @Nullable LivingEntity target, double speedFactor) {
        this.setOwner(owner);
        // 起飞时取一次参数快照：调 config 不会让半空中的蝴蝶突然改道
        this.pathParams = ButterflyPathParams.load();
        Vec3 dir = target == null
                ? owner.getLookAngle()
                : target.getEyePosition().subtract(this.position()).normalize();
        this.setDeltaMovement(dir.scale(this.pathParams.speed() * Mth.clamp(speedFactor, 0.5, 1.6)));
    }

    /** 兼容旧调用：默认速度。 */
    public void launch(LivingEntity owner, @Nullable LivingEntity target) {
        launch(owner, target, 1.0);
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide()) {
            // 纯服务端逻辑；客户端只等位置同步 + 看粒子
            return;
        }
        if (++this.lifeTicks > MAX_LIFE) {
            this.dissipate();
            return;
        }

        this.steer();
        Vec3 movement = this.getDeltaMovement();
        Vec3 from = this.position();
        this.setPos(from.x + movement.x, from.y + movement.y, from.z + movement.z);
        this.spawnTrail();

        // 撞墙 / 撞地：蝴蝶不会穿墙（原版 clip 用的是实体自己的包围箱）
        if (this.level().clip(new net.minecraft.world.level.ClipContext(
                from, this.position(),
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, this)).getType()
                != net.minecraft.world.phys.HitResult.Type.MISS) {
            this.impact(this.position());
            return;
        }

        LivingEntity victim = this.findVictim();
        if (victim != null) {
            this.impact(victim.getEyePosition().subtract(0.0, 0.25, 0.0));
            this.hurtVictim(victim);
        }
    }

    /** 追踪：朝目标的视线方向做一次「有限转角」修正，不会瞬间贴脸。 */
    private void steer() {
        LivingEntity target = this.resolveTarget();
        if (target == null) {
            return;
        }
        Vec3 desired = target.getEyePosition().subtract(this.position());
        if (desired.lengthSqr() < 1.0E-4) {
            return;
        }
        // 修正时保留各自的巡航速度（launch 里每只给的速度不一样，别在这里被归一成同一档）
        double speed = this.getDeltaMovement().length();
        if (speed < 1.0E-3) {
            speed = this.pathParams.speed();
        }
        Vec3 next = this.getDeltaMovement()
                .add(desired.normalize().scale(TURN))
                .normalize()
                .scale(speed);
        this.setDeltaMovement(next);
    }

    /**
     * 目标解析：主人（贝阿朵莉切）当前锁定的目标优先，否则找主人 40 格内最近的玩家。
     *
     * <p>这样「她被打断、目标死了」时蝴蝶会自然改追下一个人，而不是直线飞走。
     */
    @Nullable
    private LivingEntity resolveTarget() {
        if (!(this.getOwner() instanceof LivingEntity owner) || !owner.isAlive()) {
            return null;
        }
        if (owner instanceof net.minecraft.world.entity.Mob mob) {
            LivingEntity current = mob.getTarget();
            if (current != null && current.isAlive()) {
                return current;
            }
        }
        List<Player> players = this.level().getEntitiesOfClass(Player.class,
                owner.getBoundingBox().inflate(40.0),
                player -> player.isAlive() && !player.isSpectator() && !player.isCreative());
        Player nearest = null;
        double best = Double.MAX_VALUE;
        for (Player player : players) {
            double dist = player.distanceToSqr(owner);
            if (dist < best) {
                best = dist;
                nearest = player;
            }
        }
        return nearest;
    }

    /**
     * 命中判定：包围箱外扩一点，排除主人自己（蝴蝶自己不是 LivingEntity，不会被选中）。
     *
     * <p>还要排除<b>碑文的祭品</b>：黑山羊有 summonerUuid 好认，但「宾客」（僵尸/骷髅/女巫…）
     * 和黑山羊一样戴着 {@code SacrificeLedger.TAG_SACRIFICE} 标签、一样计入献祭数 ——
     * 实机日志里出现过她的蝴蝶把自家宾客打成 14/20，等于替玩家免费推进碑文。
     * 所以这里按标签统一排除，而不是只认黑山羊这一个类型。
     */
    @Nullable
    private LivingEntity findVictim() {
        AABB box = this.getBoundingBox().inflate(HIT_INFLATE);
        Entity owner = this.getOwner();
        List<LivingEntity> hits = this.level().getEntitiesOfClass(LivingEntity.class, box,
                entity -> entity.isAlive()
                        && !entity.isSpectator()
                        && entity != owner
                        && !entity.getTags().contains(SacrificeLedger.TAG_SACRIFICE));
        for (LivingEntity hit : hits) {
            // 兜底：没打标签、但确实是主人召出来的黑山羊（例如旧存档）也不打
            if (hit instanceof BlackGoatServantEntity goat && goat.getSummonerUuid() != null
                    && owner != null
                    && goat.getSummonerUuid().equals(owner.getUUID())) {
                continue;
            }
            return hit;
        }
        return null;
    }

    private void hurtVictim(LivingEntity victim) {
        Entity owner = this.getOwner();
        var source = owner == null
                ? this.damageSources().magic()
                : this.damageSources().indirectMagic(this, owner);
        // owner 在上面已经取过了，这里不要重复调用（那会把命中日志里的 owner 变成第二次查询的结果）
        float before = victim.getHealth();
        victim.hurt(source, DAMAGE);
        com.example.galboss.GalBoss.LOGGER.info("[Butterfly] hit {} -> {}/{} (was {}) owner={}",
                victim.getName().getString(), victim.getHealth(), victim.getMaxHealth(), before,
                owner == null ? "null" : owner.getName().getString());
        if (victim instanceof Player player && !player.isCreative()) {
            // 蝶粉糊脸：很短的失明，只做压迫感，不做控
            player.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                    net.minecraft.world.effect.MobEffects.BLINDNESS, 30, 0, false, false, false));
        }
    }

    /** 命中：一小群蝶炸开（不用 GoldenButterflyBurst 的全套，那只给近战重击用，贴脸会糊屏）。 */
    private void impact(Vec3 at) {
        if (this.level() instanceof ServerLevel server) {
            server.sendParticles(ModParticles.BURST_BUTTERFLY.get(),
                    at.x, at.y + 0.1, at.z, 4, 0.0, 0.0, 0.0, 0.0);
            server.sendParticles(ModParticles.BURST_DOT.get(),
                    at.x, at.y, at.z, 8, 0.25, 0.25, 0.25, 0.02);
            this.level().playSound(null, at.x, at.y, at.z,
                    net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_CHIME,
                    net.minecraft.sounds.SoundSource.HOSTILE, 0.9f, 1.6f);
        }
        this.discard();
    }

    /** 超时落空：只散一小把余晖，不炸。 */
    private void dissipate() {
        if (this.level() instanceof ServerLevel server) {
            server.sendParticles(ModParticles.BURST_DOT.get(),
                    this.getX(), this.getY(), this.getZ(), 6, 0.2, 0.2, 0.2, 0.01);
        }
        this.discard();
    }

    /** 飞行拖尾：每 6 tick 一只橙蝶，**落点沿飞行轴排成螺旋**（见 {@link #helixOffset()}）。 */
    private void spawnTrail() {
        if (!(this.level() instanceof ServerLevel server)) {
            return;
        }
        if (this.lifeTicks % Math.max(1, this.pathParams.spawnIntervalTicks()) != 0) {
            return;
        }
        Vec3 off = helixOffset();
        server.sendParticles(ModParticles.GOLDEN_BUTTERFLY.get(),
                this.getX() + off.x, this.getY() + off.y, this.getZ() + off.z,
                1, 0.04, 0.04, 0.04, 0.0);
        if (this.lifeTicks % 12 == 0) {
            server.sendParticles(ModParticles.BURST_DOT.get(),
                    this.getX(), this.getY(), this.getZ(), 1, 0.08, 0.08, 0.08, 0.0);
        }
        // 蝶身碎屑：撒在螺旋偏移的外侧，跟着蝶一起往前飘（不是撒在飞行体中心）
        if (this.lifeTicks % 3 == 0) {
            server.sendParticles(ModParticles.BURST_DOT.get(),
                    this.getX() + off.x * 1.6, this.getY() + off.y * 1.6, this.getZ() + off.z * 1.6,
                    1, 0.10, 0.10, 0.10, 0.01);
        }
    }

    /**
     * 这一次尾迹蝶相对飞行轴的螺旋偏移，即截图方程的 MC 版：
     * <pre>
     * offset(t) = S·R·cos(ωt) + U·R·squash·sin(ωt)
     * ω         = 2π · speed / pitch
     * </pre>
     * {@code S} 是水平侧向（{@code F × up}），{@code U} 是竖直（{@code S × F}），
     * {@code F} 是飞行轴本身。前进那一项（{@code v·t}）不在这里算——实体自己就在沿 {@code F} 走。
     *
     * <p>为什么"螺旋前进"要由发射物在落点上表达：{@code sendParticles} 的速度通道给的是
     * 「随机方向 × 速率」，客户端粒子拿不到飞行方向。所以每隔 {@code spawnIntervalTicks}
     * 让落点绕轴转一个相位，串起来就是一条绕飞行轴旋转的螺线。
     *
     * <p>相位用 {@code lifeTicks} 而不是累积量：速度变化时不会跳变，且天然可复现。
     */
    private Vec3 helixOffset() {
        if (!this.pathParams.enabled()) {
            return Vec3.ZERO;
        }
        Vec3 dir = this.getDeltaMovement();
        if (dir.lengthSqr() < 1.0E-6) {
            return Vec3.ZERO;
        }
        // 实际速度：同一批蝴蝶的 speedFactor 不同，各自转得快慢也就不同
        double speed = dir.length();
        dir = dir.normalize();
        // 与飞行方向垂直的一组基；垂直飞行时叉乘退化，退回 X 轴
        Vec3 side = dir.cross(new Vec3(0.0, 1.0, 0.0));
        if (side.lengthSqr() < 1.0E-4) {
            side = new Vec3(1.0, 0.0, 0.0);
        }
        side = side.normalize();
        Vec3 up = side.cross(dir).normalize();

        double phase = this.pathParams.spin()
                * this.pathParams.omegaRadPerTick(speed)
                * this.lifeTicks;
        double radius = this.pathParams.radius();
        return side.scale(Math.cos(phase) * radius)
                .add(up.scale(Math.sin(phase) * radius * this.pathParams.verticalSquash()));
    }
}
