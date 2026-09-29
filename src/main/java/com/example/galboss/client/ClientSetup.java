package com.example.galboss.client;

import com.example.galboss.client.renderer.FsGuardRenderer;
import com.example.galboss.client.renderer.OnabutaIkukoRenderer;
import com.example.galboss.client.renderer.ReizenTohkaRenderer;
import com.example.galboss.client.renderer.TadasugawaReiRenderer;
import com.example.galboss.entity.ModEntities;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "galboss", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientSetup {

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.REIZEIN_TOHKA.get(), ReizenTohkaRenderer::new);
        event.registerEntityRenderer(ModEntities.ONABUTA_IKUKO.get(), OnabutaIkukoRenderer::new);
        event.registerEntityRenderer(ModEntities.TADASUGAWA_REI.get(), TadasugawaReiRenderer::new);
        event.registerEntityRenderer(ModEntities.FS_GUARD.get(), FsGuardRenderer::new);
    }
}
