package com.example.galboss.entity;

import com.example.galboss.stake.StakeFx;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 炼狱七桩 · 玛门（贪婪）—— 资源掠夺、弹幕压制。
 *
 * <table>
 *   <tr><td>核心被动</td><td><b>黄金律法</b>：命中玩家时抽走经验，转成自己的伤害吸收（黄心）</td></tr>
 *   <tr><td>主动 1</td><td><b>黄金锁链</b>：把玩家拽到身边，并强制调换主手与背包随机一格的物品</td></tr>
 *   <tr><td>主动 2</td><td><b>挥金如土</b>：一圈金锭弹幕向外覆盖，范围内全部吃伤害</td></tr>
 * </table>
 */
public class MammonEntity extends AbstractPurgatoryStakeEntity {

    private static final int XP_STEAL = 20;
    private static final float ABSORPTION_PER_XP = 0.3F;
    private static final float MAX_ABSORPTION = 20.0F;

    private static final float COIN_DAMAGE = 6.0F;
    private static final double COIN_RING = 8.0D;

    public MammonEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
    }

    @Override
    public String assetId() {
        return "mammon";
    }

    @Override
    protected int skillOneCooldown() {
        return 120;
    }

    @Override
    protected int skillTwoCooldown() {
        return 200;
    }

    // ---------------------------------------------------------------- 被动：黄金律法

    @Override
    protected void onStakeHit(Entity target) {
        if (!(target instanceof Player player) || !(this.level() instanceof ServerLevel level)) {
            return;
        }
        int available = Math.max(0, Math.min(XP_STEAL, player.totalExperience));
        if (available <= 0) {
            return;
        }
        player.giveExperiencePoints(-available);
        float gain = available * ABSORPTION_PER_XP;
        this.setAbsorptionAmount(Math.min(MAX_ABSORPTION, this.getAbsorptionAmount() + gain));
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                player.getX(), player.getY() + 1.0, player.getZ(), 8, 0.4, 0.4, 0.4, 0.02);
        StakeFx.sound(level, player.position(), SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0F, 0.7F);
    }

    // ---------------------------------------------------------------- 主动 1：黄金锁链

    @Override
    protected void castSkillOne(ServerLevel level, LivingEntity target) {
        Vec3 from = this.getEyePosition();
        StakeFx.line(level, from, target.getEyePosition(), ParticleTypes.WAX_ON, 2);
        StakeFx.sound(level, from, SoundEvents.CHAIN_BREAK, 1.4F, 0.9F);

        // 拽到身边
        StakeFx.pull(target, this.position(), 1.2D);
        if (target instanceof Player player) {
            swapInventory(player);
        }
        StakeFx.hurt(level, this, target, 4.0F);
    }

    /** 主手与背包随机一格调换（简单实现的「搜刮」）。 */
    private static void swapInventory(Player player) {
        var inventory = player.getInventory();
        int main = inventory.selected;
        int other = player.getRandom().nextInt(inventory.items.size());
        if (other == main) {
            other = (main + 1) % inventory.items.size();
        }
        // 必须走 Inventory#setItem：直接改 items 列表不会更新脏标记，客户端快捷栏不同步（实测踩过）
        ItemStack mainStack = inventory.getItem(main);
        ItemStack otherStack = inventory.getItem(other);
        inventory.setItem(main, otherStack);
        inventory.setItem(other, mainStack);
        player.containerMenu.broadcastChanges();
    }

    // ---------------------------------------------------------------- 主动 2：挥金如土

    @Override
    protected void castSkillTwo(ServerLevel level, LivingEntity target) {
        Vec3 center = this.position();
        for (int wave = 0; wave < 4; wave++) {
            double radius = 2.0D + wave * 2.0D;
            StakeFx.ring(level, center.add(0, 0.8, 0), radius, 20, ParticleTypes.WAX_ON);
            StakeFx.ring(level, center.add(0, 0.8, 0), radius, 12, ParticleTypes.GLOW);
        }
        StakeFx.sound(level, center, SoundEvents.CHAIN_BREAK, 1.4F, 1.3F);
        com.example.galboss.network.ModNetwork.sendScreenShake(level, center, COIN_RING + 4.0D, 0.25F, 6);

        for (LivingEntity hit : StakeFx.inRadius(level, this, center, COIN_RING)) {
            StakeFx.hurt(level, this, hit, COIN_DAMAGE);
            StakeFx.knockAway(hit, center, 0.35D, 0.25D);
        }
        com.example.galboss.network.ModNetwork.sendRedTruthLiteral(level, center, 40.0D,
                "「钱是我的，命也是我的。」", 50);
    }
}
