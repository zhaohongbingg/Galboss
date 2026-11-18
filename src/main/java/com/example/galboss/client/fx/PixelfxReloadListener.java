package com.example.galboss.client.fx;

import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * 资源重载监听：F3+T 或换资源包之后，元数据 JSON 与贴图都可能换掉，
 * 必须让 {@link PixelfxAssets} 的缓存（以及「最近邻过滤已设过」的标记）失效，
 * 否则会一直用旧帧表 / 旧过滤状态。
 *
 * <p>清理动作放在 {@code apply}（游戏线程）里做，不碰任何 GL 调用。
 */
public final class PixelfxReloadListener extends SimplePreparableReloadListener<Void> {

    @Override
    protected Void prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        return null;
    }

    @Override
    protected void apply(Void unused, ResourceManager resourceManager, ProfilerFiller profiler) {
        PixelfxAssets.invalidate();
    }
}
