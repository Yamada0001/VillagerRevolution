package dev.bettervillagers.listener;

import dev.bettervillagers.BV;
import dev.bettervillagers.command.PlayerSelection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Animals;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import io.papermc.paper.event.player.PlayerTradeEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * 村民相关事件监听（规范 3.x / 4.2：区块卸载暂停 AI，受击触发反射层）。
 * <p>
 * Bukkit 事件在所属区域线程触发，可直接调用区域线程安全的逻辑与反射层。
 * 右键村民：登记为 /bv 指令作用对象。
 */
public final class VillagerListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        // 原版玩家事件保持不取消；这里只同步注册村民相关的行为状态。
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShear(PlayerShearEntityEvent event) {
        // 原版玩家事件保持不取消；羊的 Shearable 状态由 Bukkit/Paper 处理。
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteractVillager(PlayerInteractEntityEvent event) {
        // 仅主手，避免离手重复触发
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (!(event.getRightClicked() instanceof Villager v)) {
            return;
        }
        Player p = event.getPlayer();
        if (!PlayerSelection.isSelecting(p.getUniqueId())) {
            return;
        }
        PlayerSelection.select(p.getUniqueId(), v.getUniqueId());
        String name = BV.messages().raw("villager-default");
        if (BV.villagers() != null) {
            name = BV.villagers().get(v.getUniqueId().toString())
                    .map(b -> b.name() == null || b.name().isBlank()
                            ? BV.messages().raw("villager-default") : b.name())
                    .orElse(v.getName());
        }
        if (BV.messages() != null) {
            BV.messages().send(p, "villager-selected", "name", name);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        PlayerSelection.clear(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (event.getEntity() instanceof Villager v) {
            registerSafe(v);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        if (!(event.getEntity() instanceof Villager child)
                || !(event.getMother() instanceof Villager mother)
                || !(event.getFather() instanceof Villager father)
                || BV.villagers() == null) {
            return;
        }
        BV.villagers().registerOffspring(child, mother, father);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        for (Entity e : event.getChunk().getEntities()) {
            if (e instanceof Villager v) {
                registerSafe(v);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        for (Entity e : event.getChunk().getEntities()) {
            if (e instanceof Villager v) {
                String uuid = v.getUniqueId().toString();
                if (BV.villagers() != null) {
                    BV.villagers().unload(uuid);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof Villager v) {
            String uuid = v.getUniqueId().toString();
            if (BV.villagers() != null) {
                BV.villagers().removePermanently(uuid);
            }
            return;
        }
        if (event.getEntity() instanceof Animals animal
                && animal.getLastDamageCause() instanceof EntityDamageByEntityEvent damage
                && damage.getDamager() instanceof Villager butcher
                && BV.villagers() != null
                && BV.villagers().get(butcher.getUniqueId().toString())
                .map(v -> v.profession() == dev.bettervillagers.profession.Profession.BUTCHER)
                .orElse(false)) {
            for (org.bukkit.inventory.ItemStack drop : java.util.List.copyOf(event.getDrops())) {
                butcher.getInventory().addItem(drop.clone()).values().forEach(leftover ->
                        butcher.getWorld().dropItemNaturally(butcher.getLocation(), leftover));
            }
            event.getDrops().clear();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Villager attacker && BV.villagers() != null) {
            BV.villagers().get(attacker.getUniqueId().toString()).ifPresent(bv ->
                    dev.bettervillagers.profession.EquipmentDurability.damage(
                            attacker, bv.professionData(), 1.0));
        }
        if (!(event.getEntity() instanceof Villager v)) {
            return;
        }
        if (BV.behavior() == null || BV.villagers() == null) {
            return;
        }
        BV.villagers().get(v.getUniqueId().toString()).ifPresent(bv ->
                BV.behavior().onDamaged(bv, event.getDamager()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTrade(PlayerTradeEvent event) {
        if (!(event.getMerchant() instanceof Villager villager) || BV.villagers() == null) {
            return;
        }
        if (BV.regions() != null && BV.regions().isProtected(villager.getLocation())) {
            return;
        }
        BV.villagers().get(villager.getUniqueId().toString()).ifPresent(bv -> {
            if (BV.socialEngine() != null) {
                BV.socialEngine().recordPlayerTrade(event.getPlayer(), bv, villager, event.getTrade());
            } else {
                dev.bettervillagers.profession.EquipmentDurability.repair(
                        villager, bv.professionData(), Math.max(0.0, BV.config().raw().getDouble(
                                "gameplay.equipment.repair-per-trade", 10.0)));
            }
        });
    }

    /** 幂等注册（已追踪则跳过）。 */
    private void registerSafe(Villager v) {
        if (BV.villagers() == null) {
            return;
        }
        BV.villagers().register(v);
    }
}
