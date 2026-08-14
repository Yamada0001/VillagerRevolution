package dev.bettervillagers.redstone;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RegionVisualizerTest {

    @Test
    void capsSamplingForExtremelyLargeRegions() {
        assertEquals(1, RegionVisualizer.lineSteps(0));
        assertEquals(10, RegionVisualizer.lineSteps(30));
        assertEquals(169, RegionVisualizer.lineSteps(Integer.MAX_VALUE));
    }
}
