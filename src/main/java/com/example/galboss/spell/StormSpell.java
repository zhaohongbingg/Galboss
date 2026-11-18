package com.example.galboss.spell;

import com.example.galboss.fx.PixelfxSlugs;
import com.example.galboss.fx.PixelfxFx;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;

/**
 * L3 雷云：天幕压下来，之后每隔一段时间在场上随机落雷。
 *
 * <p>它自己不直接造成伤害，伤害全部由它「召唤」的 {@link LightningStrikeSpell} 结算 ——
 * 这是这套设计里最重要的复用：雷云只是节奏，落雷才是判定。
 */
public class StormSpell extends Spell {

    /** 天幕寿命。 */
    private static final int DURATION = 160;
    /** 落雷间隔（tick）。 */
    private static final int STRIKE_INTERVAL = 30;
    /** 落雷点距离原点最大半径。 */
    private static final double FIELD_RADIUS = 24.0;
    /** 雷云期间的落雷伤害（比标准落雷低，靠数量）。 */
    private static final float STORM_DAMAGE = 6.0f;
    /** 雷云期间的预警时长（压缩到 10 tick）。 */
    private static final int STORM_TELEGRAPH = 10;

    private boolean curtainUp;

    public StormSpell(Level level, Vec3 origin, @Nullable LivingEntity caster) {
        super(level, origin, caster);
    }

    /** 在 {@code origin} 上方起一片雷云。 */
    public static void cast(Level level, Vec3 origin, @Nullable LivingEntity caster) {
        SpellEngine.cast(new StormSpell(level, origin, caster));
    }

    @Override
    protected void step() {
        if (!this.curtainUp) {
            this.curtainUp = true;
            // 天幕：epitaph_storm_sky（billboard，0.04 / 112×115，40 帧循环）挂在 40 格高。
            // 旧的 tohka_phase2_eclipse 是 0.02 / 147；同宽度换算 22.0 × (0.02×147)/(0.04×112) ≈ 14.4
            PixelfxFx.playLooping(this.level, this.origin.add(0.0, 40.0, 0.0),
                    PixelfxSlugs.EPITAPH_STORM_SKY, DURATION - 20, 14.5f, 0.0f);
            if (this.level instanceof ServerLevel server) {
                server.playSound(null, this.origin.x, this.origin.y, this.origin.z,
                        SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 1.2f, 0.6f);
            }
        }
        if (this.age % STRIKE_INTERVAL != 10) {
            return;
        }
        Vec3 point = this.pickStrikePoint();
        if (point != null) {
            SpellEngine.cast(new LightningStrikeSpell(this.level, point, this.caster,
                    STORM_DAMAGE, STORM_TELEGRAPH));
        }
    }

    /** 优先劈人（随机一个场内玩家脚下），没人就随机场地一点。 */
    @Nullable
    private Vec3 pickStrikePoint() {
        List<Player> players = SpellFx.players(this.level, this.origin, FIELD_RADIUS);
        if (!players.isEmpty()) {
            Player player = players.get(this.level.random.nextInt(players.size()));
            return player.position();
        }
        double angle = this.level.random.nextDouble() * Math.PI * 2.0;
        double radius = this.level.random.nextDouble() * FIELD_RADIUS;
        return this.origin.add(Math.cos(angle) * radius, 0.0, Math.sin(angle) * radius);
    }

    @Override
    public int durationTicks() {
        return DURATION;
    }

    @Override
    public String id() {
        return "storm";
    }
}
