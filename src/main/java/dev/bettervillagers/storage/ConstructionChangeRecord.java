package dev.bettervillagers.storage;

/** Durable snapshot of a block before a construction job modified it. */
public record ConstructionChangeRecord(
        String jobId,
        int sequence,
        String world,
        int x,
        int y,
        int z,
        String oldBlockData
) {
}
