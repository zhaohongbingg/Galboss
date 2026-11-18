package com.example.galboss.client;

import com.example.galboss.client.screen.FsPetScreen;
import com.example.galboss.entity.FsPetEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.Entity;

/**
 * 客户端专有入口。
 *
 * <p>只允许被 {@code DistExecutor} 或 {@code S2CPetMessagePacket} 的 lambda 间接调用，
 * 这样专用服务器永远不会加载到这个类。
 */
public final class ClientPetHooks {

    private ClientPetHooks() {
    }

    public static void openPetScreen(FsPetEntity pet) {
        Minecraft.getInstance().setScreen(new FsPetScreen(pet.getId()));
    }

    public static void onPetMessage(int entityId, String langKey, String arg) {
        Minecraft minecraft = Minecraft.getInstance();
        Component text = arg.isEmpty()
                ? Component.translatable(langKey)
                : Component.translatable(langKey, arg);

        if (minecraft.screen instanceof FsPetScreen screen && screen.getPetId() == entityId) {
            screen.pushMessage(text);
            return;
        }
        if (minecraft.player != null) {
            Entity entity = minecraft.level == null ? null : minecraft.level.getEntity(entityId);
            MutableComponent prefix = entity != null
                    ? entity.getName().copy().append(Component.literal(": "))
                    : Component.empty();
            minecraft.player.displayClientMessage(prefix.append(text), true);
        }
    }
}
