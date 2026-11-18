package com.example.galboss.compat.jei;

import com.example.galboss.GalBoss;
import com.example.galboss.entity.FsPetEntity;
import com.example.galboss.pet.PetTradeTable;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * JEI 分类「搭档 · 交易」：哪位搭档、出什么价、卖什么、好感度门槛多少。
 *
 * <pre>
 *   [徽章] + [货币 ×原价]  →  [商品]
 *   搭档：桐香 · 搭档
 *   好感度 ≥ 60 解锁
 *   好感度越高越便宜，最高 -40%
 * </pre>
 *
 * <p>价格显示的是**原价**。实际成交价会按当前好感度打折，折扣阶梯见
 * {@link PetTradeTable#discountOf(int)}。
 */
public class PetTradeCategory implements IRecipeCategory<PetTradeCategory.Recipe> {

    /**
     * @param variant         出售方
     * @param currency        需要的货币（数量 = 原价）
     * @param result          卖出的商品
     * @param basePrice       原价
     * @param unlockAffection 解锁所需好感度
     */
    public record Recipe(FsPetEntity.PetVariant variant, ItemStack currency, ItemStack result,
                         int basePrice, int unlockAffection) {
    }

    public static final RecipeType<Recipe> TYPE = new RecipeType<>(
            ResourceLocation.fromNamespaceAndPath(GalBoss.MOD_ID, "pet_trade"), Recipe.class);

    private static final int WIDTH = 160;
    private static final int HEIGHT = 56;
    private static final int COLOR_TEXT = 0xFF404040;
    private static final int COLOR_DIM = 0xFF707070;

    /** 满好感度时的折扣，从折扣阶梯现算，改了阶梯这里跟着变。 */
    private static final String MAX_DISCOUNT = "-"
            + Math.round(PetTradeTable.discountOf(FsPetEntity.MAX_AFFECTION) * 100) + "%";

    private final IDrawable icon;
    private final IDrawable arrow;
    private final IDrawable plus;

    public PetTradeCategory(IGuiHelper guiHelper) {
        this.icon = guiHelper.createDrawableItemStack(new ItemStack(Items.EMERALD));
        this.arrow = guiHelper.getRecipeArrow();
        this.plus = guiHelper.getRecipePlusSign();
    }

    @Override
    public RecipeType<Recipe> getRecipeType() {
        return TYPE;
    }

    @Override
    public Component getTitle() {
        return Component.translatable("jei.galboss.category.pet_trade");
    }

    @Override
    public int getWidth() {
        return WIDTH;
    }

    @Override
    public int getHeight() {
        return HEIGHT;
    }

    @Override
    public IDrawable getIcon() {
        return this.icon;
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, Recipe recipe, IFocusGroup focuses) {
        builder.addInputSlot(0, 2).setStandardSlotBackground().addItemStack(recipe.currency());
        builder.addOutputSlot(80, 2).setOutputSlotBackground().addItemStack(recipe.result());
    }

    @Override
    public void draw(Recipe recipe, IRecipeSlotsView slotsView, GuiGraphics graphics,
                     double mouseX, double mouseY) {
        this.plus.draw(graphics, 40, 6);
        this.arrow.draw(graphics, 58, 6);

        Font font = Minecraft.getInstance().font;
        graphics.drawString(font,
                Component.translatable("jei.galboss.partner",
                        Component.translatable(recipe.variant().getNameKey())),
                0, 24, COLOR_DIM, false);

        Component unlock = recipe.unlockAffection() <= 0
                ? Component.translatable("jei.galboss.trade.unlocked")
                : Component.translatable("jei.galboss.trade.unlock", recipe.unlockAffection());
        graphics.drawString(font, unlock, 0, 34, COLOR_TEXT, false);

        graphics.drawString(font,
                Component.translatable("jei.galboss.trade.discount", MAX_DISCOUNT),
                0, 44, COLOR_DIM, false);
    }
}
