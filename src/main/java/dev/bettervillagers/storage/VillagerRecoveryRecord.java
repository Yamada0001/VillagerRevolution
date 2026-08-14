package dev.bettervillagers.storage;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import dev.bettervillagers.villager.VillagerData;

/** Complete, versioned snapshot used when a villager database write fails. */
public record VillagerRecoveryRecord(
        int version,
        VillagerData data,
        boolean attachToVillage,
        boolean claimKing
) {

    public static final int CURRENT_VERSION = 1;
    private static final Gson GSON = new Gson();

    public VillagerRecoveryRecord(VillagerData data, boolean attachToVillage, boolean claimKing) {
        this(CURRENT_VERSION, data, attachToVillage, claimKing);
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public static VillagerRecoveryRecord fromJson(String json) {
        VillagerRecoveryRecord record = GSON.fromJson(json, VillagerRecoveryRecord.class);
        if (record == null || record.version() != CURRENT_VERSION || record.data() == null
                || record.data().uuid() == null || record.data().uuid().isBlank()) {
            throw new JsonParseException("Invalid villager recovery record");
        }
        return record;
    }
}
