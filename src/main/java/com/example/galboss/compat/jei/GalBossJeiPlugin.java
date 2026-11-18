package com.example.galboss.compat.jei;

import com.example.galboss.GalBoss;
import com.example.galboss.entity.FsPetEntity;
import com.example.galboss.item.ModItems;
import com.example.galboss.item.PetGiftTable;
import com.example.galboss.pet.PetTradeTable;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * JEI 软联动：把「送礼表」和「交易货架」也做成 JEI 里的两个分类。
 *
 * <p>这是**可选**联动 —— JEI 没装时这个类永远不会被加载（只有 JEI 自己的 {@code @JeiPlugin}
 * 扫描会碰它），mods.toml 里也是 {@code mandatory=false}，所以不构成前置依赖。
 *
 * <p>数据直接读 {@link PetGiftTable} / {@link PetTradeTable}，也就是那份
 * {@code config/galboss-common.toml}，改配置后 JEI 会跟着变（JEI 收到配置重载事件时会重建配方
 * 列表；最坏情况重启一次也能对）。
 */
@JeiPlugin
public class GalBossJeiPlugin implements IModPlugin {

    private static final ResourceLocation PLUGIN_UID =
            ResourceLocation.fromNamespaceAndPath(GalBoss.MOD_ID, "jei_plugin");

    @Override
    public ResourceLocation getPluginUid() {
        return PLUGIN_UID;
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        IGuiHelper guiHelper = registration.getJeiHelpers().getGuiHelper();
        registration.addRecipeCategories(
                new PetGiftCategory(guiHelper),
                new PetTradeCategory(guiHelper));
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        registration.addRecipes(PetGiftCategory.TYPE, buildGiftRecipes());
        registration.addRecipes(PetTradeCategory.TYPE, buildTradeRecipes());
    }

    /**
     * 把三枚徽章挂成催化剂：在 JEI 里查徽章，就能直接看到对应的送礼表与货架。
     */
    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        for (FsPetEntity.PetVariant variant : FsPetEntity.PetVariant.values()) {
            registration.addRecipeCatalyst(partnerStack(variant),
                    PetGiftCategory.TYPE, PetTradeCategory.TYPE);
        }
    }

    /**
     * 每个 (礼物, 搭档) 组合出一条。
     *
     * <p>数量看着多一点（礼物数 × 3），但换来的是两个方向都好查：
     * 查礼物 → 三个搭档各加多少；查搭档的徽章 → 他/她喜欢的全部东西。
     */
    private static List<PetGiftCategory.Recipe> buildGiftRecipes() {
        List<PetGiftCategory.Recipe> recipes = new ArrayList<>();
        for (Map.Entry<Item, Integer> entry : PetGiftTable.baseValues().entrySet()) {
            ItemStack gift = new ItemStack(entry.getKey());
            for (FsPetEntity.PetVariant variant : FsPetEntity.PetVariant.values()) {
                recipes.add(new PetGiftCategory.Recipe(
                        variant,
                        gift,
                        partnerStack(variant),
                        PetGiftTable.valueOf(variant, gift),
                        PetGiftTable.isFavorite(variant, gift)));
            }
        }
        return recipes;
    }

    private static List<PetTradeCategory.Recipe> buildTradeRecipes() {
        List<PetTradeCategory.Recipe> recipes = new ArrayList<>();
        for (FsPetEntity.PetVariant variant : FsPetEntity.PetVariant.values()) {
            for (PetTradeTable.Offer offer : PetTradeTable.offersOf(variant)) {
                recipes.add(new PetTradeCategory.Recipe(
                        variant,
                        new ItemStack(offer.currency(), Math.min(offer.price(), 64)),
                        offer.createStack(),
                        offer.price(),
                        offer.unlockAffection()));
            }
        }
        return recipes;
    }

    /** 搭档的「化身」物品：用召唤他的那枚徽章，语义上就是这个搭档本尊。 */
    private static ItemStack partnerStack(FsPetEntity.PetVariant variant) {
        return new ItemStack(ModItems.summonItemOf(variant));
    }
}
