package dev.bettervillagers.listener;

import dev.bettervillagers.BV;
import dev.bettervillagers.command.PlayerSelection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.VillagerTradeEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.Material;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
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
    public void onInteractAnimal(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof org.bukkit.entity.Animals animal)) {
            return;
        }
        Material held = event.getPlayer().getInventory().getItemInMainHand().getType();
        if (held == Material.BUCKET || (animal instanceof org.bukkit.entity.Sheep && held == Material.SHEARS)) {
            // 牛奶/剪毛保留原版交互，不伪造玩家事件。
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrade(VillagerTradeEvent event) {
        if (BV.socialEngine() == null || BV.villagers() == null || event.getWhoClicked() == null) {
            return;
        }
        Villager villager = event.getVillager();
        BV.villagers().get(villager.getUniqueId().toString())
                .ifPresent(bv -> BV.socialEngine().recordTrade(bv, 1));
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
                    BV.villagers().unregister(uuid);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof Villager v) {
            String uuid = v.getUniqueId().toString();
            if (BV.villagers() != null) {
                BV.villagers().unregister(uuid);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Villager v)) {
            return;
        }
        if (BV.behavior() == null || BV.villagers() == null) {
            return;
        }
        BV.villagers().get(v.getUniqueId().toString()).ifPresent(bv ->
                BV.behavior().onDamaged(bv, event.getDamager()));
    }

    /** 幂等注册（已追踪则跳过）。 */
    private void registerSafe(Villager v) {
        if (BV.villagers() == null) {
            return;
        }
        BV.villagers().register(v);
    }
}
