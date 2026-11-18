package com.example.galboss.pet;

import com.example.galboss.entity.FsPetEntity;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 聊天话题表。
 *
 * <p>话题按好感度解锁；**首次**聊某个话题才加好感度，之后重复聊只回话不加分。
 * 这样既能靠日常互动慢慢养，又不会被"无限复读刷满"，同时天然带着"关系推进"的叙事节奏。
 */
public final class PetChatTable {

    /**
     * 一个话题。
     *
     * @param labelKey        按钮上的短标题语言键
     * @param lineKey         宠物回话的语言键
     * @param unlockAffection 解锁所需好感度
     * @param affectionGain   首次聊天给的好感度
     */
    public record Topic(String labelKey, String lineKey, int unlockAffection, int affectionGain) {

        public boolean isUnlocked(int affection) {
            return affection >= this.unlockAffection;
        }
    }

    private static final Map<FsPetEntity.PetVariant, List<Topic>> TOPICS =
            new EnumMap<>(FsPetEntity.PetVariant.class);

    static {
        TOPICS.put(FsPetEntity.PetVariant.TOHKA, List.of(
                topic("reizein_tohka", 0, 0, 1),
                topic("reizein_tohka", 1, 30, 1),
                topic("reizein_tohka", 2, 60, 2),
                topic("reizein_tohka", 3, 90, 3)
        ));
        TOPICS.put(FsPetEntity.PetVariant.REI, List.of(
                topic("tadasugawa_rei", 0, 0, 1),
                topic("tadasugawa_rei", 1, 30, 1),
                topic("tadasugawa_rei", 2, 60, 2),
                topic("tadasugawa_rei", 3, 90, 3)
        ));
        TOPICS.put(FsPetEntity.PetVariant.IKUKO, List.of(
                topic("onabuta_ikuko", 0, 0, 1),
                topic("onabuta_ikuko", 1, 30, 1),
                topic("onabuta_ikuko", 2, 60, 2),
                topic("onabuta_ikuko", 3, 90, 3)
        ));
    }

    private PetChatTable() {
    }

    private static Topic topic(String entityId, int index, int unlockAffection, int affectionGain) {
        String base = "pet.galboss.chat." + entityId + ".";
        return new Topic(base + "topic." + index, base + "line." + index,
                unlockAffection, affectionGain);
    }

    public static List<Topic> topicsOf(FsPetEntity.PetVariant variant) {
        return TOPICS.getOrDefault(variant, List.of());
    }
}
