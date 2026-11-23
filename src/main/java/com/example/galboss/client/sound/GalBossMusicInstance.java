package com.example.galboss.client.sound;

import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * 一条循环播放的音乐实例。
 *
 * <p>原版的 {@code SimpleSoundInstance.forMusic} 是给「放一次就完」的曲子准备的：它不循环，
 * 用脏了就得自己重放。这里直接继承 {@link AbstractSoundInstance} 并打开循环，
 * 由声音引擎自己处理流式 ogg 的循环点（{@code delay == 0} 时引擎走自动循环分支）。
 *
 * <p>三点设置值得记一下：
 * <ul>
 *   <li>{@link SoundSource#MUSIC} —— 跟随原版「音乐」滑条，玩家在选项里能单独调</li>
 *   <li>{@code relative = true} —— 不做世界内定位，玩家走到哪都在耳边</li>
 *   <li>{@link SoundInstance.Attenuation#NONE} —— 不按距离衰减，否则跑远就没声了</li>
 * </ul>
 */
public final class GalBossMusicInstance extends AbstractSoundInstance {

    public GalBossMusicInstance(SoundEvent event, float volume) {
        super(event, SoundSource.MUSIC, SoundInstance.createUnseededRandom());
        this.looping = true;
        this.relative = true;
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.volume = volume;
        this.pitch = 1.0f;
        this.x = 0.0;
        this.y = 0.0;
        this.z = 0.0;
    }
}
