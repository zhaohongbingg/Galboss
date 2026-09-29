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

    // 桐香「学生会召集」召唤的护卫
    public static final RegistryObject<EntityType<FsGuardEntity>> FS_GUARD =
            ENTITY_TYPES.register("fs_guard",
                    () -> EntityType.Builder.<FsGuardEntity>of(FsGuardEntity::new, MobCategory.MONSTER)
                            .sized(0.55f, 1.8f)
                            .clientTrackingRange(64)
                            .build(ResourceLocation.fromNamespaceAndPath(GalBoss.MOD_ID, "fs_guard").toString()));

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
            event.put(FS_GUARD.get(), FsGuardEntity.createAttributes().build());
        }
    }
}
