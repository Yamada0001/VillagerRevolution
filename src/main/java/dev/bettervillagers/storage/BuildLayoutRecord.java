package dev.bettervillagers.storage;

/** 已完成的模板实例及其占地。 */
public record BuildLayoutRecord(
        int villageId,
        String world,
        String buildType,
        String templateId,
        int centerX,
        int centerY,
        int centerZ,
        int minX,
        int maxX,
        int minZ,
        int maxZ,
        String rotation,
        String mirror,
        String clusterId
) {
}
