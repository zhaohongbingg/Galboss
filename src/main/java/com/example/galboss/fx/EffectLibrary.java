package com.example.galboss.fx;

import com.example.galboss.GalBoss;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.GsonHelper;
import net.minecraftforge.fml.loading.FMLPaths;

import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 特效定义库：把「特效长什么样、播多久、多大」从 Java 常量变成可编辑的数据。
 *
 * <p><b>这是可视化编辑器的地基。</b>编辑器本身（世界内拖拽 / 时间轴）要能「编」，
 * 先得有东西可编；而在此之前，{@code scale} / {@code yaw} 全都写死在
 * {@link PixelfxFx} 的调用点，改一个数就得重编译重启。
 *
 * <h2>三层来源（后者覆盖前者，字段级合并）</h2>
 * <ol>
 *   <li><b>L1 烘焙产物</b> {@code assets/galboss/pixelfx/<slug>.json} —— 资源管线产出，随 jar 发布，只读；</li>
 *   <li><b>L2 本地覆盖</b> {@code config/galboss/fx/<slug>.json} —— 开发期调参用，<b>可以只写要改的字段</b>；</li>
 *   <li><b>L3 运行时</b> 本类的 {@link #definitions} —— 双端统一的唯一真相。</li>
 * </ol>
 *
 * <p>L2 的部分覆盖靠「浅合并 + 对象递归合并」实现：写 {@code {"pivot":{"x":120}}} 只会改 pivot.x，
 * 其余字段仍取 L1 的值。这正是 v1 拖拽编辑器回写数值时要用的落盘格式。
 *
 * <h2>线程与重载</h2>
 * <p>{@link #scan} 是纯函数（无副作用，可在 prepare 线程跑 IO）；{@link #publish} 只做一次引用替换。
 * 每次发布 {@link #generation()} 自增，客户端据此让 {@code PixelfxAssets} 的解析缓存失效 ——
 * <b>不需要额外网络包</b>，单机下服务端命令重载后客户端下一帧就会重新解析。
 *
 * <p><b>硬约束</b>：本类双端可用，只存 {@link JsonObject}，绝不引用 {@code net.minecraft.client.*}
 * 或 {@code PixelfxMetadata}（专用服务器上没有客户端类）。
 */
public final class EffectLibrary {

    /** 资源目录（相对 {@code assets/galboss/}）。 */
    public static final String ASSET_DIR = "pixelfx";

    /** 本地覆盖目录（相对 {@code config/galboss/}）。 */
    public static final String CONFIG_SUBDIR = "fx";

    private static final String JSON_SUFFIX = ".json";

    /** 必填字段：缺任一即判为该定义不可用（与 CONTRACT.md §4 一致）。 */
    private static final String[] REQUIRED_FIELDS = {"texture", "frames"};

    /** 定义类型判别字段。缺省视为 {@code pixelfx}（兼容所有已烘焙的 json）。 */
    public static final String KIND_PIXELFX = "pixelfx";

    /** 参数类定义（如 {@code butterfly_path}）：不要求 pixelfx 字段。 */
    public static final String KIND_PATH = "path";

    /** 当前生效的定义。空 Map 表示「还没加载过」，不是「没有任何特效」。 */
    private static volatile Map<String, JsonObject> definitions = Collections.emptyMap();

    /** 发布代数：每次 {@link #publish} 自增，供客户端缓存失效用。 */
    private static volatile int generation;

    /** 只警告一次的键。 */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private EffectLibrary() {
    }

    // ------------------------------------------------------------------ 查询

    /** 该 slug 是否有可用定义。 */
    public static boolean isKnown(@Nullable String slug) {
        return slug != null && !slug.isEmpty() && definitions.containsKey(slug);
    }

    /** 全部已知 slug（字典序，稳定输出）。库未加载时为空。 */
    public static List<String> slugs() {
        return List.copyOf(definitions.keySet());
    }

    public static int size() {
        return definitions.size();
    }

    /** 逗号分隔的 slug 清单，给命令报错 / 日志用。 */
    public static String asText() {
        List<String> all = slugs();
        return all.isEmpty() ? "(尚未加载)" : String.join(", ", all);
    }

    /** 取合并后的原始定义。客户端用它解析成 {@code PixelfxMetadata}。 */
    public static Optional<JsonObject> definition(@Nullable String slug) {
        if (slug == null || slug.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(definitions.get(slug));
    }

    /** 发布代数；变化即表示定义库被重载过。 */
    public static int generation() {
        return generation;
    }

    // ------------------------------------------------------------------ 加载

    /**
     * 扫描 L1 + L2 并合并，返回不可变结果。<b>纯函数</b>：不改任何静态状态，可在 prepare 线程调用。
     *
     * @param resourceManager 资源管理器；null 时只读 L2（用于极早期 / 测试场景）
     */
    public static Map<String, JsonObject> scan(@Nullable ResourceManager resourceManager) {
        Map<String, JsonObject> merged = new TreeMap<>();

        // ---- L1：随 jar 发布的烘焙产物
        if (resourceManager != null) {
            try {
                Map<ResourceLocation, Resource> found = resourceManager.listResources(ASSET_DIR,
                        location -> GalBoss.MOD_ID.equals(location.getNamespace())
                                && location.getPath().endsWith(JSON_SUFFIX));
                for (Map.Entry<ResourceLocation, Resource> entry : found.entrySet()) {
                    String slug = slugOf(entry.getKey().getPath());
                    if (slug == null) {
                        continue;
                    }
                    try (BufferedReader reader = entry.getValue().openAsReader()) {
                        merged.put(slug, GsonHelper.parse(reader));
                    } catch (Exception e) {
                        warnOnce("asset:" + slug, "[fx] 解析内置定义 {} 失败，已跳过: {}",
                                entry.getKey(), e.toString());
                    }
                }
            } catch (Exception e) {
                warnOnce("asset-scan", "[fx] 扫描 {} 目录失败，本轮只加载本地覆盖: {}",
                        ASSET_DIR, e.toString());
            }
        }

        // ---- L2：config/galboss/fx/*.json 覆盖（可只写部分字段）
        Path overrideDir = FMLPaths.CONFIGDIR.get().resolve(GalBoss.MOD_ID).resolve(CONFIG_SUBDIR);
        if (Files.isDirectory(overrideDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(overrideDir, "*" + JSON_SUFFIX)) {
                for (Path file : stream) {
                    String slug = slugOf(file.getFileName().toString());
                    if (slug == null) {
                        continue;
                    }
                    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                        JsonElement parsed = JsonParser.parseReader(reader);
                        if (!parsed.isJsonObject()) {
                            warnOnce("override-shape:" + slug,
                                    "[fx] 本地覆盖 {} 不是 JSON 对象，已跳过", file);
                            continue;
                        }
                        JsonObject base = merged.get(slug);
                        merged.put(slug, base == null ? parsed.getAsJsonObject()
                                : mergeInto(base.deepCopy(), parsed.getAsJsonObject()));
                    } catch (Exception e) {
                        warnOnce("override:" + slug, "[fx] 解析本地覆盖 {} 失败，已跳过: {}",
                                file, e.toString());
                    }
                }
            } catch (IOException e) {
                warnOnce("override-scan", "[fx] 扫描 {} 失败: {}", overrideDir, e.toString());
            }
        }

        // ---- 合并后再校验：L2 可能是部分覆盖，只有合并结果才谈得上完整
        merged.entrySet().removeIf(entry -> {
            // 参数类定义（kind=path）没有贴图/帧字段，不能按 pixelfx 的规矩校验
            if (KIND_PATH.equals(GsonHelper.getAsString(entry.getValue(), "kind", KIND_PIXELFX))) {
                return false;
            }
            for (String field : REQUIRED_FIELDS) {
                if (!entry.getValue().has(field)) {
                    warnOnce("invalid:" + entry.getKey(),
                            "[fx] 定义 {} 缺少必填字段 \"{}\"，已跳过", entry.getKey(), field);
                    return true;
                }
            }
            return false;
        });

        return Collections.unmodifiableMap(merged);
    }

    /** 发布扫描结果（游戏线程调用）。 */
    public static void publish(Map<String, JsonObject> scanned) {
        definitions = scanned == null ? Collections.emptyMap() : scanned;
        generation++;
        GalBoss.LOGGER.info("[fx] 特效定义库已加载：{} 个（{}）", definitions.size(), asText());
    }

    /** 手动重载：给 {@code /galboss fx reload} 用。 */
    public static void reload(@Nullable ResourceManager resourceManager) {
        publish(scan(resourceManager));
    }

    /** 清掉「只警告一次」的记录（重载后如果还缺，重新提醒一次是合理的）。 */
    public static void clearWarnings() {
        WARNED.clear();
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 把 {@code override} 递归合并进 {@code base}：对象递归、其余直接覆盖。
     *
     * <p>递归是为了让 {@code {"pivot":{"x":120}}} 这种部分覆盖能只改一个字段。
     */
    private static JsonObject mergeInto(JsonObject base, JsonObject override) {
        for (Map.Entry<String, JsonElement> entry : override.entrySet()) {
            JsonElement existing = base.get(entry.getKey());
            JsonElement incoming = entry.getValue();
            if (existing != null && existing.isJsonObject() && incoming.isJsonObject()) {
                mergeInto(existing.getAsJsonObject(), incoming.getAsJsonObject());
            } else {
                base.add(entry.getKey(), incoming);
            }
        }
        return base;
    }

    /**
     * 从资源路径 / 文件名里取 slug。
     *
     * @param path 形如 {@code pixelfx/tohka_bell_wave.json} 或 {@code tohka_bell_wave.json}
     * @return 合法 slug；非法（空、含非法字符）时返回 null
     */
    @Nullable
    private static String slugOf(@Nullable String path) {
        if (path == null) {
            return null;
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        if (!name.endsWith(JSON_SUFFIX)) {
            return null;
        }
        String slug = name.substring(0, name.length() - JSON_SUFFIX.length());
        if (slug.isEmpty()) {
            return null;
        }
        for (int i = 0; i < slug.length(); i++) {
            char c = slug.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (!ok) {
                return null;
            }
        }
        return slug;
    }

    private static void warnOnce(String key, String format, Object... args) {
        if (WARNED.add(key)) {
            GalBoss.LOGGER.warn(format, args);
        }
    }
}
