package com.example.galboss.particle;

import com.example.galboss.GalBoss;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 粒子类型注册表。
 *
 * <p>拍翅蝴蝶（{@code galboss:golden_butterfly}，素材现为 ER 橙蝶 2 帧）。
 * 粒子的「长什么样」全部在客户端 {@code client.particle.ButterflyParticle} 里实现，
 * 这里只负责注册一个类型 ID —— 服务端命令（/galboss butterfly）和实体都能用它发粒子包。
 *
 * <p>注意：ID 里的 {@code golden} 是历史命名（最初是金色镂空蝶），换美术时没有改 ID，
 * 因为它是服务端发粒子包用的线格式，实体/命令/存档都在引用它。
 *
 * <p>用 {@link SimpleParticleType}（无额外数据）即可：蝴蝶的位置/速度由粒子包携带，
 * 不需要每颗粒子传自定义参数。
 */
public final class ModParticles {

    public static final DeferredRegister<ParticleType<?>> PARTICLES =
            DeferredRegister.create(ForgeRegistries.PARTICLE_TYPES, GalBoss.MOD_ID);

    /** 金色镂空蝴蝶（本体层：锐利轮廓 + 镂空孔，普通 alpha 混合）。 */
    public static final RegistryObject<SimpleParticleType> GOLDEN_BUTTERFLY =
            PARTICLES.register("golden_butterfly", () -> new SimpleParticleType(false));

    /**
     * 金色镂空蝴蝶（辉光层：加色混合，画出发光轮廓）。
     *
     * <p>本体粒子创建时会自动在同位置再挂一只这只（见 {@code ButterflyParticle.Provider}），
     * 所以调用方只需要发本体粒子，不用管辉光。
     */
    public static final RegistryObject<SimpleParticleType> GOLDEN_BUTTERFLY_GLOW =
            PARTICLES.register("golden_butterfly_glow", () -> new SimpleParticleType(false));

    // ------------------------------------------------------------------ 「金色蝴蝶攻击」爆发特效
    // 对应设计图 4 阶段：命中闪光 → 蝴蝶飞出 → 盘旋 → 消散。由 fx/GoldenButterflyBurst 成组发送。

    /** 爆出的 4 帧蝴蝶（20 tick 螺旋飞出）。 */
    public static final RegistryObject<SimpleParticleType> BURST_BUTTERFLY =
            PARTICLES.register("burst_butterfly", () -> new SimpleParticleType(false));

    /** 命中瞬间的星芒。 */
    public static final RegistryObject<SimpleParticleType> BURST_STAR =
            PARTICLES.register("burst_star", () -> new SimpleParticleType(false));

    /** 命中瞬间的扩张光环。 */
    public static final RegistryObject<SimpleParticleType> BURST_RING =
            PARTICLES.register("burst_ring", () -> new SimpleParticleType(false));

    /** 余晖光点。 */
    public static final RegistryObject<SimpleParticleType> BURST_DOT =
            PARTICLES.register("burst_dot", () -> new SimpleParticleType(false));

    // ------------------------------------------------------------------ 血（狙击命中）

    /** 血珠（ER 粒子表 {@code blood_drop}）：小、受重力、落地即逝。 */
    public static final RegistryObject<SimpleParticleType> BLOOD_DROP =
            PARTICLES.register("blood_drop", () -> new SimpleParticleType(false));

    /** 血花（ER 粒子表 {@code blood_splash}）：命中瞬间喷出的那一团。 */
    public static final RegistryObject<SimpleParticleType> BLOOD_SPLASH =
            PARTICLES.register("blood_splash", () -> new SimpleParticleType(false));

    private ModParticles() {
    }

    public static void register(IEventBus eventBus) {
        PARTICLES.register(eventBus);
    }
}
