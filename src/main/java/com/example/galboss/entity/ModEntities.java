package com.example.galboss.entity;

import com.example.galboss.GalBoss;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.*;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

public class ModEntities {

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, GalBoss.MOD_ID);

    // Boss 实体
    public static final RegistryObject<EntityType<ReizenTohkaEntity>> REIZEIN_TOHKA =
            ENTITY_TYPES.register("reizein_tohka",
                    () -> EntityType.Builder.<ReizenTohkaEntity>of(ReizenTohkaEntity::new, MobCategory.MONSTER)
                            .sized(0.6f, 1.95f)
                            .clientTrackingRange(64)
                            .build(ResourceLocation.fromNamespaceAndPath(GalBoss.MOD_ID, "reizein_tohka").toString()));

    public static final RegistryObject<EntityType<OnabutaIkukoEntity>> ONABUTA_IKUKO =
            ENTITY_TYPES.register("onabuta_ikuko",
                    () -> EntityType.Builder.<OnabutaIkukoEntity>of(OnabutaIkukoEntity::new, MobCategory.MONSTER)
                            .sized(0.6f, 1.95f)
                            .clientTrackingRange(64)
                            .build(ResourceLocation.fromNamespaceAndPath(GalBoss.MOD_ID, "onabuta_ikuko").toString()));

    public static final RegistryObject<EntityType<TadasugawaReiEntity>> TADASUGAWA_REI =
            ENTITY_TYPES.register("tadasugawa_rei",
                    () -> EntityType.Builder.<TadasugawaReiEntity>of(TadasugawaReiEntity::new, MobCategory.MONSTER)
                            .sized(0.6f, 1.95f)
                            .clientTrackingRange(64)
                            .build(ResourceLocation.fromNamespaceAndPath(GalBoss.MOD_ID, "tadasugawa_rei").toString()));

    // 贝阿朵莉切：独立可生成的近战怪物，不参与 FS Big Three 的联动与血条
    public static final RegistryObject<EntityType<BeatriceEntity>> BEATRICE =
            ENTITY_TYPES.register("beatrice",
                    () -> EntityType.Builder.<BeatriceEntity>of(BeatriceEntity::new, MobCategory.MONSTER)
                            .sized(0.7f, 2.0f)
                            .clientTrackingRange(64)
                            .build(ResourceLocation.fromNamespaceAndPath(GalBoss.MOD_ID, "beatrice").toString()));

    // 桐香「学生会召集」召唤的护卫
    public static final RegistryObject<EntityType<FsGuardEntity>> FS_GUARD =
            ENTITY_TYPES.register("fs_guard",
                    () -> EntityType.Builder.<FsGuardEntity>of(FsGuardEntity::new, MobCategory.MONSTER)
                            .sized(0.55f, 1.8f)
                            .clientTrackingRange(64)
                            .build(ResourceLocation.fromNamespaceAndPath(GalBoss.MOD_ID, "fs_guard").toString()));

    // 郁子「深渊召唤」召唤的黑山羊仆从：不自然生成，只给刷怪蛋做创造/调试用
    // 尺寸 0.9 × 3.0：v6 交付模型 43.184 单位 = 2.699 格（1:1），渲染器再乘 1.1115 顶到 3.0 格，
    // 碰撞箱必须跟着到 3.0，否则视觉与判定对不上（改一个忘另一个是最容易犯的错）。
    public static final RegistryObject<EntityType<BlackGoatServantEntity>> BLACK_GOAT_SERVANT =
            ENTITY_TYPES.register("black_goat_servant",
                    () -> EntityType.Builder.<BlackGoatServantEntity>of(BlackGoatServantEntity::new, MobCategory.MONSTER)
                            .sized(0.9f, 3.0f)
                            .clientTrackingRange(64)
                            .build(ResourceLocation.fromNamespaceAndPath(GalBoss.MOD_ID, "black_goat_servant").toString()));

    // 用 boss 掉落物召唤的宠物版本（好感度培养系统）
    public static final RegistryObject<EntityType<FsPetEntity>> FS_PET =
            ENTITY_TYPES.register("fs_pet",
                    () -> EntityType.Builder.<FsPetEntity>of(FsPetEntity::new, MobCategory.CREATURE)
                            .sized(0.45f, 1.0f)
                            .clientTrackingRange(64)
                            .build(ResourceLocation.fromNamespaceAndPath(GalBoss.MOD_ID, "fs_pet").toString()));

    // ------------------------------------------------ 炼狱七桩（贝阿朵莉切的七名仆从）
    //
    // 七个「精英怪」：同一套骨架与共用基类（AbstractPurgatoryStakeEntity），只有发型/发色不同。
    // 尺寸 0.7 × 2.0 与贝阿朵莉切一致：交付模型 45.33–45.54 单位（≈2.85 格）在渲染器里乘 0.7
    // 的 YSM height_scale 收到 ≈1.99 格 —— 渲染缩放与碰撞箱两处必须一起改（见 PurgatoryStakeRenderer）。
    // 七个的手里都持炼狱之柱（PurgatoryPillarLayer + GeoItem），不自然生成，刷怪蛋 / 命令召唤。

    public static final RegistryObject<EntityType<AsmodeusEntity>> ASMODEUS = stake("asmodeus", AsmodeusEntity::new);
    public static final RegistryObject<EntityType<BeelzebubEntity>> BEELZEBUB = stake("beelzebub", BeelzebubEntity::new);
    public static final RegistryObject<EntityType<BelphegorEntity>> BELPHEGOR = stake("belphegor", BelphegorEntity::new);
    public static final RegistryObject<EntityType<LeviathanEntity>> LEVIATHAN = stake("leviathan", LeviathanEntity::new);
    public static final RegistryObject<EntityType<LuciferEntity>> LUCIFER = stake("lucifer", LuciferEntity::new);
    public static final RegistryObject<EntityType<MammonEntity>> MAMMON = stake("mammon", MammonEntity::new);
    public static final RegistryObject<EntityType<SatanEntity>> SATAN = stake("satan", SatanEntity::new);

    /**
     * 七桩的注册模板：注册名只写一次，同时用作实体 id、geo/贴图文件名与语言的键。
     *
     * <p>如果哪天要给某一位单独改碰撞箱或分类，就把它从这条模板里拆出来单独写。
     */
    private static <T extends AbstractPurgatoryStakeEntity> RegistryObject<EntityType<T>> stake(
            String id, EntityType.EntityFactory<T> factory) {
        return ENTITY_TYPES.register(id,
                () -> EntityType.Builder.<T>of(factory, MobCategory.MONSTER)
                        .sized(0.7f, 2.0f)
                        .clientTrackingRange(64)
                        .build(ResourceLocation.fromNamespaceAndPath(GalBoss.MOD_ID, id).toString()));
    }

    /**
     * 贝阿朵莉切的「黄金蝶」远程法术（发射物）。
     *
     * <p>没有模型也没有贴图：客户端注册的是 {@code NoopRenderer}，外观完全由服务端每 tick
     * 发的 {@code galboss:golden_butterfly} 粒子构成。所以它不需要 geo / texture 资源，
     * 也不会出现在刷怪蛋之外的地方 —— {@code updateInterval(1)} 是必须的，
     * 默认 20 tick 同步一次会让蝴蝶在屏幕上瞬移。
     */
    public static final RegistryObject<EntityType<ButterflyMissileEntity>> BUTTERFLY_MISSILE =
            ENTITY_TYPES.register("butterfly_missile",
                    () -> EntityType.Builder.<ButterflyMissileEntity>of(
                                    ButterflyMissileEntity::new, MobCategory.MISC)
                            .sized(0.7f, 0.7f)
                            .clientTrackingRange(8)
                            .updateInterval(1)
                            .build(ResourceLocation.fromNamespaceAndPath(
                                    GalBoss.MOD_ID, "butterfly_missile").toString()));

    /**
     * 谢斯塔狙击术的「光箭」。
     *
     * <p>和黄金蝶一样没有模型：客户端是 {@code NoopRenderer}，外观 = 金色拖尾粒子 + 命中溅血。
     * {@code updateInterval(1)} 是必须的 —— 它 1.7 格/tick，按默认 20 tick 同步会直接瞬移。
     * 碰撞箱给 0.3 只是为了不出奇怪的可视化，实际判定在实体自己 tick 里按距离算。
     */
    public static final RegistryObject<EntityType<SniperArrowEntity>> SNIPER_ARROW =
            ENTITY_TYPES.register("sniper_arrow",
                    () -> EntityType.Builder.<SniperArrowEntity>of(SniperArrowEntity::new, MobCategory.MISC)
                            .sized(0.3f, 0.3f)
                            .clientTrackingRange(16)
                            .updateInterval(1)
                            .build(ResourceLocation.fromNamespaceAndPath(
                                    GalBoss.MOD_ID, "sniper_arrow").toString()));

    public static void register(IEventBus eventBus) {
        ENTITY_TYPES.register(eventBus);
    }

    @Mod.EventBusSubscriber(modid = GalBoss.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static class ModEvents {

        @SubscribeEvent
        public static void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
            event.put(REIZEIN_TOHKA.get(), ReizenTohkaEntity.createAttributes().build());
            event.put(ONABUTA_IKUKO.get(), OnabutaIkukoEntity.createAttributes().build());
            event.put(TADASUGAWA_REI.get(), TadasugawaReiEntity.createAttributes().build());
            event.put(BEATRICE.get(), BeatriceEntity.createAttributes().build());
            event.put(FS_GUARD.get(), FsGuardEntity.createAttributes().build());
            event.put(BLACK_GOAT_SERVANT.get(), BlackGoatServantEntity.createAttributes().build());
            event.put(FS_PET.get(), FsPetEntity.createAttributes().build());
            // 炼狱七桩共用同一套精英怪属性（要分化就单独给某一位写 builder）
            event.put(ASMODEUS.get(), AbstractPurgatoryStakeEntity.createStakeAttributes().build());
            event.put(BEELZEBUB.get(), AbstractPurgatoryStakeEntity.createStakeAttributes().build());
            event.put(BELPHEGOR.get(), AbstractPurgatoryStakeEntity.createStakeAttributes().build());
            event.put(LEVIATHAN.get(), AbstractPurgatoryStakeEntity.createStakeAttributes().build());
            event.put(LUCIFER.get(), AbstractPurgatoryStakeEntity.createStakeAttributes().build());
            event.put(MAMMON.get(), AbstractPurgatoryStakeEntity.createStakeAttributes().build());
            event.put(SATAN.get(), AbstractPurgatoryStakeEntity.createStakeAttributes().build());
        }
    }
}
