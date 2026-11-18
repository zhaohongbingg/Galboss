package com.example.galboss.item;

import com.example.galboss.config.GalBossConfig;
import com.example.galboss.entity.FsPetEntity;
import com.example.galboss.pet.PetDataParser;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 礼物表：哪件物品能送、一件加多少好感度。
 *
 * <p>数据来自 {@code config/galboss-common.toml} 的 {@code [pet_gift]} 段：
 * <pre>
 *   giftValues    = ["minecraft:golden_apple=8", ...]   # 物品=好感度
 *   giftFavorites = ["tohka=minecraft:cake", ...]       # 搭档=最爱的物品
 * </pre>
 *
 * <p>第一次用到时解析一次并缓存，配置重载后由 {@code GalBoss.ConfigReloadHandler} 置空重建。
 */
public final class PetGiftTable {

    private static volatile Map<Item, Integer> values;
    private static volatile Map<FsPetEntity.PetVariant, Item> favorites;

    private PetGiftTable() {
    }

    /** 配置改动后调用，下次访问会重新解析。 */
    public static void invalidate() {
        values = null;
        favorites = null;
    }

    /** 返回该物品能提供的好感度；0 表示这不是礼物。 */
    public static int valueOf(FsPetEntity.PetVariant variant, ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        Integer base = values().get(stack.getItem());
        if (base == null) {
            return 0;
        }
        // 最爱：额外加一半 + 2，保证「对症下药」永远比乱塞更划算
        if (favorites().get(variant) == stack.getItem()) {
            return base + base / 2 + 2;
        }
        return base;
    }

    public static boolean isFavorite(FsPetEntity.PetVariant variant, ItemStack stack) {
        return !stack.isEmpty() && favorites().get(variant) == stack.getItem();
    }

    /**
     * 完整的礼物表（物品 → 基础好感度），按配置里的书写顺序，只读。
     *
     * <p>给 JEI 这类「要列出全部」的展示用；单点查询请走 {@link #valueOf}。
     */
    public static Map<Item, Integer> baseValues() {
        return values();
    }

    private static Map<Item, Integer> values() {
        Map<Item, Integer> local = values;
        if (local == null) {
            reload();
            local = values;
        }
        return local;
    }

    private static Map<FsPetEntity.PetVariant, Item> favorites() {
        Map<FsPetEntity.PetVariant, Item> local = favorites;
        if (local == null) {
            reload();
            local = favorites;
        }
        return local;
    }

    private static synchronized void reload() {
        if (values != null && favorites != null) {
            return;
        }

        // LinkedHashMap 保留配置里的书写顺序，JEI 里列出来的次序才和 toml 一致
        Map<Item, Integer> parsedValues = new LinkedHashMap<>();
        for (String entry : GalBossConfig.PET_GIFT_VALUES.get()) {
            int split = entry.indexOf('=');
            if (split <= 0) {
                PetDataParser.warn("pet_gift.giftValues", entry, "缺少 '='");
                continue;
            }
            Item item = PetDataParser.itemOf(entry.substring(0, split), "pet_gift.giftValues");
            Integer value = PetDataParser.intOf(entry.substring(split + 1), "pet_gift.giftValues");
            if (item == null || value == null) {
                continue;
            }
            parsedValues.put(item, value);
        }

        Map<FsPetEntity.PetVariant, Item> parsedFavorites =
                new EnumMap<>(FsPetEntity.PetVariant.class);
        List<? extends String> favoriteEntries = GalBossConfig.PET_GIFT_FAVORITES.get();
        for (String entry : favoriteEntries) {
            int split = entry.indexOf('=');
            if (split <= 0) {
                PetDataParser.warn("pet_gift.giftFavorites", entry, "缺少 '='");
                continue;
            }
            FsPetEntity.PetVariant variant =
                    PetDataParser.variantOf(entry.substring(0, split), "pet_gift.giftFavorites");
            Item item = PetDataParser.itemOf(entry.substring(split + 1), "pet_gift.giftFavorites");
            if (variant == null || item == null) {
                continue;
            }
            parsedFavorites.put(variant, item);
        }

        // 两张表一起发布，避免读到「一半新一半旧」的中间状态；同时包成只读，防止外部改坏缓存
        values = Collections.unmodifiableMap(parsedValues);
        favorites = Collections.unmodifiableMap(parsedFavorites);
    }
}
