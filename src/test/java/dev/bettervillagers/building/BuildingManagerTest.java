package dev.bettervillagers.building;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildingManagerTest {

    @Test
    void detectsHousingShortageOnlyAfterPopulationThreshold() {
        BuildingManager.HousingStatus belowThreshold = BuildingManager.evaluateHousing(11, 0, 12, 4, 1);
        assertFalse(belowThreshold.shortage());

        BuildingManager.HousingStatus shortage = BuildingManager.evaluateHousing(20, 3, 12, 4, 1);
        assertEquals(6, shortage.requiredHousingUnits());
        assertTrue(shortage.shortage());

        BuildingManager.HousingStatus sufficient = BuildingManager.evaluateHousing(20, 6, 12, 4, 1);
        assertFalse(sufficient.shortage());
    }

    @Test
    void sanitizesInvalidHousingConfiguration() {
        BuildingManager.HousingStatus status = BuildingManager.evaluateHousing(12, -3, 0, 0, -1);
        assertEquals(0, status.housingUnits());
        assertEquals(12, status.requiredHousingUnits());
        assertTrue(status.shortage());
    }
}
