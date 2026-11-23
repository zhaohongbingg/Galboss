package com.example.galboss.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * 蝴蝶粒子公共基类：双频谐波振翅漂浮 + 速度惯性继承 + 逐帧拍翅。
 */
public abstract class AbstractButterflyParticle extends SingleQuadParticle {

    protected final ButterflySprite sprite;
    protected final int phaseOffset;

    /** 基准尺寸（用于叠加拍翅透视呼吸）。 */
    protected float baseQuadSize;

    /** 是否为带初速度的惯性尾迹模式（由弹幕抛射物生成）。 */
    private final boolean directionalDrift;
    private double driftAngle;
    private final double turnRate;
    private final double originX;
    private final double originY;
    private final double originZ;
    private final double orbitRadius;
    private final double orbitSpeed;
    private final double forwardSpeed;
    private final double riseSpeed;
    private final double orbitPhase;

    protected AbstractButterflyParticle(ClientLevel level, double x, double y, double z,
                                        double vx, double vy, double vz,
                                        int phaseOffset, ButterflySprite sprite) {
        super(level, x, y, z);
        this.sprite = sprite;
        this.setSize(0.2F, 0.2F);
        this.baseQuadSize = 0.22F + this.random.nextFloat() * 0.20F;
        this.quadSize = this.baseQuadSize;
        this.phaseOffset = phaseOffset;

        double speedSq = vx * vx + vy * vy + vz * vz;
        this.directionalDrift = speedSq > 1.0E-6;

        this.driftAngle = this.random.nextDouble() * Math.PI * 2.0;
        this.turnRate = (this.random.nextDouble() - 0.5) * 0.045;
        this.originX = x;
        this.originY = y;
        this.originZ = z;
        this.orbitRadius = 0.09 + this.random.nextDouble() * 0.09;
        this.orbitSpeed = 0.26 + this.random.nextDouble() * 0.14;
        this.forwardSpeed = 0.018 + this.random.nextDouble() * 0.014;
        this.riseSpeed = 0.004 + this.random.nextDouble() * 0.007;
        this.orbitPhase = this.random.nextDouble() * Math.PI * 2.0;

        this.lifetime = this.directionalDrift
                ? (22 + this.random.nextInt(16))
                : (80 + this.random.nextInt(110));

        this.gravity = 0.0F;
        this.friction = 1.0F;
        this.xd = vx;
        this.yd = vy;
        this.zd = vz;
        this.rCol = 1.0F;
        this.gCol = 1.0F;
        this.bCol = 1.0F;
        this.alpha = 0.0F;
    }

    @Override
    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        this.age++;
        if (this.age >= this.lifetime) {
            this.remove();
            return;
        }

        double flapPhase = (this.age / (double) Math.max(1, this.sprite.frameTicks()) + this.phaseOffset) * Math.PI;
        double flapLift = Math.sin(flapPhase) * 0.018;

        if (this.directionalDrift) {
            // 弹幕尾迹模式：沿传入速度惯性滑行并渐进减速，叠加小幅振翅横摆
            double orbit = this.orbitPhase + this.age * (this.orbitSpeed * 1.25);
            double flutterScale = 0.014;
            this.x += this.xd + Math.cos(orbit) * flutterScale;
            this.y += this.yd + flapLift * 0.8 + 0.003;
            this.z += this.zd + Math.sin(orbit) * flutterScale;
            this.xd *= 0.90;
            this.yd *= 0.90;
            this.zd *= 0.90;
        } else {
            // 环境/法阵游荡模式：低频曲线偏航 + 小半径盘旋 + 拍翅上下起伏
            this.driftAngle += this.turnRate * Math.sin(this.age * 0.06 + this.orbitPhase);
            double orbit = this.orbitPhase + this.age * this.orbitSpeed;
            double advance = this.forwardSpeed * this.age;
            this.x = this.originX + Math.cos(this.driftAngle) * advance + Math.cos(orbit) * this.orbitRadius;
            this.z = this.originZ + Math.sin(this.driftAngle) * advance + Math.sin(orbit) * this.orbitRadius;
            this.y = this.originY + this.riseSpeed * this.age
                    + Math.sin(this.age * 0.16 + this.orbitPhase) * 0.03 + flapLift;
        }
        this.setPos(this.x, this.y, this.z);

        // 随拍翅做轻微透视缩放呼吸，增强 2 帧精灵的立体感
        this.quadSize = this.baseQuadSize * (1.0F + 0.06F * (float) Math.sin(flapPhase));

        // 淡入 / 淡出
        float life = this.age / (float) this.lifetime;
        float fadeInEnd = this.directionalDrift ? 0.12F : 0.10F;
        float fadeOutStart = this.directionalDrift ? 0.65F : 0.82F;
        if (life < fadeInEnd) {
            this.alpha = life / fadeInEnd;
        } else if (life > fadeOutStart) {
            this.alpha = Mth.clamp((1.0F - life) / (1.0F - fadeOutStart), 0.0F, 1.0F);
        } else {
            this.alpha = 1.0F;
        }
    }

    @Override
    protected int getLightColor(float partialTick) {
        return LightTexture.FULL_BRIGHT;
    }

    @Override
    public float getQuadSize(float partialTick) {
        return this.quadSize;
    }

    // ------------------------------------------------------------ 供辉光从属粒子同步的包级访问器

    /**
     * {@code Particle.level} 是 protected，跨包时只能通过 {@code this} 或本类实例访问；
     * 辉光层要拿父级的 level，所以这里开一个包级出口。
     */
    ClientLevel clientLevel() {
        return this.level;
    }

    /** 同上：{@code Particle.lifetime} 也是 protected。 */
    int lifeTicks() {
        return this.lifetime;
    }

    double currentX() {
        return this.x;
    }

    double currentY() {
        return this.y;
    }

    double currentZ() {
        return this.z;
    }

    double prevX() {
        return this.xo;
    }

    double prevY() {
        return this.yo;
    }

    double prevZ() {
        return this.zo;
    }

    float currentAlpha() {
        return this.alpha;
    }

    float currentQuadSize() {
        return this.quadSize;
    }

    int currentAge() {
        return this.age;
    }

    int currentFrameIndex() {
        return frameIndex();
    }

    // ------------------------------------------------------------ 帧选择

    protected int frameIndex() {
        return Math.floorMod(this.age / this.sprite.frameTicks() + this.phaseOffset, this.sprite.frames());
    }

    protected float frameU0() {
        return (frameIndex() % this.sprite.cols()) * this.sprite.frameU();
    }

    protected float frameV0() {
        return (frameIndex() / this.sprite.cols()) * this.sprite.frameV();
    }

    @Override
    protected float getU0() {
        return frameU0() + this.sprite.insetU();
    }

    @Override
    protected float getU1() {
        return frameU0() + this.sprite.frameU() - this.sprite.insetU();
    }

    @Override
    protected float getV0() {
        return frameV0() + this.sprite.insetV();
    }

    @Override
    protected float getV1() {
        return frameV0() + this.sprite.frameV() - this.sprite.insetV();
    }

    protected static ParticleRenderType createRenderType(ResourceLocation texture, boolean additive) {
        return GalBossParticleRenderTypes.create(texture, additive);
    }
}
