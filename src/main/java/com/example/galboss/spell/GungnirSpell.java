package com.example.galboss.spell;

import com.example.galboss.fx.PixelfxSlugs;
import com.example.galboss.fx.PixelfxFx;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Gungnir（奥丁的永恒之枪）：天空显形 → 沿一条 24 格直线贯穿。
 *
 * <p>整套武器里「最讲道理」的一招：地面的预警形状就是伤害形状（一条细长带），侧身一步即可躲开。
 * 它用来教玩家「看预警形状躲」，后面的锤落 / 雷雨都建立在这条认知上。
 *
 * <p>方向在**构造时**定死：预警画完之后枪不再转向，否则玩家躲了也白躲。
 */
public class GungnirSpell extends Spell {

    /** 显形 + 预警时长。 */
    private static final int WARN = 24;
    /** 结算那一拍（比显形晚几 tick，让「枪到」和「掉血」同步）。 */
    private static final int HIT_TICK = 30;
    /** 贯穿长度（格）。 */
    private static final double LENGTH = 24.0;
    /** 伤害。 */
    private static final float DAMAGE = 14.0f;
    /** 半宽（格）：判定带 2×1.5 宽。 */
    private static final double HALF_WIDTH = 1.5;

    private final Vec3 end;
    private final Set<UUID> hit = new HashSet<>();
    private boolean fired;
    private boolean resolved;

    public GungnirSpell(Level level, Vec3 origin, @Nullable LivingEntity caster, @Nullable LivingEntity target) {
        super(level, origin, caster);
        Vec3 dir = target == null
                ? (caster == null ? new Vec3(0, 0, 1) : caster.getLookAngle())
                : target.position().subtract(origin);
        Vec3 flat = new Vec3(dir.x, 0.0, dir.z);
        if (flat.lengthSqr() < 1.0E-4) {
            flat = new Vec3(0.0, 0.0, 1.0);
        }
        this.end = origin.add(flat.normalize().scale(LENGTH));
    }

    public static void cast(Level level, Vec3 origin, @Nullable LivingEntity caster, @Nullable LivingEntity target) {
        SpellEngine.cast(new GungnirSpell(level, origin, caster, target));
    }

    @Override
    protected void step() {
        if (this.age <= WARN) {
            if (this.age % 4 == 1) {
                // 两条平行线 = 判定带边界
                SpellFx.line(this.level, this.origin, this.end, 1.2, HALF_WIDTH);
            }
            return;
        }
        if (!this.fired) {
            this.fired = true;
            // 神枪显形：epitaph_gungnir = holy/divine-lance（现烘，21 帧 / 1.05s one-shot）。
            // 从「冰枪染金」换成真正的光枪：柳叶枪头 + 双翼枪镡 + 锥形枪身，0.30s 有白热帧与尖端星芒，
            // 尾部向尖端侵蚀消散。锚点在枪尖，所以读作「枪尖停在这里、枪身向后延伸」。
            // 缩放 1.5：枪身 202px → 0.035 × 1.5 × 202 ≈ 10.6 格长，配 24 格贯穿带正好。
            // 注意是 play（一次性），不是 playLooping —— 新素材是 one-shot，不再是循环件。
            PixelfxFx.play(this.level, this.origin.add(0.0, 3.0, 0.0),
                    PixelfxSlugs.EPITAPH_GUNGNIR, 1.5f, 0.0f);
            // 枪影坠落：epitaph_spear_fall（0.025 / 137×118）—— 显形之后那一下扎下来
            PixelfxFx.play(this.level, this.origin.add(0.0, 0.5, 0.0),
                    PixelfxSlugs.EPITAPH_SPEAR_FALL, 1.6f, 0.0f);
            // 沿线的连续冲击：epitaph_spear_impact 是 ground 锚点，正好读成「枪从地上一路犁过去」
            for (int i = 0; i < 6; i++) {
                Vec3 at = this.origin.lerp(this.end, i / 5.0).add(0.0, 0.05, 0.0);
                PixelfxFx.play(this.level, at, PixelfxSlugs.EPITAPH_SPEAR_IMPACT, 0.8f, 0.0f);
            }
            if (this.level instanceof ServerLevel server) {
                server.playSound(null, this.origin.x, this.origin.y, this.origin.z,
                        SoundEvents.TRIDENT_THROW, SoundSource.HOSTILE, 1.2f, 0.7f);
            }
            SpellFx.shake(this.level, this.origin, 48.0, 0.6f, 8);
        }
        if (this.age >= HIT_TICK && !this.resolved) {
            this.resolved = true;
            AABB box = this.lineBox();
            int hits = SpellFx.magicBox(this.level, box, DAMAGE, this.caster, this.hit, 0, 0, 1.2);
            com.example.galboss.GalBoss.LOGGER.debug("[Spell] gungnir hit {} target(s)", hits);
        }
    }

    /** 24×3 的判定带（y 从脚下一格到头上三格）。 */
    private AABB lineBox() {
        double minX = Math.min(this.origin.x, this.end.x) - HALF_WIDTH;
        double maxX = Math.max(this.origin.x, this.end.x) + HALF_WIDTH;
        double minZ = Math.min(this.origin.z, this.end.z) - HALF_WIDTH;
        double maxZ = Math.max(this.origin.z, this.end.z) + HALF_WIDTH;
        double minY = this.origin.y - 1.0;
        double maxY = this.origin.y + 3.0;
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Override
    public int durationTicks() {
        return HIT_TICK + 8;
    }

    @Override
    public String id() {
        return "gungnir";
    }
}
