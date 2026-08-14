package dev.bettervillagers.villager;

import dev.bettervillagers.behavior.VillagerState;
import dev.bettervillagers.profession.Profession;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BVillagerTest {

    @Test
    void actionPointsRecoverAfterBeingExhausted() {
        BVillager villager = villager(1234L);

        assertFalse(villager.failedToConsumeActionPoints(100.0));
        assertTrue(villager.failedToConsumeActionPoints(1.0));

        villager.recoverActionPoints(System.currentTimeMillis() + 1_100L);

        assertFalse(villager.failedToConsumeActionPoints(1.0));
    }

    @Test
    void preservesOriginalCreationTimeWithoutAnEntity() {
        BVillager villager = villager(1234L);

        VillagerData saved = villager.toData("[]");

        assertEquals(1234L, saved.createdAt());
        assertEquals("test_world", saved.locationWorld());
        assertEquals(12.5, saved.locationX());
    }

    @Test
    void recordsStateTransitionTimeOnlyWhenStateChanges() {
        BVillager villager = villager(1234L);
        long initial = villager.stateSince();

        villager.state(VillagerState.COMBAT);
        long changed = villager.stateSince();
        villager.state(VillagerState.COMBAT);

        assertTrue(changed >= initial);
        assertEquals(changed, villager.stateSince());
    }

    @Test
    void protectedRegionSuspensionDoesNotOverwriteConfiguredAiSwitch() {
        BVillager villager = villager(1234L);

        villager.protectedRegionSuspended(true);
        assertFalse(villager.aiEnabled());
        assertTrue(villager.configuredAiEnabled());
        assertTrue(villager.toData("[]").aiEnabled());

        villager.aiEnabled(false);
        villager.protectedRegionSuspended(false);
        assertFalse(villager.aiEnabled());
        assertFalse(villager.toData("[]").aiEnabled());
    }

    @Test
    void offspringInheritanceUsesOneParentRateAndNeverInheritsKing() {
        assertEquals(Profession.FARMER, VillagerManager.selectInherited(
                Profession.FARMER, 0.3, Profession.MINER, 0.3, 0.1, 0.2));
        assertEquals(Profession.CIVILIAN, VillagerManager.selectInherited(
                Profession.FARMER, 0.3, Profession.MINER, 0.3, 0.9, 0.4));
        assertEquals(Profession.CIVILIAN, VillagerManager.selectInherited(
                Profession.KING, 1.0, Profession.FARMER, 1.0, 0.1, 0.0));
    }

    private BVillager villager(long createdAt) {
        VillagerData data = new VillagerData(
                "00000000-0000-0000-0000-000000000001", "Test", Profession.FARMER.id(),
                20.0, 2.0, "LOW", "test_world", 12.5, 64.0, -8.5,
                1, true, "[]", createdAt, createdAt);
        return new BVillager(data, null, Profession.FARMER, null);
    }
}
