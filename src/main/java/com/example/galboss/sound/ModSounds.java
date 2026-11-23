package com.example.galboss.sound;

import com.example.galboss.GalBoss;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 本模组自带的音乐事件。
 *
 * <p>两条曲目都在 {@code assets/galboss/sounds/music/} 下，并且 {@code sounds.json} 里标了
 * {@code stream: true} —— 一百多秒的 BGM 必须走流式解码，整段读进内存会在老机器上卡一下。
 * 注册名里的点（{@code music.golden_land}）不是笔误：原版音乐全部走 {@code music.*}
 * 这个命名习惯，这里保持一致。
 *
 * <p>实际播放由客户端的 {@code GalBossMusicController} 负责（服务端不发包、不占带宽），
 * 这里只负责把 {@link SoundEvent} 注册进注册表，让资源包里的 ogg 能被解析到。
 */
public final class ModSounds {

    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, GalBoss.MOD_ID);

    /** 黄金乡主题：Happiness_of_Marionette.ogg。 */
    public static final RegistryObject<SoundEvent> MUSIC_GOLDEN_LAND =
            register("music.golden_land");

    /** Boss 战主题：Miragecoordinator.ogg。 */
    public static final RegistryObject<SoundEvent> MUSIC_MIRAGE_COORDINATOR =
            register("music.mirage_coordinator");

    private static RegistryObject<SoundEvent> register(String name) {
        // 变长距离：BGM 只是给本机听的，音源位置不参与衰减计算（见客户端实例的 Attenuation.NONE）
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(
                ResourceLocation.fromNamespaceAndPath(GalBoss.MOD_ID, name)));
    }

    public static void register(IEventBus eventBus) {
        SOUNDS.register(eventBus);
    }

    private ModSounds() {
    }
}
