package com.example.galboss.pet;

import com.example.galboss.config.GalBossConfig;
import com.example.galboss.entity.FsPetEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 交易商品表。
 *
 * <p>数据来自 {@code config/galboss-common.toml} 的 {@code [pet_trade]} 段，每行五段：
 * <pre>
 *   "&lt;商品ID&gt; &lt;数量&gt; &lt;货币ID&gt; &lt;价格&gt; &lt;解锁好感度&gt;"
 *   例: "minecraft:book 3 minecraft:emerald 2 0"
 * </pre>
 *
 * <p>折扣阶梯仍写在代码里（好感度越高越便宜，最高 40%），只把货架内容外置。
 */
public final class PetTradeTable {

    /**
     * 一件商品。
     *
     * @param result          卖出的物品
     * @param count           卖出的数量
     * @param currency        需要的货币
     * @param price           原价
     * @param unlockAffection 解锁所需好感度
     */
    public record Offer(Item result, int count, Item currency, int price, int unlockAffection) {

        public ItemStack createStack() {
            return new ItemStack(this.result, this.count);
        }

        public boolean isUnlocked(int affection) {
            return affection >= this.unlockAffection;
        }
    }

    private static final int ENTRY_PARTS = 5;

    private static volatile Map<FsPetEntity.PetVariant, List<Offer>> offers;

    private PetTradeTable() {
    }

    /** 配置改动后调用，下次访问会重新解析。 */
    public static void invalidate() {
        offers = null;
    }

    public static List<Offer> offersOf(FsPetEntity.PetVariant variant) {
        return table().getOrDefault(variant, List.of());
    }

    /** 好感度折扣：陌生 0% / 友好 10% / 信任 25% / 亲密 40%。 */
    public static double discountOf(int affection) {
        return switch (FsPetEntity.AffectionTier.of(affection)) {
            case STRANGER -> 0.0;
            case FRIENDLY -> 0.10;
            case TRUSTED -> 0.25;
            case INTIMATE -> 0.40;
        };
    }

    /** 折后价，向上取整，最低 1。 */
    public static int priceOf(Offer offer, int affection) {
        return Math.max(1, (int) Math.ceil(offer.price() * (1.0 - discountOf(affection))));
    }

    private static Map<FsPetEntity.PetVariant, List<Offer>> table() {
        Map<FsPetEntity.PetVariant, List<Offer>> local = offers;
        if (local == null) {
            reload();
            local = offers;
        }
        return local;
    }

    private static synchronized void reload() {
        if (offers != null) {
            return;
        }
        Map<FsPetEntity.PetVariant, List<Offer>> parsed =
                new EnumMap<>(FsPetEntity.PetVariant.class);
        parsed.put(FsPetEntity.PetVariant.TOHKA,
                parse(GalBossConfig.PET_TRADES_TOHKA.get(), "pet_trade.tohkaTrades"));
        parsed.put(FsPetEntity.PetVariant.REI,
                parse(GalBossConfig.PET_TRADES_REI.get(), "pet_trade.reiTrades"));
        parsed.put(FsPetEntity.PetVariant.IKUKO,
                parse(GalBossConfig.PET_TRADES_IKUKO.get(), "pet_trade.ikukoTrades"));
        offers = parsed;
    }

    private static List<Offer> parse(List<? extends String> entries, String context) {
        List<Offer> result = new ArrayList<>();
        for (String entry : entries) {
            String[] parts = PetDataParser.split(entry, ENTRY_PARTS, context);
            if (parts == null) {
                continue;
            }
            Item item = PetDataParser.itemOf(parts[0], context);
            Integer count = PetDataParser.intOf(parts[1], context);
            Item currency = PetDataParser.itemOf(parts[2], context);
            Integer price = PetDataParser.intOf(parts[3], context);
            Integer unlock = PetDataParser.intOf(parts[4], context);
            if (item == null || count == null || currency == null || price == null || unlock == null) {
                continue;
            }
            if (count <= 0 || price <= 0) {
                PetDataParser.warn(context, entry, "数量与价格必须为正数");
                continue;
            }
            result.add(new Offer(item, count, currency, price, Math.max(0, unlock)));
        }
        return List.copyOf(result);
    }
}
