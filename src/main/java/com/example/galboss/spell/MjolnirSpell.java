package com.example.galboss.spell;

import com.example.galboss.fx.PixelfxSlugs;
import com.example.galboss.fx.PixelfxFx;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Mjolnir（索尔的雷霆之锤）：预警圈 → 锤影砸落 → 内外双环伤害。
 *
 * <p>落点在**构造时**定死（= 施法那一刻目标站的位置），预警期间不会再跟人走：
 * 跑动必躲、站桩必吃。这条规则写进注释，避免以后有人「顺手改成追踪」把它的手感毁掉。
 */
public class MjolnirSpell extends Spell {

    /** 预警时长。 */
    private static final int WARN = 24;
    /** 内圈半径 / 伤害。 */
    private static final double INNER_RADIUS = 6.0;
    private static final float INNER_DAMAGE = 10.0f;
    /** 外圈半径 / 伤害。 */
    private static final double OUTER_RADIUS = 12.0;
    private static final float OUTER_DAMAGE = 5.0f;

    private final Vec3 at;
    /**
     * 命中记录：内圈和外圈**共用同一个集合**。
     *
     * <p>内圈是外圈的子集，如果两边各用一个集合，站内圈的人会先吃 10 点再吃 5 点
     * （实测就是这么发现的）。共用集合后，内圈命中的人在外圈结算时会被跳过。
     */
    private final Set<UUID> hit = new HashSet<>();
    private boolean landed;

    public MjolnirSpell(Level level, Vec3 at, @Nullable LivingEntity caster) {
        super(level, at, caster);
        this.at = at;
    }

    public static void cast(Level level, Vec3 at, @Nullable LivingEntity caster) {
        SpellEngine.cast(new MjolnirSpell(level, at, caster));
    }

    @Override
    protected void step() {
        if (this.age <= WARN) {
            if (this.age % 4 == 1) {
                SpellFx.circle(this.level, this.at, INNER_RADIUS, SpellFx.GOLD);
                SpellFx.circle(this.level, this.at, OUTER_RADIUS, SpellFx.SPARK);
                SpellFx.cross(this.level, this.at, INNER_RADIUS);
            }
            return;
        }
        if (this.landed) {
            return;
        }
        this.landed = true;
        // 锤影砸落：epitaph_mjolnir = 方头战锤（现烘自 impacts/hammer-smash v2，27 帧 / 1.3s）。
        // v2 针对 MC 场景重做过：修掉首帧锤柄被画布上沿裁切（改成蓄力微抬 + 侧向挥砸弧线），
        // 加 0.10~0.25s 的速度拖尾楔补 20fps 的帧间空隙，锤头加宽到 64×40 并加 1px 深色外描边
        // （明亮地形下也认得出轮廓），命中加挤压顿帧 + 锤面电弧，收尾用像素侵蚀消散而不是淡出。
        // 画布 220×254，锚点在锤头底面（y = 0），蓄力位 y ≈ -156 —— 所以落点就是地面，不用抬高。
        // 缩放 1.4：锤头 64px → 0.04 × 1.4 × 64 ≈ 3.6 格宽；冲击环 r=104px → 5.8 格，和 6 格内圈判定对齐；
        // 挥砸弧线 156px → 8.7 格，读得出「从头顶抡下来」。
        PixelfxFx.play(this.level, this.at.add(0.0, 0.05, 0.0), PixelfxSlugs.EPITAPH_MJOLNIR, 1.4f, 0.0f);
        // 落地冲击环保留 tohka_bell_wave（0.021 / 248，本来就是专用地面冲击件）
        PixelfxFx.play(this.level, this.at.add(0.0, 0.05, 0.0), PixelfxSlugs.TOHKA_BELL_WAVE, 2.2f, 0.0f);
        if (this.level instanceof ServerLevel server) {
            server.playSound(null, this.at.x, this.at.y, this.at.z, SoundEvents.ANVIL_LAND,
                    SoundSource.HOSTILE, 1.4f, 0.5f);
            server.playSound(null, this.at.x, this.at.y, this.at.z, SoundEvents.LIGHTNING_BOLT_IMPACT,
                    SoundSource.HOSTILE, 1.0f, 0.8f);
        }
        SpellFx.shake(this.level, this.at, 56.0, 0.6f, 10);

        int inner = SpellFx.magic(this.level, this.at, INNER_RADIUS, INNER_DAMAGE,
                this.caster, this.hit, 40, 1, 0.4);
        // 外圈复用同一个 hit 集合：内圈吃过的不会再吃一遍
        int outer = SpellFx.magic(this.level, this.at, OUTER_RADIUS, OUTER_DAMAGE,
                this.caster, this.hit, 0, 0, 0.2);
        com.example.galboss.GalBoss.LOGGER.debug("[Spell] mjolnir inner={} outer={}", inner, outer);
    }

    @Override
    public int durationTicks() {
        return WARN + 10;
    }

    @Override
    public String id() {
        return "mjolnir";
    }
}
