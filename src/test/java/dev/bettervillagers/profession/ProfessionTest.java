package dev.bettervillagers.profession;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfessionTest {

    @Test
    void exactLookupDoesNotCoerceInvalidCommandInputToCivilian() {
        assertEquals(Profession.FARMER, Profession.find("Farmer").orElseThrow());
        assertTrue(Profession.find("not-a-profession").isEmpty());
    }
}
