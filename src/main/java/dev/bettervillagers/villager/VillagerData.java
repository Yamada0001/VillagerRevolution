package dev.bettervillagers.villager;

/** 村民持久化数据（规范 8.2 villagers 表）。运行期状态由 {@code BVillager} 承载。 */
public record VillagerData(
        String uuid,
        String name,
        String profession,
        double health,
        double attack,
        String defense,
        String locationWorld,
        double locationX,
        double locationY,
        double locationZ,
        int villageId,
        boolean aiEnabled,
        String aiMemoryJson,
        long createdAt,
        long updatedAt
) {
}
