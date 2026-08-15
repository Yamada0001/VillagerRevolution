package dev.bettervillagers.village;

import dev.bettervillagers.profession.Profession;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillageActivityManagerTest {

    @Test
    void sequenceIsStableAndOnlyMatchingRolesParticipate() {
        assertEquals(VillageActivityManager.Activity.TRADE_FAIR,
                VillageActivityManager.nextActivity(4, 0));
        assertEquals(VillageActivityManager.Activity.DEFENSE_DRILL,
                VillageActivityManager.nextActivity(4, 1));
        assertTrue(VillageActivityManager.participates(
                VillageActivityManager.Activity.DEFENSE_DRILL, Profession.KNIGHT));
        assertFalse(VillageActivityManager.participates(
                VillageActivityManager.Activity.DEFENSE_DRILL, Profession.FARMER));
        assertTrue(VillageActivityManager.participates(
                VillageActivityManager.Activity.BUILDING_CONTEST, Profession.BUILDER));
        assertEquals("activity.trade_fair",
                VillageActivityManager.Activity.TRADE_FAIR.translationKey());
    }
}
