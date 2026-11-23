package com.example.galboss.client.fx;

import com.example.galboss.GalBoss;
import com.example.galboss.fx.EffectLibrary;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.GsonHelper;

import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * pixelfx 资源访问层：元数据 JSON 解析 + 贴图直接加载（不进粒子图集）。
 *
 * <p>三个「只记一次」：
 * <ul>
 *   <li>元数据缺失 / JSON 坏掉 → 缓存成 {@link Optional#empty()}，之后每次直接跳过，日志只打一次；</li>
 *   <li>贴图缺失 → 同上，绝不把缺失贴图塞进 {@link net.minecraft.client.renderer.texture.TextureManager}，
 *       否则它自己会每帧刷 MissingTexture 错误；</li>
 *   <li>贴图过滤（最近邻、关 mipmap）每张贴图只设一次，资源重载后由 {@link PixelfxReloadListener} 清掉重设。</li>
 * </ul>
 *
 * <p>这是纯客户端类，只能被 {@code client} 包 / {@link PixelfxClientHooks} 触达。
 */
public final class PixelfxAssets {

    /** 与 modid 一致：资源路径固定 assets/galboss/pixelfx/<slug>.json。 */
    private static final String NAMESPACE = GalBoss.MOD_ID;

    /** slug -> 元数据；Optional.empty() 表示「查过了，没有 / 坏了」，避免反复 IO 与刷日志。 */
    private static final Map<String, Optional<PixelfxMetadata>> METADATA_CACHE = new HashMap<>();

    /** 已经设过最近邻过滤的贴图。 */
    private static final Set<ResourceLocation> FILTERED_TEXTURES = new HashSet<>();

    /**
     * 已经确认缺失的贴图。
     *
     * <p>{@link #bindTexture} 每帧、每个特效实例、每一趟都会问一次贴图在不在，
     * 所以「查过一次没有」必须记下来，别让资源查找（会走一遍资源包链）留在高频路径上。
     */
    private static final Set<ResourceLocation> MISSING_TEXTURES = new HashSet<>();

    /** 已经警告过的键（元数据 / 贴图 / 渲染异常）。 */
    private static final Set<String> WARNED = new HashSet<>();

    private PixelfxAssets() {
    }

    /** 资源重载：元数据与贴图都可能换掉，全部重新解析。 */
    public static void invalidate() {
        METADATA_CACHE.clear();
        FILTERED_TEXTURES.clear();
        MISSING_TEXTURES.clear();
        // WARNED 一并清掉：重载后如果还缺，重新提醒一次是合理的
        WARNED.clear();
    }

    /** 取元数据；缺失或损坏时返回 {@link Optional#empty()}（只警告一次）。 */
    public static Optional<PixelfxMetadata> metadata(String slug) {
        if (slug == null || slug.isEmpty()) {
            return Optional.empty();
        }
        Optional<PixelfxMetadata> cached = METADATA_CACHE.get(slug);
        if (cached != null) {
            return cached;
        }
        Optional<PixelfxMetadata> loaded = loadMetadata(slug);
        METADATA_CACHE.put(slug, loaded);
        return loaded;
    }

    private static Optional<PixelfxMetadata> loadMetadata(String slug) {
        Minecraft minecraft = Minecraft.getInstance();
        ResourceManager manager = minecraft.getResourceManager();
        if (manager == null) {
            return Optional.empty();
        }
        // 优先用 EffectLibrary 的合并结果：它已经把 assets/galboss/pixelfx 与
        // config/galboss/fx 的覆盖合并过，编辑器调参走的就是这条路。
        // 库尚未加载时（启动极早期 / 扫描失败）才回退到直接读资源，保证不会白屏。
        Optional<JsonObject> merged = EffectLibrary.definition(slug);
        if (merged.isPresent()) {
            return parse(slug, merged.get());
        }
        ResourceLocation jsonLocation = ResourceLocation.fromNamespaceAndPath(NAMESPACE, "pixelfx/" + slug + ".json");
        Optional<Resource> resource = manager.getResource(jsonLocation);
        if (resource.isEmpty()) {
            warnOnce("meta:" + slug, "[pixelfx] 找不到特效元数据 {}，该特效将被跳过", jsonLocation);
            return Optional.empty();
        }
        try (BufferedReader reader = resource.get().openAsReader()) {
            JsonObject json = GsonHelper.parse(reader);
            return parse(slug, json);
        } catch (Exception e) {
            warnOnce("meta-parse:" + slug, "[pixelfx] 解析特效元数据 {} 失败，该特效将被跳过: {}",
                    jsonLocation, e.toString());
            return Optional.empty();
        }
    }

    /** 严格按契约 §4 读字段；结构性错误（缺 frames、缺 pivot）直接判为该特效不可用。 */
    private static Optional<PixelfxMetadata> parse(String slug, JsonObject json) {
        try {
            String textureText = GsonHelper.getAsString(json, "texture");
            ResourceLocation texture = ResourceLocation.tryParse(textureText);
            if (texture == null) {
                warnOnce("meta-tex:" + slug, "[pixelfx] {} 的 texture 字段不是合法 ResourceLocation: {}",
                        slug, textureText);
                return Optional.empty();
            }

            ResourceLocation lightTexture = null;
            if (GsonHelper.isStringValue(json, "lightTexture")) {
                String lightText = GsonHelper.getAsString(json, "lightTexture");
                lightTexture = ResourceLocation.tryParse(lightText);
                if (lightTexture == null) {
                    warnOnce("meta-light:" + slug, "[pixelfx] {} 的 lightTexture 不是合法 ResourceLocation: {}",
                            slug, lightText);
                }
            }

            int frameW = GsonHelper.getAsInt(json, "frameW");
            int frameH = GsonHelper.getAsInt(json, "frameH");
            int cols = GsonHelper.getAsInt(json, "cols");
            int rows = GsonHelper.getAsInt(json, "rows");
            int frames = GsonHelper.getAsInt(json, "frames");
            if (frameW <= 0 || frameH <= 0 || cols <= 0 || rows <= 0 || frames <= 0) {
                warnOnce("meta-dims:" + slug,
                        "[pixelfx] {} 的尺寸字段非法 (frameW={}, frameH={}, cols={}, rows={}, frames={})，已跳过",
                        slug, frameW, frameH, cols, rows, frames);
                return Optional.empty();
            }
            if ((long) cols * rows < frames) {
                warnOnce("meta-grid:" + slug,
                        "[pixelfx] {} 的网格装不下所有帧 (cols×rows={} < frames={})，已跳过",
                        slug, (long) cols * rows, frames);
                return Optional.empty();
            }

            JsonObject pivot = GsonHelper.getAsJsonObject(json, "pivot");
            float pivotX = GsonHelper.getAsFloat(pivot, "x");
            float pivotY = GsonHelper.getAsFloat(pivot, "y");

            String anchor = GsonHelper.getAsString(json, "anchor", "ground");
            boolean billboard = "billboard".equalsIgnoreCase(anchor);

            int fps = Math.max(1, GsonHelper.getAsInt(json, "fps", 20));
            boolean loop = GsonHelper.getAsBoolean(json, "loop", false);
            int durationTicks = GsonHelper.getAsInt(json, "durationTicks", frames);
            float defaultScale = GsonHelper.getAsFloat(json, "defaultScale", 0.02f);
            if (defaultScale <= 0.0f) {
                defaultScale = 0.02f;
            }
            // billboard 的屏幕平面旋转角；ground 忽略此字段（朝向由调用方 yaw 决定）
            float roll = GsonHelper.getAsFloat(json, "roll", 0.0f);
            if (!Float.isFinite(roll)) {
                roll = 0.0f;
            }

            // 下面三项是「Godot 移植适配」用的可调项，缺省值 = 保持原行为
            int tint = 0xFFFFFF;
            if (GsonHelper.isStringValue(json, "tint")) {
                String hex = GsonHelper.getAsString(json, "tint").replace("#", "").trim();
                try {
                    tint = Integer.parseInt(hex, 16) & 0xFFFFFF;
                } catch (NumberFormatException e) {
                    warnOnce("meta-tint:" + slug, "[pixelfx] {} 的 tint 不是合法 #RRGGBB: {}，按白色处理",
                            slug, hex);
                }
            }
            float lightStrength = GsonHelper.getAsFloat(json, "lightStrength", 1.0f);
            if (!(lightStrength > 0.0f) || !Float.isFinite(lightStrength)) {
                lightStrength = 1.0f;
            }
            boolean premultiplied = GsonHelper.getAsBoolean(json, "premultiplied", false);

            return Optional.of(new PixelfxMetadata(slug, texture, lightTexture,
                    frameW, frameH, cols, rows, frames, fps, loop, durationTicks,
                    pivotX, pivotY, billboard, roll, defaultScale,
                    tint, lightStrength, premultiplied));
        } catch (RuntimeException e) {
            // 缺字段 / 类型不对：pixelfx 是「资源没烘好」而不是「游戏坏了」，绝不能把玩家崩掉
            warnOnce("meta-field:" + slug, "[pixelfx] {} 的元数据字段不完整或类型不对，已跳过: {}",
                    slug, e.toString());
            return Optional.empty();
        }
    }

    /**
     * 绑定贴图到 {@code Sampler0}，并按需强制最近邻 + 关 mipmap。
     *
     * <p>刻意走 {@link net.minecraft.client.renderer.texture.TextureManager} 直接加载单张网格图，
     * <b>不进粒子图集</b>：pixelfx 的帧间 UV 是精确的网格，进图集会因为 atlas 的
     * sprite 边界插值而糊掉，也不该被其他资源挤占。
     *
     * @return false 表示贴图不存在（调用方跳过这一趟，不要继续画）
     */
    public static boolean bindTexture(@Nullable ResourceLocation location) {
        if (location == null) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ResourceManager manager = minecraft.getResourceManager();
        if (manager == null) {
            return false;
        }
        if (MISSING_TEXTURES.contains(location)) {
            return false;
        }
        // 注意：直接走 TextureManager 时路径必须带 `.png`（SimpleTexture.load 是精确 open），
        // 不带扩展名的 id 只有经过 atlas/Sprite 体系才会被补全。ResourceManager.getResource()
        // 同样是精确路径。两处统一用 pngLocation，缺一个就会回落成洋红 missing texture。
        ResourceLocation pngLocation = ResourceLocation.fromNamespaceAndPath(
                location.getNamespace(), location.getPath() + ".png");
        if (manager.getResource(pngLocation).isEmpty()) {
            MISSING_TEXTURES.add(location);
            warnOnce("texture:" + location, "[pixelfx] 找不到贴图 {}，相关特效将被跳过", pngLocation);
            return false;
        }
        AbstractTexture texture = minecraft.getTextureManager().getTexture(pngLocation);
        if (FILTERED_TEXTURES.add(pngLocation)) {
            // 像素画必须最近邻、关 mipmap；普通贴图默认是线性过滤，会把 1:1 的像素糊成一片
            texture.setFilter(false, false);
        }
        RenderSystem.setShaderTexture(0, pngLocation);
        return true;
    }

    /** 同一个问题只打一次日志（元数据缺失、贴图缺失、渲染异常）。 */
    static void warnOnce(String key, String format, Object... args) {
        if (WARNED.add(key)) {
            GalBoss.LOGGER.warn(format, args);
        }
    }
}
