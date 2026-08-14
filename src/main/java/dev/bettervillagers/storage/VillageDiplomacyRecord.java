package dev.bettervillagers.storage;

/** Persisted relation between two distinct villages. */
public record VillageDiplomacyRecord(
        int villageA,
        int villageB,
        String relation,
        long updatedAt
) {
}
