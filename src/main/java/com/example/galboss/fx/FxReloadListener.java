package com.example.galboss.fx;

import com.google.gson.JsonObject;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.Map;

/**
 * 特效定义库的重载监听（<b>双端共用</b>）。
 *
 * <p>IO 全在 {@link #prepare}（后台线程）里做完，{@link #apply} 只在游戏线程做一次引用替换 ——
 * 和 {@code PixelfxReloadListener} 的分工一致。
 *
 * <p>注册点有两处：
 * <ul>
 *   <li>服务端：{@code AddReloadListenerEvent}（{@code /reload} 与开服时触发）；</li>
 *   <li>客户端：{@code RegisterClientReloadListenersEvent}（F3+T 与开游戏时触发）。</li>
 * </ul>
 * 单机下两边读的是同一批文件（服务端资源管理器也能看到 jar 内的 {@code assets/galboss/pixelfx/}），
 * 所以不需要额外的同步包。
 */
public final class FxReloadListener extends SimplePreparableReloadListener<Map<String, JsonObject>> {

    @Override
    protected Map<String, JsonObject> prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        profiler.push("galboss_fx_scan");
        try {
            return EffectLibrary.scan(resourceManager);
        } finally {
            profiler.pop();
        }
    }

    @Override
    protected void apply(Map<String, JsonObject> scanned, ResourceManager resourceManager,
                         ProfilerFiller profiler) {
        profiler.push("galboss_fx_publish");
        try {
            EffectLibrary.clearWarnings();
            EffectLibrary.publish(scanned);
        } finally {
            profiler.pop();
        }
    }
}
