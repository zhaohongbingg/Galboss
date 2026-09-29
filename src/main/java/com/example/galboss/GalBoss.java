package com.example.galboss;

import com.example.galboss.config.GalBossConfig;
import com.example.galboss.effect.ModEffects;
import com.example.galboss.entity.ModEntities;
import com.example.galboss.item.ModCreativeTabs;
import com.example.galboss.item.ModItems;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
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

        ModEntities.register(modEventBus);
        ModItems.register(modEventBus);
        ModEffects.register(modEventBus);
        ModCreativeTabs.register(modEventBus);

        MinecraftForge.EVENT_BUS.register(this);
        
        LOGGER.info("GalBoss initialized!");
    }
}
