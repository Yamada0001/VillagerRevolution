package dev.bettervillagers.behavior.task;

import dev.bettervillagers.profession.Profession;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerPolicyTest {

    @Test
    void limitsTransfersToRoleSpecificInputsAndOutputs() {
        assertTrue(ContainerPolicy.supports(Profession.FARMER));
        assertFalse(ContainerPolicy.supports(Profession.KING));
        assertTrue(ContainerPolicy.input(Profession.FARMER, Material.WHEAT_SEEDS));
        assertTrue(ContainerPolicy.output(Profession.FARMER, Material.WHEAT));
        assertFalse(ContainerPolicy.output(Profession.FARMER, Material.DIAMOND));
        assertTrue(ContainerPolicy.output(Profession.MINER, Material.DIAMOND_ORE));
        assertFalse(ContainerPolicy.output(Profession.MINER, Material.EMERALD));
        assertTrue(ContainerPolicy.containerMaterial(Material.CHEST));
        assertTrue(ContainerPolicy.containerMaterial(Material.RED_SHULKER_BOX));
        assertFalse(ContainerPolicy.containerMaterial(Material.CRAFTING_TABLE));
    }
}
