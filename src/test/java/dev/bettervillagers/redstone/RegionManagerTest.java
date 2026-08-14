package dev.bettervillagers.redstone;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionManagerTest {

    @Test
    void configuredRegionsAreNormalizedAndIndexedByWorld() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("protected-regions", List.of(Map.of(
                "name", "spawn-tech", "world", "World",
                "x1", 20, "y1", 80, "z1", 20,
                "x2", 10, "y2", 60, "z2", 10,
                "owner", "Admin")));
        RegionManager manager = new RegionManager(true);

        manager.configure(config);

        assertTrue(manager.isProtected("world", 15, 70, 15));
        assertFalse(manager.isProtected("other", 15, 70, 15));
        assertTrue(manager.findByName("SPAWN-TECH").isPresent());
    }
}
