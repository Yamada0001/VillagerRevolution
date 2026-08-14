package dev.bettervillagers.behavior.block;

import dev.bettervillagers.BV;
import dev.bettervillagers.villager.BVillager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.function.BooleanSupplier;
import java.util.function.Function;

public final class BlockInteractionEngine {

    private static final double BREAK_COST = 10.0;
    private static final double PLACE_COST = 8.0;
    private static final double INTERACT_COST = 2.0;

    private final long cooldownMillis;

    public BlockInteractionEngine(long cooldownSeconds) {
        this.cooldownMillis = cooldownSeconds * 1000L;
    }

    public void placeAt(BVillager bv, Location location, Material material) {
        placeAt(bv, location, material, () -> true);
    }

    /** Places only when the caller can commit any required inventory cost. */
    public void placeAt(BVillager bv, Location location, Material material, BooleanSupplier commitCost) {
        if (disallowed(bv, location, material, BVillager.OP_PLACE)) {
            return;
        }
        BV.scheduler().runAtRegion(location, () -> {
            Block block = location.getBlock();
            var entity = bv.entity();
            if (entity == null || !entity.isValid() || disallowed(bv, location, material, BVillager.OP_PLACE)) {
                return;
            }
            EntityChangeBlockEvent event = new EntityChangeBlockEvent(entity, block, material.createBlockData());
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled() || bv.failedToConsumeActionPoints(PLACE_COST)
                    || !commitCost.getAsBoolean()) {
                return;
            }
            block.setBlockData(event.getBlockData(), false);
            bv.lastBlockOp(BVillager.OP_PLACE, System.currentTimeMillis());
            dev.bettervillagers.profession.EquipmentDurability.damage(entity, bv.professionData(), 1.0);
        });
    }

    public void breakAt(BVillager bv, Location location) {
        if (!BV.config().feature("block-interaction")) {
            return;
        }
        BV.scheduler().runAtRegion(location, () -> {
            Block block = location.getBlock();
            Material current = block.getType();
            var entity = bv.entity();
            if (entity == null || !entity.isValid() || disallowed(bv, location, current, BVillager.OP_BREAK)) {
                return;
            }
            EntityChangeBlockEvent event = new EntityChangeBlockEvent(entity, block, Material.AIR.createBlockData());
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled() || bv.failedToConsumeActionPoints(BREAK_COST)) {
                return;
            }
            ItemStack tool = entity.getEquipment().getItemInMainHand();
            var drops = block.getDrops(tool, entity).stream().map(ItemStack::clone).toList();
            block.setBlockData(event.getBlockData(), false);
            for (ItemStack drop : drops) {
                entity.getInventory().addItem(drop).values().forEach(leftover ->
                        entity.getWorld().dropItemNaturally(entity.getLocation(), leftover));
            }
            bv.lastBlockOp(BVillager.OP_BREAK, System.currentTimeMillis());
            dev.bettervillagers.profession.EquipmentDurability.damage(entity, bv.professionData(), 1.0);
        });
    }

    public boolean interactAt(BVillager bv, Location location, Material material) {
        if (disallowed(bv, location, material, BVillager.OP_INTERACT)) {
            return false;
        }
        if (bv.failedToConsumeActionPoints(INTERACT_COST)) {
            return false;
        }
        bv.lastBlockOp(BVillager.OP_INTERACT, System.currentTimeMillis());
        if (bv.entity() != null) {
            dev.bettervillagers.profession.EquipmentDurability.damage(bv.entity(), bv.professionData(), 0.25);
        }
        return true;
    }

    /** Accesses one live container from its owning region thread. */
    public boolean accessContainer(BVillager bv, Location location, Function<Inventory, Boolean> operation) {
        if (!BV.config().feature("block-interaction") || operation == null
                || !Bukkit.isOwnedByCurrentRegion(location)
                || (BV.regions() != null && BV.regions().isProtected(location))
                || System.currentTimeMillis() - bv.lastBlockOp(BVillager.OP_INTERACT) < cooldownMillis) {
            return false;
        }
        var entity = bv.entity();
        if (entity == null || !entity.isValid() || entity.getWorld() != location.getWorld()
                || entity.getLocation().distanceSquared(location) > 16.0) {
            return false;
        }
        Block block = location.getBlock();
        if (!(block.getState() instanceof Container container)
                || bv.failedToConsumeActionPoints(INTERACT_COST)) {
            return false;
        }
        Inventory inventory = container instanceof Chest chest
                ? chest.getBlockInventory() : container.getInventory();
        boolean changed = false;
        try {
            changed = Boolean.TRUE.equals(operation.apply(inventory));
            if (!changed) {
                return false;
            }
            bv.lastBlockOp(BVillager.OP_INTERACT, System.currentTimeMillis());
            dev.bettervillagers.profession.EquipmentDurability.damage(entity, bv.professionData(), 0.25);
            return true;
        } finally {
            if (!changed) {
                bv.refundActionPoints(INTERACT_COST);
            }
        }
    }

    private boolean disallowed(BVillager bv, Location location, Material material, int opKind) {
        return !BV.config().feature("block-interaction")
                || (BV.regions() != null && BV.regions().isProtected(location))
                || !BV.professions().isWhitelisted(bv.profession(), material)
                || System.currentTimeMillis() - bv.lastBlockOp(opKind) < cooldownMillis;
    }
}
