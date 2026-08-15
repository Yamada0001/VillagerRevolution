package dev.bettervillagers.building;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainAnalyzerTest {

    @Test
    void clearanceRejectsStructuresLiquidsAndTreeCanopies() {
        assertTrue(TerrainAnalyzer.isClearanceObstacle(Material.COBBLESTONE));
        assertTrue(TerrainAnalyzer.isClearanceObstacle(Material.OAK_LOG));
        assertTrue(TerrainAnalyzer.isClearanceObstacle(Material.OAK_LEAVES));
        assertTrue(TerrainAnalyzer.isClearanceObstacle(Material.WATER));
    }

    @Test
    void clearanceAllowsAirAndReplaceableDecoration() {
        assertFalse(TerrainAnalyzer.isClearanceObstacle(Material.AIR));
        assertFalse(TerrainAnalyzer.isClearanceObstacle(Material.SHORT_GRASS));
        assertFalse(TerrainAnalyzer.isClearanceObstacle(Material.DANDELION));
        assertFalse(TerrainAnalyzer.isClearanceObstacle(Material.VINE));
    }
}
