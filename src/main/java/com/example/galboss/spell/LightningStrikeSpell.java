package com.example.galboss.spell;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * L1 落雷：地面圈预警 → 雷柱落下 → 半径内魔法伤害 + 短暂麻痹。
 *
 * <p>这是整套闪电的基础件：{@link StormSpell} 里那一片随机落雷就是它（缩短预警、降低伤害）。
 * 任何会施法的实体都能直接 {@code LightningStrikeSpell.cast(level, pos, caster)}。
 */
public class LightningStrikeSpell extends Spell {

    /** 默认预警时长（16 tick = 0.8s，够反应）。 */
    public static final int TELEGRAPH = 16;
    /** 默认伤害。 */
    public static final float DAMAGE = 9.0f;
    /** 命中半径。 */
    public static final double RADIUS = 2.2;

    private final Vec3 at;
    private final float damage;
    private final int telegraphTicks;
    private final Set<UUID> hit = new HashSet<>();
    private boolean struck;

    public LightningStrikeSpell(Level level, Vec3 at, @Nullable LivingEntity caster,
                                float damage, int telegraphTicks) {
        super(level, at, caster);
        this.at = at;
        this.damage = damage;
        this.telegraphTicks = Math.max(6, telegraphTicks);
    }

    /** 标准落雷（0.8s 预警 / 9 点）。 */
    public static void cast(Level level, Vec3 at, @Nullable LivingEntity caster) {
        SpellEngine.cast(new LightningStrikeSpell(level, at, caster, DAMAGE, TELEGRAPH));
    }

    @Override
    protected void step() {
        if (this.age <= this.telegraphTicks) {
            // 外圈固定、内圈收缩：读得出来「还有多久落」
            double progress = this.age / (double) this.telegraphTicks;
            if (this.age % 4 == 1) {
                SpellFx.circle(this.level, this.at, RADIUS, SpellFx.SPARK);
            }
            if (this.age % 2 == 0) {
                SpellFx.circle(this.level, this.at, RADIUS * (1.0 - progress), SpellFx.SPARK);
            }
            return;
        }
        if (this.struck) {
            int afterImpact = this.age - this.telegraphTicks - 1;
            if (afterImpact == 2 || afterImpact == 4) {
                // 余电从雷柱向命中边界扩散；不重复伤害结算。
                SpellFx.circle(this.level, this.at, RADIUS * afterImpact / 4.0,
                        afterImpact == 2 ? SpellFx.SPARK : SpellFx.GOLD);
            }
            return;
        }
        this.struck = true;
        // scale 1.0 = epitaph_lightning_bolt 的原生尺寸（0.025 × 231 ≈ 5.8 格宽 / 6.6 格高），
        // 与旧的 tohka_verdict_smite@1.6 视觉大小基本一致；换算见 SpellFx#boltVisual
        SpellFx.boltVisual(this.level, this.at, 1.0f);
        int hits = SpellFx.magic(this.level, this.at, RADIUS, this.damage, this.caster, this.hit, 8, 2, 0.0);
        com.example.galboss.GalBoss.LOGGER.debug("[Spell] lightning_strike hit {} target(s)", hits);
    }

    @Override
    public int durationTicks() {
        return this.telegraphTicks + 6;
    }

    @Override
    public String id() {
        return "lightning_strike";
    }
}
