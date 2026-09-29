package com.example.galboss.item;

import com.example.galboss.GalBoss;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * 本模组自己的创造模式物品栏：刷怪蛋 + 终局奖励物品。
 */
public class ModCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, GalBoss.MOD_ID);

    public static final RegistryObject<CreativeModeTab> FS_BIG_THREE = CREATIVE_MODE_TABS.register(
            "fs_big_three",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.galboss.fs_big_three"))
                    .icon(() -> new ItemStack(ModItems.FS_BADGE.get()))
                    .displayItems((parameters, output) -> {
                        // 刷怪蛋
                        output.accept(ModItems.REIZEIN_TOHKA_SPAWN_EGG.get());
                        output.accept(ModItems.TADASUGAWA_REI_SPAWN_EGG.get());
                        output.accept(ModItems.ONABUTA_IKUKO_SPAWN_EGG.get());
                        // 终局奖励
                        output.accept(ModItems.TOHKA_BADGE.get());
                        output.accept(ModItems.REI_ARMBAND.get());
                        output.accept(ModItems.IKUKO_MEDAL.get());
                        output.accept(ModItems.FS_BIG_THREE_CORE.get());
                        output.accept(ModItems.FS_BADGE.get());
                    })
                    .build());

    public static void register(IEventBus eventBus) {
        CREATIVE_MODE_TABS.register(eventBus);
    }

    /**
     * 把三个刷怪蛋同时挂进原版的「生成蛋」页签。
     *
     * 自建页签在创造模式里排在原版页签后面，很容易被当成「没生效」，
     * 挂一份到原版页签就顺手解决了。
     */
    @Mod.EventBusSubscriber(modid = GalBoss.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class TabContents {

        private TabContents() {
        }

        @SubscribeEvent
        public static void onBuildContents(BuildCreativeModeTabContentsEvent event) {
            // 生成蛋进原版「生成蛋」页签
            if (CreativeModeTabs.SPAWN_EGGS.equals(event.getTabKey())) {
                event.accept(ModItems.REIZEIN_TOHKA_SPAWN_EGG.get());
                event.accept(ModItems.TADASUGAWA_REI_SPAWN_EGG.get());
                event.accept(ModItems.ONABUTA_IKUKO_SPAWN_EGG.get());
                return;
            }
            // 手持武器进「战斗」页签（它们也是实际物品，顺便方便查看模型）
            if (CreativeModeTabs.COMBAT.equals(event.getTabKey())) {
                event.accept(ModItems.IKUKO_KATANA.get());
                event.accept(ModItems.REI_GUN.get());
            }
        }
    }
}
