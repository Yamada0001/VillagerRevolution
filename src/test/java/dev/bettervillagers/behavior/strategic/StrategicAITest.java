package dev.bettervillagers.behavior.strategic;

import dev.bettervillagers.building.BuildingManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StrategicAITest {

    @Test
    void housingBatchFillsAvailableParallelSlotsWithoutOverbuilding() {
        var largeShortage = new BuildingManager.HousingStatus(24, 2, 7, true);
        assertEquals(4, StrategicAI.housingBatchSize(largeShortage, 0, 4));
        assertEquals(2, StrategicAI.housingBatchSize(largeShortage, 2, 4));

        var oneMissing = new BuildingManager.HousingStatus(16, 4, 5, true);
        assertEquals(1, StrategicAI.housingBatchSize(oneMissing, 0, 4));
    }

    @Test
    void housingBatchDoesNothingWhenHousingIsSufficient() {
        var sufficient = new BuildingManager.HousingStatus(16, 5, 5, false);
        assertEquals(0, StrategicAI.housingBatchSize(sufficient, 0, 4));
    }
}
