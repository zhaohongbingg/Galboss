package com.example.galboss.item;

import com.example.galboss.entity.FsPetEntity;
import com.example.galboss.entity.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.level.Level;

/**
 * BOSS 掉落物 → 召唤对应的宠物版本。
 *
 * <p>三个掉落物各绑一个变体，直接复用它们，不额外加物品：
 * <ul>
 *   <li>学生会徽章 → 冷泉院 桐香</li>
 *   <li>风纪臂章   → 只須川 レイ</li>
 *   <li>战斗勋章   → オナブタ 郁子</li>
 * </ul>
 *
 * <p>右键地面生成（对着空气右键则生成在视线前方 2 格），召唤出来的宠物自动认主。
 * 仍然可以拿去合成 FS Big Three Core，合成表不受影响。
 */
public class PetSummonItem extends Item {

    private final FsPetEntity.PetVariant variant;

    public PetSummonItem(FsPetEntity.PetVariant variant, Properties properties) {
        super(properties);
        this.variant = variant;
    }

    public FsPetEntity.PetVariant getVariant() {
        return this.variant;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        BlockPos pos = context.getClickedPos().relative(context.getClickedFace());
        return this.summon(context.getLevel(), context.getPlayer(), context.getItemInHand(), pos);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        // 对着空气右键：生成在视线前方两格，别糊自己脸上
        BlockPos pos = BlockPos.containing(
                player.getX() + player.getLookAngle().x * 2.0,
                player.getY(),
                player.getZ() + player.getLookAngle().z * 2.0);
        InteractionResult result = this.summon(level, player, stack, pos);
        return result.consumesAction()
                ? InteractionResultHolder.success(stack)
                : InteractionResultHolder.pass(stack);
    }

    private InteractionResult summon(Level level, Player player, ItemStack stack, BlockPos pos) {
        if (player == null) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        BlockPos spawnPos = findFreeSpot(level, pos);
        FsPetEntity pet = ModEntities.FS_PET.get().create(level);
        if (pet == null) {
            return InteractionResult.FAIL;
        }

        pet.moveTo(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5,
                player.getYRot(), 0.0f);
        pet.setVariant(this.variant);
        pet.setTame(true);
        pet.setOwnerUUID(player.getUUID());
        pet.setSitting(false);
        pet.setCustomName(Component.translatable(this.variant.getNameKey()));
        pet.setPersistenceRequired();

        level.addFreshEntity(pet);
        level.playSound(null, pet.blockPosition(), SoundEvents.ENDERMAN_TELEPORT,
                SoundSource.NEUTRAL, 0.6f, 1.4f);

        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        player.displayClientMessage(
                Component.translatable("pet.galboss.summon", pet.getName()), true);
        return InteractionResult.CONSUME;
    }

    /** 从目标位置往上找一块不卡住的地方，避免宠物生成在方块里。 */
    private static BlockPos findFreeSpot(Level level, BlockPos start) {
        BlockPos pos = start;
        for (int i = 0; i < 3 && !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty(); i++) {
            pos = pos.above();
        }
        return pos;
    }
}
