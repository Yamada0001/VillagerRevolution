package dev.bettervillagers.storage;

import dev.bettervillagers.villager.VillagerData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillagerRecoveryRecordTest {

    @Test
    void roundTripsCompleteVillagerSnapshotAndTransactionIntent() {
        VillagerData data = new VillagerData(
                "00000000-0000-0000-0000-000000000001", "Recovery", "farmer",
                40.0, 5.0, "LOW", "world", 1.5, 64.0, -2.5,
                3, false, "[{\"event\":\"saved\"}]", 10L, 20L);

        VillagerRecoveryRecord decoded = VillagerRecoveryRecord.fromJson(
                new VillagerRecoveryRecord(data, true, true).toJson());

        assertEquals(data, decoded.data());
        assertTrue(decoded.attachToVillage());
        assertTrue(decoded.claimKing());
    }

    @Test
    void rejectsUnknownRecoveryVersion() {
        assertThrows(com.google.gson.JsonParseException.class,
                () -> VillagerRecoveryRecord.fromJson("{\"version\":999,\"data\":{}}"));
    }
}
