package dev.bettervillagers.behavior.block;

import dev.bettervillagers.BV;
import dev.bettervillagers.villager.BVillager;
import org.bukkit.Location;
import org.bukkit.Material;

public final class BlockInteractionEngine {

    private static final double BREAK_COST = 10.0;
    private static final double PLACE_COST = 8.0;
    private static final double INTERACT_COST = 2.0;

    private final long cooldownMillis;

    public BlockInteractionEngine(long cooldownSeconds) {
        this.cooldownMillis = cooldownSeconds * 1000L;
    }

    public void placeAt(BVillager bv, Location location, Material material) {
        if (disallowed(bv, location, material, BVillager.OP_PLACE)) {
            return;
        }
        if (bv.failedToConsumeActionPoints(PLACE_COST)) {
            return;
        }
        bv.lastBlockOp(BVillager.OP_PLACE, System.currentTimeMillis());
        BV.scheduler().runAtRegion(location, () -> location.getBlock().setType(material, false));
    }

    public void breakAt(BVillager bv, Location location) {
        Material target = location.getBlock().getType();
        if (disallowed(bv, location, target, BVillager.OP_BREAK)) {
            return;
        }
        if (bv.failedToConsumeActionPoints(BREAK_COST)) {
            return;
        }
        bv.lastBlockOp(BVillager.OP_BREAK, System.currentTimeMillis());
        BV.scheduler().runAtRegion(location, () -> location.getBlock().setType(Material.AIR, false));
    }

    public void interactAt(BVillager bv, Location location, Material material) {
        if (disallowed(bv, location, material, BVillager.OP_INTERACT)) {
            return;
        }
        if (bv.failedToConsumeActionPoints(INTERACT_COST)) {
            return;
        }
        bv.lastBlockOp(BVillager.OP_INTERACT, System.currentTimeMillis());
    }

    private boolean disallowed(BVillager bv, Location location, Material material, int opKind) {
        return (BV.regions() != null && BV.regions().isProtected(location))
                || !BV.professions().isWhitelisted(bv.profession(), material)
                || System.currentTimeMillis() - bv.lastBlockOp(opKind) < cooldownMillis;
    }
}
