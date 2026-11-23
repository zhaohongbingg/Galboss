package com.example.galboss.client.sound;

import com.example.galboss.GalBoss;
import com.example.galboss.config.GalBossMusicConfig;
import com.example.galboss.entity.AbstractFSBossEntity;
import com.example.galboss.entity.BeatriceEntity;
import com.example.galboss.ritual.GoldenLand;
import com.example.galboss.sound.GalBossMusicTrack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.event.TickEvent;

/**
 * 客户端 BGM 控制器：决定「现在该放哪首」，并负责把原版背景乐挡掉。
 *
 * <p><b>为什么不走网络包：</b>BGM 是纯客户端表现，服务端既不需要知道也不需要校验。
 * 判定条件全部是客户端本来就能看到的东西 —— 所在维度、身边有没有「已经开战」的 boss。
 * 好处是零带宽、零服务端状态，玩家中途重连 / 切维度 / boss 被清掉都能自己收敛回来。
 *
 * <p>三条规则，按优先级：
 * <ol>
 *   <li>身边有开战的 boss（{@link AbstractFSBossEntity} 的同步战斗标记，或任何
 *       {@link BeatriceEntity}）→ 战斗曲 {@code mirage_coordinator}</li>
 *   <li>人在 {@code galboss:golden_land} → 黄金乡主题 {@code golden_land}</li>
 *   <li>其它 → 什么都不放</li>
 * </ol>
 * 规则 1 和 2 同时成立时由 {@code music.battleOverridesGoldenLand} 决定谁赢（默认战斗曲赢）。
 *
 * <p><b>和原版音乐的关系：</b>原版 {@code MusicManager} 会在任意时刻随机起一首背景乐，
 * 和 boss 曲叠在一起会糊成一片。所以这里做两件事：开播前先 {@code stopPlaying()} 掐掉正在放的，
 * 再用 {@link PlaySoundEvent} 把「别人的 MUSIC 类音源」拦下来 —— 只拦 MUSIC，
 * 唱片（RECORDS）、环境音（AMBIENT）都不受影响。
 *
 * <p>判定每 {@value #CHECK_INTERVAL} tick 跑一次：切歌不需要 20Hz 的精度，
 * 而 {@code getEntitiesOfClass} 每 tick 扫一遍 128³ 的方块没必要。
 */
public final class GalBossMusicController {

    /** 判定节流：每 10 tick（0.5 秒）重新评估一次。 */
    private static final int CHECK_INTERVAL = 10;

    /**
     * 停播宽限：判定「该停了」之后再多等一会儿。
     *
     * <p>没有这个宽限，玩家在 boss 战里被拍死、复活点刚好在 64 格外时，音乐会先停再从头放一遍 ——
     * 听感上就是「打一半音乐重开了」。宽限期内只要目标曲目又变回当前曲目（复活走回来、
     * 目标短暂丢失），就当作没被打断，继续播放。
     */
    private static final int STOP_GRACE_TICKS = 60;

    /** 「身边有 boss」的半径，和 boss 的参战判定半径保持一致。 */
    private static final double BATTLE_RADIUS = 64.0;

    private static int tickCounter;

    /** 待停播的剩余宽限 tick；{@code <= 0} 表示没有待停的曲目。 */
    private static int pendingStopTicks;

    /** 上一 tick 看到的「音乐」滑条值，用来处理「从 0 调大」时实例已经在跑但没出声的情况。 */
    private static float lastMusicSlider = -1.0f;

    private static GalBossMusicTrack currentTrack;
    private static SoundInstance currentInstance;

    private GalBossMusicController() {
    }

    // ------------------------------------------------------------------ 事件入口

    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (++tickCounter < CHECK_INTERVAL) {
            return;
        }
        tickCounter = 0;
        evaluate();
    }

    /**
     * 自家 BGM 在响的时候，把别的 MUSIC 音源全部掐掉。
     *
     * <p>事件拿到的是「即将播放的实例」，置 {@code null} 就是不让它播。先放行自己的实例，
     * 否则第一次播放就会被自己拦下来。
     */
    public static void onPlaySound(PlaySoundEvent event) {
        if (currentInstance == null) {
            return;
        }
        SoundInstance sound = event.getSound();
        if (sound == null || sound == currentInstance) {
            return;
        }
        if (sound.getSource() == SoundSource.MUSIC) {
            // INFO 而不是 DEBUG：原版音乐本身几分钟才来一次，这条日志很稀疏，
            // 但排查「怎么突然有别的曲子在响」时是唯一线索。
            GalBoss.LOGGER.info("[Music] 拦下原版音乐 {}", sound.getLocation());
            event.setSound(null);
        }
    }

    // -------------------------------------------------------------------- 判定

    private static void evaluate() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            stop();
            lastMusicSlider = -1.0f;
            return;
        }

        // 「音乐」滑条原本是 0，现在被调大了：实例当年是以 0 音量创建的，引擎不会回头重算，
        // 只能重开一遍。滑条变小的情况引擎自己会处理（updateCategoryVolume）。
        float slider = mc.options.getSoundSourceVolume(SoundSource.MUSIC);
        if (currentTrack != null && lastMusicSlider <= 0.0f && slider > 0.0f) {
            switchTo(currentTrack);
        }
        lastMusicSlider = slider;

        GalBossMusicTrack wanted = resolve(player);
        if (wanted == currentTrack) {
            // 目标没变（含「刚才判定要停，现在又回来了」）：撤销待停，继续放
            pendingStopTicks = 0;
            return;
        }
        if (wanted == null && currentTrack != null) {
            if (pendingStopTicks <= 0) {
                pendingStopTicks = STOP_GRACE_TICKS;
            }
            pendingStopTicks -= CHECK_INTERVAL;
            if (pendingStopTicks > 0) {
                return;
            }
        }
        pendingStopTicks = 0;
        switchTo(wanted);
    }

    private static GalBossMusicTrack resolve(Player player) {
        if (!GalBossMusicConfig.ENABLED.get()) {
            return null;
        }
        boolean goldenLand = GoldenLand.DIMENSION.equals(player.level().dimension());
        boolean battle = isBattleFightNear(player);

        if (battle && (!goldenLand || GalBossMusicConfig.BATTLE_OVERRIDES_GOLDEN_LAND.get())) {
            return GalBossMusicTrack.BATTLE;
        }
        return goldenLand ? GalBossMusicTrack.GOLDEN_LAND : null;
    }

    /**
     * 附近有没有「正在打」的 boss。
     *
     * <p>三人组用同步过来的战斗标记：结构里待机（右键才开战）的 boss 不算，
     * 否则玩家在走廊里路过就会莫名其妙开始放战斗曲。贝阿朵莉切没有待机形态，
     * 她在场就是要打，直接按存在判定。
     */
    private static boolean isBattleFightNear(Player player) {
        AABB area = player.getBoundingBox().inflate(BATTLE_RADIUS);
        for (AbstractFSBossEntity boss : player.level().getEntitiesOfClass(AbstractFSBossEntity.class, area)) {
            if (boss.isAlive() && boss.isBattleMusicActive()) {
                return true;
            }
        }
        for (BeatriceEntity boss : player.level().getEntitiesOfClass(BeatriceEntity.class, area)) {
            if (boss.isAlive()) {
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------- 播放

    private static void switchTo(GalBossMusicTrack track) {
        stop();
        if (track == null) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        // 先把原版背景乐掐掉：不然玩家会听到两首曲子叠在一起
        mc.getMusicManager().stopPlaying();

        GalBossMusicInstance instance = new GalBossMusicInstance(
                track.event(), (float) GalBossMusicConfig.VOLUME.get().doubleValue());
        mc.getSoundManager().play(instance);

        currentTrack = track;
        currentInstance = instance;
        GalBoss.LOGGER.info("[Music] 开始播放 {}", track.id());
    }

    private static void stop() {
        pendingStopTicks = 0;
        if (currentInstance != null) {
            Minecraft.getInstance().getSoundManager().stop(currentInstance);
            currentInstance = null;
        }
        if (currentTrack != null) {
            GalBoss.LOGGER.info("[Music] 停止 {}", currentTrack.id());
            currentTrack = null;
        }
    }
}
