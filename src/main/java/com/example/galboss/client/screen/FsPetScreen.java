package com.example.galboss.client.screen;

import com.example.galboss.GalBoss;
import com.example.galboss.entity.FsPetEntity;
import com.example.galboss.item.PetGiftTable;
import com.example.galboss.network.C2SPetActionPacket;
import com.example.galboss.network.ModNetwork;
import com.example.galboss.pet.PetChatTable;
import com.example.galboss.pet.PetTradeTable;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 宠物养成界面：送礼 / 聊天 / 交易 三个页签。
 *
 * <p>这是一个纯客户端 {@link Screen}，不挂容器、不占物品槽，好处是不会产生物品复制漏洞。
 * 送礼取的是玩家主手物品，所有结算都发回服务端做（见 {@link C2SPetActionPacket}）。
 */
public class FsPetScreen extends Screen {

    private static final int PANEL_WIDTH = 250;
    private static final int PANEL_HEIGHT = 200;

    private static final int TAB_GIFT = 0;
    private static final int TAB_CHAT = 1;
    private static final int TAB_TRADE = 2;

    private static final int COLOR_PANEL = 0xE8101018;
    private static final int COLOR_BORDER = 0xFF7A7AA8;
    private static final int COLOR_TEXT = 0xFFE8E8F0;
    private static final int COLOR_DIM = 0xFF9A9AB0;
    private static final int COLOR_ACCENT = 0xFFFF5E8A;
    private static final int COLOR_GOLD = 0xFFFFD27F;

    private final int petId;

    @Nullable
    private FsPetEntity pet;

    private int tab = TAB_GIFT;

    private final List<Component> log = new ArrayList<>();

    /** 交易行点击区域：{x, y, w, h, 商品序号}。 */
    private final List<int[]> tradeRows = new ArrayList<>();

    public FsPetScreen(int petId) {
        super(Component.translatable("pet.galboss.screen.title"));
        this.petId = petId;
    }

    public int getPetId() {
        return this.petId;
    }

    /** 收到服务端的宠物消息：界面开着就追加到这里，不再走聊天栏。 */
    public void pushMessage(Component message) {
        FsPetEntity current = this.pet();
        Component line = current == null
                ? message
                : current.getName().copy().append(Component.literal(": ")).append(message);
        this.log.add(line);
        while (this.log.size() > 6) {
            this.log.remove(0);
        }
    }

    @Nullable
    private FsPetEntity pet() {
        if (this.pet != null && this.pet.isAlive()) {
            return this.pet;
        }
        if (this.minecraft != null && this.minecraft.level != null
                && this.minecraft.level.getEntity(this.petId) instanceof FsPetEntity found) {
            this.pet = found;
        }
        return this.pet;
    }

    private int left() {
        return (this.width - PANEL_WIDTH) / 2;
    }

    private int top() {
        return (this.height - PANEL_HEIGHT) / 2;
    }

    // ------------------------------------------------------------------ 布局

    @Override
    protected void init() {
        super.init();
        this.tradeRows.clear();

        int left = this.left();
        int top = this.top();

        this.addRenderableWidget(Button.builder(Component.translatable("pet.galboss.tab.gift"),
                        b -> this.switchTab(TAB_GIFT))
                .bounds(left + 8, top + 40, 74, 18).build());
        this.addRenderableWidget(Button.builder(Component.translatable("pet.galboss.tab.chat"),
                        b -> this.switchTab(TAB_CHAT))
                .bounds(left + 88, top + 40, 74, 18).build());
        this.addRenderableWidget(Button.builder(Component.translatable("pet.galboss.tab.trade"),
                        b -> this.switchTab(TAB_TRADE))
                .bounds(left + 168, top + 40, 74, 18).build());

        FsPetEntity current = this.pet();
        if (current != null) {
            switch (this.tab) {
                case TAB_GIFT -> this.addRenderableWidget(
                        Button.builder(Component.translatable("pet.galboss.gift.button"),
                                        b -> this.send(C2SPetActionPacket.ACTION_GIFT, 0))
                                .bounds(left + 65, top + 116, 120, 20).build());
                case TAB_CHAT -> {
                    List<PetChatTable.Topic> topics = PetChatTable.topicsOf(current.getVariant());
                    for (int i = 0; i < topics.size(); i++) {
                        int index = i;
                        this.addRenderableWidget(Button.builder(
                                        this.topicLabel(current, topics.get(i), index),
                                        b -> this.send(C2SPetActionPacket.ACTION_CHAT, index))
                                .bounds(left + 12, top + 62 + i * 18, PANEL_WIDTH - 24, 16).build());
                    }
                }
                case TAB_TRADE -> {
                    List<PetTradeTable.Offer> offers = PetTradeTable.offersOf(current.getVariant());
                    for (int i = 0; i < offers.size(); i++) {
                        this.tradeRows.add(new int[]{
                                left + 12, top + 74 + i * 22, PANEL_WIDTH - 24, 20, i});
                    }
                }
                default -> {
                }
            }

            this.addRenderableWidget(Button.builder(this.sitLabel(current),
                            b -> this.send(C2SPetActionPacket.ACTION_TOGGLE_SIT, 0))
                    .bounds(left + 8, top + 176, 120, 18).build());
        }

        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> this.onClose())
                .bounds(left + PANEL_WIDTH - 68, top + 176, 60, 18).build());
    }

    private void switchTab(int newTab) {
        if (this.tab == newTab) {
            return;
        }
        this.tab = newTab;
        this.rebuildWidgets();
    }

    private void send(byte action, int arg) {
        // 链路诊断用：和 C2SPetActionPacket 里的接收日志配对看，
        // 就能区分"包没发出去"和"服务端收到了但没生效"。
        GalBoss.LOGGER.debug("[panel] 客户端发送 动作={} 搭档实体={}", action, this.petId);
        ModNetwork.sendToServer(new C2SPetActionPacket(this.petId, action, arg));
    }

    private Component topicLabel(FsPetEntity pet, PetChatTable.Topic topic, int index) {
        boolean done = (pet.getChatDone() & (1 << index)) != 0;
        boolean unlocked = topic.isUnlocked(pet.getAffection());

        Component marker = Component.literal(done ? "§a+ " : unlocked ? "§7> " : "§8x ");
        MutableComponent label = Component.literal("").append(marker)
                .append(Component.translatable(topic.labelKey()));
        if (!unlocked) {
            label.append(Component.translatable("pet.galboss.chat.locked", topic.unlockAffection()));
        }
        return label;
    }

    private Component sitLabel(FsPetEntity pet) {
        return Component.translatable(pet.isSitting()
                ? "pet.galboss.button.stand" : "pet.galboss.button.sit");
    }

    // ------------------------------------------------------------------ 渲染

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 手动铺一层变暗，避免依赖 renderBackground 在不同版本里的签名差异
        graphics.fill(0, 0, this.width, this.height, 0xC0101018);

        FsPetEntity current = this.pet();
        if (current == null) {
            this.onClose();
            return;
        }

        int left = this.left();
        int top = this.top();
        graphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, COLOR_PANEL);
        graphics.renderOutline(left, top, PANEL_WIDTH, PANEL_HEIGHT, COLOR_BORDER);

        this.renderHeader(graphics, current, left, top);

        switch (this.tab) {
            case TAB_GIFT -> this.renderGiftTab(graphics, current, left, top);
            case TAB_CHAT -> this.renderChatTab(graphics, current, left, top);
            case TAB_TRADE -> this.renderTradeTab(graphics, current, left, top, mouseX, mouseY);
            default -> {
            }
        }

        // 状态行只在非聊天页显示，聊天页下方已经是完整对话记录了
        if (this.tab != TAB_CHAT && !this.log.isEmpty()) {
            this.drawClipped(graphics, this.log.get(this.log.size() - 1),
                    left + 10, top + 165, PANEL_WIDTH - 20, COLOR_GOLD);
        }

        for (Renderable renderable : this.renderables) {
            renderable.render(graphics, mouseX, mouseY, partialTick);
        }
    }

    private void renderHeader(GuiGraphics graphics, FsPetEntity pet, int left, int top) {
        this.drawClipped(graphics, pet.getName(), left + 10, top + 7, 130, COLOR_TEXT);

        Component right = Component.translatable(pet.getTier().getLangKey())
                .copy().append(Component.literal("  "
                        + pet.getAffection() + "/" + FsPetEntity.MAX_AFFECTION));
        graphics.drawString(this.font, right,
                left + PANEL_WIDTH - 10 - this.font.width(right), top + 7, COLOR_GOLD, false);

        this.drawAffectionBar(graphics, left + 10, top + 23, PANEL_WIDTH - 20, 6, pet);
    }

    private void drawAffectionBar(GuiGraphics graphics, int x, int y, int width, int height, FsPetEntity pet) {
        graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, 0xFF30303C);
        int filled = Math.round(width * (pet.getAffection() / (float) FsPetEntity.MAX_AFFECTION));
        if (filled > 0) {
            graphics.fill(x, y, x + filled, y + height, COLOR_ACCENT);
        }
        for (FsPetEntity.AffectionTier tier : FsPetEntity.AffectionTier.values()) {
            if (tier.getMinAffection() <= 0) {
                continue;
            }
            int markX = x + Math.round(width
                    * (tier.getMinAffection() / (float) FsPetEntity.MAX_AFFECTION));
            graphics.fill(markX, y - 1, markX + 1, y + height + 1, 0xFFB0B0C8);
        }
    }

    private void renderGiftTab(GuiGraphics graphics, FsPetEntity pet, int left, int top) {
        ItemStack held = this.minecraft == null || this.minecraft.player == null
                ? ItemStack.EMPTY : this.minecraft.player.getMainHandItem();
        int value = PetGiftTable.valueOf(pet.getVariant(), held);

        int slotX = left + 18;
        int slotY = top + 76;
        graphics.fill(slotX - 2, slotY - 2, slotX + 20, slotY + 20, 0xFF2A2A36);
        graphics.renderOutline(slotX - 2, slotY - 2, 22, 22, 0xFF50506A);
        if (!held.isEmpty()) {
            graphics.renderItem(held, slotX, slotY);
            graphics.renderItemDecorations(this.font, held, slotX, slotY);
        }

        int textX = slotX + 30;
        if (value > 0) {
            graphics.drawString(this.font,
                    Component.translatable("pet.galboss.gift.preview", "+" + value),
                    textX, slotY + 6, COLOR_TEXT, false);
            if (PetGiftTable.isFavorite(pet.getVariant(), held)) {
                graphics.drawString(this.font, Component.translatable("pet.galboss.gift.favorite"),
                        textX, slotY + 18, COLOR_GOLD, false);
            }
        } else {
            graphics.drawString(this.font, Component.translatable("pet.galboss.gift.empty"),
                    textX, slotY + 6, COLOR_DIM, false);
        }

        Component hint = Component.translatable("pet.galboss.gift.hint");
        graphics.drawString(this.font, hint,
                left + (PANEL_WIDTH - this.font.width(hint)) / 2, top + 144, COLOR_DIM, false);
    }

    private void renderChatTab(GuiGraphics graphics, FsPetEntity pet, int left, int top) {
        // 话题按钮由 init() 建好，这里只补上最近的对话
        int count = PetChatTable.topicsOf(pet.getVariant()).size();
        int baseY = top + 62 + count * 18 + 6;

        List<Component> recent = this.log.size() <= 3
                ? this.log : this.log.subList(this.log.size() - 3, this.log.size());
        for (int i = 0; i < recent.size(); i++) {
            this.drawClipped(graphics, recent.get(i), left + 12, baseY + i * 10,
                    PANEL_WIDTH - 24, COLOR_TEXT);
        }
    }

    private void renderTradeTab(GuiGraphics graphics, FsPetEntity pet, int left, int top,
                                int mouseX, int mouseY) {
        int affection = pet.getAffection();
        int discountPercent = (int) Math.round(PetTradeTable.discountOf(affection) * 100);
        graphics.drawString(this.font,
                Component.translatable("pet.galboss.trade.discount", discountPercent),
                left + 12, top + 62, discountPercent > 0 ? COLOR_ACCENT : COLOR_DIM, false);

        List<PetTradeTable.Offer> offers = PetTradeTable.offersOf(pet.getVariant());
        for (int i = 0; i < this.tradeRows.size() && i < offers.size(); i++) {
            int[] row = this.tradeRows.get(i);
            PetTradeTable.Offer offer = offers.get(i);
            boolean unlocked = offer.isUnlocked(affection);
            boolean hovered = mouseX >= row[0] && mouseX < row[0] + row[2]
                    && mouseY >= row[1] && mouseY < row[1] + row[3];

            graphics.fill(row[0], row[1], row[0] + row[2], row[1] + row[3],
                    hovered && unlocked ? 0x40FFFFFF : 0x20000000);

            ItemStack stack = offer.createStack();
            graphics.renderItem(stack, row[0] + 3, row[1] + 2);
            graphics.renderItemDecorations(this.font, stack, row[0] + 3, row[1] + 2);

            int price = PetTradeTable.priceOf(offer, affection);
            String priceText = String.valueOf(price);
            int priceX = row[0] + row[2] - 4 - this.font.width(priceText);
            graphics.drawString(this.font, priceText, priceX, row[1] + 6,
                    unlocked ? COLOR_GOLD : COLOR_DIM, false);
            graphics.renderItem(new ItemStack(offer.currency()), priceX - 20, row[1] + 2);

            if (unlocked) {
                this.drawClipped(graphics, stack.getHoverName(), row[0] + 24, row[1] + 6,
                        Math.max(16, priceX - 24 - (row[0] + 24)), COLOR_TEXT);
            } else {
                graphics.fill(row[0], row[1], row[0] + row[2], row[1] + row[3], 0x90000000);
                graphics.drawString(this.font,
                        Component.translatable("pet.galboss.trade.locked", offer.unlockAffection()),
                        row[0] + 24, row[1] + 6, COLOR_DIM, false);
            }
        }
    }

    /** 按宽度裁字，避免长句子糊出面板。 */
    private void drawClipped(GuiGraphics graphics, Component text, int x, int y, int width, int color) {
        String clipped = this.font.plainSubstrByWidth(text.getString(), width);
        graphics.drawString(this.font, clipped, x, y, color, false);
    }

    // ------------------------------------------------------------------ 交互

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && this.tab == TAB_TRADE) {
            for (int[] row : this.tradeRows) {
                if (mouseX >= row[0] && mouseX < row[0] + row[2]
                        && mouseY >= row[1] && mouseY < row[1] + row[3]) {
                    this.send(C2SPetActionPacket.ACTION_TRADE, row[4]);
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void tick() {
        super.tick();
        if (this.pet() == null) {
            this.onClose();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
