package com.example.galboss.sound;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.RegistryObject;

/**
 * 客户端 BGM 的两条曲目。
 *
 * <p>枚举而不是字符串：控制器里只可能出现这两个值，写错就编译不过；
 * 以后要加第三首（比如「第三阶段」）也只在这里补一行。
 */
public enum GalBossMusicTrack {

    /** 进入黄金乡维度时循环播放。 */
    GOLDEN_LAND(ModSounds.MUSIC_GOLDEN_LAND),

    /** 场上有已经开战的 boss 时循环播放。 */
    BATTLE(ModSounds.MUSIC_MIRAGE_COORDINATOR);

    private final RegistryObject<SoundEvent> sound;

    GalBossMusicTrack(RegistryObject<SoundEvent> sound) {
        this.sound = sound;
    }

    public SoundEvent event() {
        return this.sound.get();
    }

    public ResourceLocation id() {
        return this.sound.getId();
    }
}
