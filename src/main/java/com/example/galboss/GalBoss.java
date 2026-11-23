package com.example.galboss;

import com.example.galboss.block.ModBlocks;
import com.example.galboss.config.GalBossConfig;
import com.example.galboss.config.GalBossMusicConfig;
import com.example.galboss.command.GalBossCommand;
import com.example.galboss.effect.ModEffects;
import com.example.galboss.entity.ModEntities;
import com.example.galboss.fx.FxReloadListener;
import com.example.galboss.item.ModCreativeTabs;
import com.example.galboss.item.ModItems;
import com.example.galboss.item.PetGiftTable;
import com.example.galboss.network.ModNetwork;
import com.example.galboss.particle.ModParticles;
import com.example.galboss.pet.PetTradeTable;
import com.example.galboss.sound.ModSounds;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(GalBoss.MOD_ID)
public class GalBoss {

    public static final String MOD_ID = "galboss";
    public static final Logger LOGGER = LogManager.getLogger();

    public GalBoss(FMLJavaModLoadingContext context) {
        IEventBus modEventBus = context.getModEventBus();

        // 整合包适配配置（boss 减伤 / 单次限伤），落在 config/galboss-common.toml
        context.registerConfig(ModConfig.Type.COMMON, GalBossConfig.SPEC);
        // 客户端 BGM 配置（开关 / 音量 / 黄金乡里的优先级），落在 config/galboss-client.toml。
        // 音乐是纯本地表现，所以不放进 COMMON：联机时每个玩家自己调，服务端不参与。
        context.registerConfig(ModConfig.Type.CLIENT, GalBossMusicConfig.SPEC);

        ModNetwork.register();

        // /galboss 调试命令：RegisterCommandsEvent 在 Forge 事件总线上。
        // 这里显式 addListener 而不是用 @EventBusSubscriber，避免依赖注解里 Dist 的默认值。
        MinecraftForge.EVENT_BUS.addListener(GalBossCommand::register);

        // 特效定义库：开服 / /reload 时重扫 assets/galboss/pixelfx + config/galboss/fx。
        // 这是编辑器「改完立刻生效」的服务端一半；客户端一半在 ClientSetup 里注册。
        MinecraftForge.EVENT_BUS.addListener((AddReloadListenerEvent event) ->
                event.addListener(new FxReloadListener()));

        ModEntities.register(modEventBus);
        // 方块先于物品注册：BlockItem 构造时要拿 ModBlocks 的方块
        ModBlocks.register(modEventBus);
        ModItems.register(modEventBus);
        ModEffects.register(modEventBus);
        ModParticles.register(modEventBus);
        ModCreativeTabs.register(modEventBus);
        // 两条 BGM（黄金乡主题 / 幻象协调者战斗曲），ogg 在 assets/galboss/sounds/music/
        ModSounds.register(modEventBus);

        MinecraftForge.EVENT_BUS.register(this);
        
        LOGGER.info("GalBoss initialized!");
    }

    /**
     * 配置加载 / 重载后让宠物数据表失效。
     *
     * <p>礼物表与货架是「第一次用到时解析」的懒加载，所以只要把缓存置空，
     * 下一次访问就会拿着新配置重新解析，不需要额外的重载逻辑。
     */
    @Mod.EventBusSubscriber(modid = MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ConfigReloadHandler {

        private ConfigReloadHandler() {
        }

        @SubscribeEvent
        public static void onConfigChanged(ModConfigEvent event) {
            if (!MOD_ID.equals(event.getConfig().getModId())) {
                return;
            }
            PetGiftTable.invalidate();
            PetTradeTable.invalidate();
            LOGGER.debug("GalBoss pet tables invalidated after config change.");
        }
    }
}
