package com.example.galboss.spell;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * 服务端法术演出与判定基类。
 */
public abstract class Spell {

    protected final Level level;
    protected final Vec3 origin;
    @Nullable
    protected final LivingEntity caster;
    protected int age;

    private boolean finished;

    protected Spell(Level level, Vec3 origin, @Nullable LivingEntity caster) {
        this.level = level;
        this.origin = origin;
        this.caster = caster;
    }

    public final void tick() {
        if (this.finished) {
            return;
        }
        this.age++;
        this.step();
        if (this.age >= this.durationTicks()) {
            this.finished = true;
        }
    }

    protected abstract void step();

    public abstract int durationTicks();

    public abstract String id();

    public boolean isFinished() {
        return this.finished;
    }

    public int getAge() {
        return this.age;
    }

    protected String casterName() {
        return this.caster == null ? "none" : this.caster.getName().getString();
    }

    /**
     * 将水平方向向量转换为 Pixelfx 地面贴合特效的偏航角（图像上边 = -Z 对齐方向向量）。
     */
    protected static float directionToFxYaw(Vec3 dir) {
        double lenSq = dir.x * dir.x + dir.z * dir.z;
        if (lenSq < 1.0E-6) {
            return 0.0f;
        }
        return (float) Math.toDegrees(Math.atan2(dir.x, -dir.z));
    }
}
