package com.example.galboss.pet;

import com.example.galboss.GalBoss;
import com.example.galboss.entity.FsPetEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * 解析配置里的字符串条目。
 *
 * <p>所有方法都「失败返回 null + 记一条 warn」，不做异常抛出 —— 配置里写错一行只丢掉那一行，
 * 不会让整个配置加载失败、更不会崩游戏。
 */
public final class PetDataParser {

    private PetDataParser() {
    }

    @Nullable
    public static Item itemOf(@Nullable String raw, String context) {
        if (raw == null) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(raw.trim());
        if (id == null) {
            warn(context, raw, "不是合法的物品 ID");
            return null;
        }
        Item item = ForgeRegistries.ITEMS.getValue(id);
        if (item == null) {
            warn(context, raw, "注册表里没有这个物品");
            return null;
        }
        return item;
    }

    @Nullable
    public static Integer intOf(@Nullable String raw, String context) {
        if (raw == null) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            warn(context, raw, "不是整数");
            return null;
        }
    }

    /** 接受短名（tohka / rei / ikuko）和完整实体名（reizein_tohka 等）。 */
    @Nullable
    public static FsPetEntity.PetVariant variantOf(@Nullable String raw, String context) {
        if (raw == null) {
            return null;
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        for (FsPetEntity.PetVariant variant : FsPetEntity.PetVariant.values()) {
            if (variant.getEntityId().equals(key) || variant.name().toLowerCase(Locale.ROOT).equals(key)) {
                return variant;
            }
        }
        warn(context, raw, "未知的搭档名");
        return null;
    }

    /** 按空白切段，段数不对就返回 null。 */
    @Nullable
    public static String[] split(String raw, int expected, String context) {
        String[] parts = raw.trim().split("\\s+");
        if (parts.length != expected) {
            warn(context, raw, "需要 " + expected + " 段，实际 " + parts.length + " 段");
            return null;
        }
        return parts;
    }

    public static void warn(String context, String raw, String reason) {
        GalBoss.LOGGER.warn("[GalBoss] 跳过宠物配置项（{}）：{}  <- {}", context, reason, raw);
    }
}
