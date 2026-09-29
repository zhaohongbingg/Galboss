package com.example.galboss.item;

import com.example.galboss.entity.ModEntities;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, "galboss");

    // ---------------------------------------------------------------- 生成蛋
    //
    // 配色直接从各自皮肤的像素统计里取，主色 = 头发（角色最醒目的特征），
    // 副色 = 服装上的高对比色。括号里是该颜色在对应区域的占比。

    /** 冷泉院 桐香：青绿发色 + 粉色格纹裙。皮肤里没有紫色，旧的紫色配色是错的。 */
    public static final RegistryObject<Item> REIZEIN_TOHKA_SPAWN_EGG = ITEMS.register(
            "reizein_tohka_spawn_egg",
            () -> new ForgeSpawnEggItem(
                    ModEntities.REIZEIN_TOHKA::get,
                    0x449783,  // 头发（头部区域 49%）
                    0xEFD6CE,  // 下身格纹（下身区域 30%）
                    new Item.Properties()
            )
    );

    /** オナブタ 郁子：亮蓝发色 + 白色水手服。 */
    public static final RegistryObject<Item> ONABUTA_IKUKO_SPAWN_EGG = ITEMS.register(
            "onabuta_ikuko_spawn_egg",
            () -> new ForgeSpawnEggItem(
                    ModEntities.ONABUTA_IKUKO::get,
                    0x6189BB,  // 头发（头部区域 39%）
                    0xE6E9F0,  // 水手服上衣（躯干区域 14%）
                    new Item.Properties()
            )
    );

    /** 只須川 レイ：深棕发色 + 白色水手服（与郁子同款制服）。 */
    public static final RegistryObject<Item> TADASUGAWA_REI_SPAWN_EGG = ITEMS.register(
            "tadasugawa_rei_spawn_egg",
            () -> new ForgeSpawnEggItem(
                    ModEntities.TADASUGAWA_REI::get,
                    0x483830,  // 头发（头部区域 45%）
                    0xE6E9F0,  // 水手服上衣（躯干区域 17%）
                    new Item.Properties()
            )
    );

    // ------------------------------------------------- 手持武器（用于渲染层）
    //
    // 这两个既是模型载体也是实际物品：怪物手持武器走原版物品系统
    // （见 IkukoKatanaLayer / ReiGunLayer），物品模型在
    // models/item/ikuko_katana.json、rei_gun.json，
    // 由 tools/blockbench/geo_to_item_model.mjs 从 geo/ 生成 —— 别手改那两个 json。

    public static final RegistryObject<Item> IKUKO_KATANA = ITEMS.register(
            "ikuko_katana",
            () -> new Item(new Item.Properties())
    );

    public static final RegistryObject<Item> REI_GUN = ITEMS.register(
            "rei_gun",
            () -> new Item(new Item.Properties())
    );

    // ------------------------------------------------------------ 终局奖励素材

    /** 冷泉院 桐香掉落：学生会徽章 */
    public static final RegistryObject<Item> TOHKA_BADGE = ITEMS.register(
            "tohka_badge", () -> new Item(new Item.Properties()));

    /** 只須川 レイ掉落：风纪臂章 */
    public static final RegistryObject<Item> REI_ARMBAND = ITEMS.register(
            "rei_armband", () -> new Item(new Item.Properties()));

    /** オナブタ 郁子掉落：战斗勋章 */
    public static final RegistryObject<Item> IKUKO_MEDAL = ITEMS.register(
            "ikuko_medal", () -> new Item(new Item.Properties()));

    /** 三枚勋章合成的核心素材 */
    public static final RegistryObject<Item> FS_BIG_THREE_CORE = ITEMS.register(
            "fs_big_three_core", () -> new Item(new Item.Properties()));

    /** 终局饰品：低血量触发增益 */
    public static final RegistryObject<Item> FS_BADGE = ITEMS.register(
            "fs_badge", () -> new Item(new Item.Properties().stacksTo(1).fireResistant()));

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}
