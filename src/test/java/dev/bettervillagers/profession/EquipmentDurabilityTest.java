package dev.bettervillagers.profession;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EquipmentDurabilityTest {

    @Test
    void durabilityNeverDropsBelowZeroOrIncreasesFromNegativeLoss() {
        assertEquals(0.0, EquipmentDurability.nextDurability(3.0, 5.0));
        assertEquals(40.0, EquipmentDurability.nextDurability(40.0, -5.0));
        assertEquals(90.0, EquipmentDurability.nextDurability(150.0, 10.0));
    }
}
