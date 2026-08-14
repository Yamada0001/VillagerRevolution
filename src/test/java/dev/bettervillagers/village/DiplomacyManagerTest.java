package dev.bettervillagers.village;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DiplomacyManagerTest {

    @Test
    void decisionParserAcceptsOnlyOneProtocolToken() {
        assertEquals(DiplomacyManager.Relation.ALLY, DiplomacyManager.parseDecision(" ally "));
        assertEquals(DiplomacyManager.Relation.NEUTRAL, DiplomacyManager.parseDecision("NEUTRAL"));
        assertEquals(DiplomacyManager.Relation.ENEMY, DiplomacyManager.parseDecision("enemy"));
        assertNull(DiplomacyManager.parseDecision("ALLY because it is safer"));
        assertNull(DiplomacyManager.parseDecision(""));
        assertNull(DiplomacyManager.parseDecision(null));
    }
}
