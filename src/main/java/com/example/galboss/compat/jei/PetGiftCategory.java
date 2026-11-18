package com.example.galboss.compat.jei;

import com.example.galboss.GalBoss;
import com.example.galboss.entity.FsPetEntity;
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
 * JEI 分类「搭档 · 送礼」：一件礼物送给某位搭档能加多少好感度。
 *
 * <pre>
 *   [礼物]  →  [搭档徽章]
 *   好感度 +8      最喜欢的礼物
 *   搭档：桐香 · 搭档
 * </pre>
 */
public class PetGiftCategory implements IRecipeCategory<PetGiftCategory.Recipe> {

    /**
     * @param variant     目标搭档
     * @param gift        礼物
     * @param partnerIcon 该搭档对应的徽章
     * @param affection   该搭档实际能拿到的好感度（已含最爱加成）
     * @param favorite    是不是这位搭档的最爱
     */
    public record Recipe(FsPetEntity.PetVariant variant, ItemStack gift, ItemStack partnerIcon,
                         int affection, boolean favorite) {
    }

    public static final RecipeType<Recipe> TYPE = new RecipeType<>(
            ResourceLocation.fromNamespaceAndPath(GalBoss.MOD_ID, "pet_gift"), Recipe.class);

    private static final int WIDTH = 150;
    private static final int HEIGHT = 50;
    private static final int COLOR_TEXT = 0xFF404040;
    private static final int COLOR_FAVORITE = 0xFF9A6A00;
    private static final int COLOR_DIM = 0xFF707070;

    private final IDrawable icon;
    private final IDrawable arrow;

    public PetGiftCategory(IGuiHelper guiHelper) {
        this.icon = guiHelper.createDrawableItemStack(new ItemStack(Items.CAKE));
        this.arrow = guiHelper.getRecipeArrow();
    }

    @Override
    public RecipeType<Recipe> getRecipeType() {
        return TYPE;
    }

    @Override
    public Component getTitle() {
        return Component.translatable("jei.galboss.category.pet_gift");
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
        builder.addInputSlot(0, 2).setStandardSlotBackground().addItemStack(recipe.gift());
        builder.addOutputSlot(52, 2).setOutputSlotBackground().addItemStack(recipe.partnerIcon());
    }

    @Override
    public void draw(Recipe recipe, IRecipeSlotsView slotsView, GuiGraphics graphics,
                     double mouseX, double mouseY) {
        this.arrow.draw(graphics, 26, 6);

        Font font = Minecraft.getInstance().font;
        graphics.drawString(font,
                Component.translatable("jei.galboss.gift.affection", recipe.affection()),
                0, 26, COLOR_TEXT, false);

        if (recipe.favorite()) {
            graphics.drawString(font, Component.translatable("jei.galboss.gift.favorite"),
                    62, 26, COLOR_FAVORITE, false);
        }

        graphics.drawString(font,
                Component.translatable("jei.galboss.partner",
                        Component.translatable(recipe.variant().getNameKey())),
                0, 37, COLOR_DIM, false);
    }
}
