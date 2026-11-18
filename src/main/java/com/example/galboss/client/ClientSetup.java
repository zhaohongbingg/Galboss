package com.example.galboss.client;

import com.example.galboss.client.fx.PixelfxReloadListener;
import com.example.galboss.client.fx.SniperBeamRenderer;
import com.example.galboss.client.fx.PixelfxRenderer;
import com.example.galboss.fx.FxReloadListener;
import com.example.galboss.client.gui.RedTruthOverlay;
import com.example.galboss.client.gui.SniperLockOverlay;
import com.example.galboss.client.particle.ButterflyBurstParticle;
import com.example.galboss.client.particle.ButterflyGlowParticle;
import com.example.galboss.client.particle.ButterflyParticle;
import com.example.galboss.client.particle.BloodParticle;
import com.example.galboss.client.particle.GoldenDotParticle;
import com.example.galboss.client.particle.GoldenRingParticle;
import com.example.galboss.client.particle.GoldenStarParticle;
import com.example.galboss.client.renderer.BeatriceRenderer;
import com.example.galboss.client.renderer.BlackGoatServantRenderer;
import com.example.galboss.client.renderer.FsGuardRenderer;
import com.example.galboss.client.renderer.FsPetRenderer;
import com.example.galboss.client.renderer.OnabutaIkukoRenderer;
import com.example.galboss.client.renderer.ReizenTohkaRenderer;
import com.example.galboss.client.renderer.TadasugawaReiRenderer;
import com.example.galboss.entity.ModEntities;
import com.example.galboss.particle.ModParticles;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RegisterParticleProvidersEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = "galboss", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientSetup {

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.REIZEIN_TOHKA.get(), ReizenTohkaRenderer::new);
        event.registerEntityRenderer(ModEntities.ONABUTA_IKUKO.get(), OnabutaIkukoRenderer::new);
        event.registerEntityRenderer(ModEntities.TADASUGAWA_REI.get(), TadasugawaReiRenderer::new);
        event.registerEntityRenderer(ModEntities.BEATRICE.get(), BeatriceRenderer::new);
        event.registerEntityRenderer(ModEntities.FS_GUARD.get(), FsGuardRenderer::new);
        event.registerEntityRenderer(ModEntities.BLACK_GOAT_SERVANT.get(), BlackGoatServantRenderer::new);
        event.registerEntityRenderer(ModEntities.FS_PET.get(), FsPetRenderer::new);
        // 黄金蝶：本体不画任何东西，外观完全由粒子构成（见 ButterflyMissileEntity）
        event.registerEntityRenderer(ModEntities.BUTTERFLY_MISSILE.get(), NoopRenderer::new);
        // 狙击光箭：同上，外观 = 金色拖尾 + 命中溅血
        event.registerEntityRenderer(ModEntities.SNIPER_ARROW.get(), NoopRenderer::new);
    }

    /**
     * 客户端初始化：把 pixelfx 的世界层渲染挂到 Forge 事件总线。
     *
     * <p>{@link RenderLevelStageEvent} 是 Forge 总线上的事件（不是 mod 总线），
     * 而 {@link PixelfxRenderer} 是纯客户端类，所以只能在这里注册，不能进 common 代码。
     */
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        MinecraftForge.EVENT_BUS.addListener(PixelfxRenderer::onRenderLevelStage);
        // 狙击瞄准线：世界空间的红色红外线（自己画四边形 + 关深度测试，所以不走粒子）
        MinecraftForge.EVENT_BUS.addListener(SniperBeamRenderer::onRenderLevelStage);
        // 红字：HUD 层的服务端文本，同样只能在这个客户端专用入口挂载
        MinecraftForge.EVENT_BUS.addListener(RedTruthOverlay::onRenderGui);
        // 「你被瞄准了」：准星附近的括号 + 倒计时
        MinecraftForge.EVENT_BUS.addListener(SniperLockOverlay::onRenderGui);
        // 屏震：改相机角度（落雷 / 锤击 / 巨物坠地）
        MinecraftForge.EVENT_BUS.addListener(ScreenShake::onCamera);
    }

    /**
     * 注册客户端粒子工厂：拍翅蝴蝶（{@code galboss:golden_butterfly} / {@code ..._glow}）。
     *
     * <p>服务端只发粒子包（类型 ID + 位置/速度），真正长什么样、怎么动、怎么发光
     * 全在 {@link ButterflyParticle} 里。素材已换成 ER 预览表的 {@code butterfly_orange_flap}
     * （2 帧拍翅），见 {@code Butterflies.ORANGE_BODY}；想换回金色镂空蝶只改那个 sprite。
     *
     * <p>粒子 ID 仍叫 {@code golden_butterfly}：它是服务端发包用的线格式（实体、命令都在用），
     * 换美术不动 ID，避免存档/命令失效。
     */
    @SubscribeEvent
    public static void registerParticleProviders(RegisterParticleProvidersEvent event) {
        // registerSpecial = 非 JSON 粒子：不经过粒子图集，自己 setShaderTexture + 自定义渲染类型
        event.registerSpecial(ModParticles.GOLDEN_BUTTERFLY.get(),
                new ButterflyParticle.Provider());
        // 辉光层：加色混合（本体粒子创建时会自动在同位置挂一只）
        event.registerSpecial(ModParticles.GOLDEN_BUTTERFLY_GLOW.get(),
                new ButterflyGlowParticle.Provider());

        // 「金色蝴蝶攻击」爆发特效（设计图 4 阶段）
        event.registerSpecial(ModParticles.BURST_BUTTERFLY.get(),
                new ButterflyBurstParticle.Provider());
        event.registerSpecial(ModParticles.BURST_STAR.get(),
                new GoldenStarParticle.Provider());
        event.registerSpecial(ModParticles.BURST_RING.get(),
                new GoldenRingParticle.Provider());
        event.registerSpecial(ModParticles.BURST_DOT.get(),
                new GoldenDotParticle.Provider());

        // 狙击命中溅血（ER 粒子表的 blood_drop / blood_splash）
        event.registerSpecial(ModParticles.BLOOD_DROP.get(),
                new BloodParticle.DropProvider());
        event.registerSpecial(ModParticles.BLOOD_SPLASH.get(),
                new BloodParticle.SplashProvider());
    }

    /**
     * 注册客户端资源重载监听：资源包变动后 pixelfx 的帧表缓存与贴图过滤标记要跟着失效。
     */
    @SubscribeEvent
    public static void registerReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new PixelfxReloadListener());
        // 特效定义库（EffectLibrary）的客户端一半：F3+T / 换资源包后重新扫描并发布。
        // 单机下服务端那份读的是同一批文件，不需要同步包。
        event.registerReloadListener(new FxReloadListener());
    }
}
