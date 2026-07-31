package dev.bettervillagers.listener;

import dev.bettervillagers.BV;
import dev.bettervillagers.i18n.MessageService;
import dev.bettervillagers.village.Village;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家进入/离开村庄接收范围提示。
 * <p>
 * 检测流程已全异步化：移动事件仅做轻量节流，重计算（村庄范围校验、国王/人口查询）
 * 在异步线程完成；UI（标题/ActionBar）通过实体调度器回到玩家所在区域线程发送，
 * 不阻塞主线程/区域线程。
 */
public final class VillageEntryListener implements Listener {

    private final Map<UUID, Integer> inside = new ConcurrentHashMap<>();
    /** 节流：每个玩家至少间隔 N tick 才重新计算一次（避免高频移动卡主线程）。 */
    private final Map<UUID, Long> lastCheck = new ConcurrentHashMap<>();
    private static final long THROTTLE_TICKS = 10L;

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (sameBlock(event.getFrom(), event.getTo())) {
            return;
        }
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastCheck.get(uuid);
        if (last != null && now - last < THROTTLE_TICKS * 50L) {
            return;
        }
        lastCheck.put(uuid, now);

        // 异步做范围/归属/唯一性校验，避免主线程阻塞
        Location to = event.getTo().clone();
        // world 名在主线程预先取好，避免异步线程访问游戏 API（规范：异步禁止访问世界）
        final String worldName = to.getWorld() == null ? null : to.getWorld().getName();
        int previousId = inside.getOrDefault(uuid, -1);
        BV.scheduler().runAsync(() -> {
            Village current = findVillageAsync(to, worldName);
            int currentId = current == null ? -1 : current.id();
            if (currentId == previousId) {
                // 同一村庄内：持续刷新底部栏（需回到玩家区域线程）
                if (current != null) {
                    refreshActionBar(player, current);
                }
                return;
            }
            if (current == null) {
                inside.remove(uuid);
                clearActionBar(player);
                return;
            }
            inside.put(uuid, currentId);
            showEntry(player, current);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        inside.remove(uuid);
        lastCheck.remove(uuid);
    }

    private void showEntry(Player player, Village current) {
        // 标题显示也回到玩家所在区域线程发送（原生 sendTitle 线程要求）
        BV.scheduler().runForEntity(player, () -> {
            if (!BV.config().villageEntryTitleEnabled()) {
                return;
            }
            String unknown = BV.messages().raw("village-entry-unknown");
            String name = villageName(current);
            String king = BV.villages().resolveKingName(current.id());
            if (king == null || king.isBlank() || king.equals("-")) {
                king = unknown;
            }
            String title = BV.messages().raw("village-entry-title").replace("{village}", name);
            String subtitle = BV.messages().raw("village-entry-subtitle")
                    .replace("{king}", king)
                    .replace("{pop}", String.valueOf(BV.villages().countVillagersInVillage(current.id())));
            player.showTitle(Title.title(
                    MessageService.deserialize(title),
                    MessageService.deserialize(subtitle),
                    Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofSeconds(1))));
            String actionBar = BV.messages().raw("village-entry-actionbar").replace("{village}", name);
            player.sendActionBar(MessageService.deserialize(actionBar));
        }, null);
    }

    private void refreshActionBar(Player player, Village current) {
        BV.scheduler().runForEntity(player, () -> {
            String actionBar = BV.messages().raw("village-entry-actionbar")
                    .replace("{village}", villageName(current));
            player.sendActionBar(MessageService.deserialize(actionBar));
        }, null);
    }

    private void clearActionBar(Player player) {
        BV.scheduler().runForEntity(player, () ->
                player.sendActionBar(MessageService.deserialize("")), null);
    }

    private String villageName(Village current) {
        return current.name() == null || current.name().isBlank()
                ? BV.messages().raw("village-id-format").replace("{id}", String.valueOf(current.id()))
                : current.name();
    }

    /**
     * 异步检测：纯内存遍历村庄列表 + 空间校验，不触碰游戏世界对象（worldName 由调用方在主线程预先取好）。
     */
    private Village findVillageAsync(Location location, String worldName) {
        if (BV.villages() == null || worldName == null) {
            return null;
        }
        int extra = BV.config().villageEntryRangeExtra();
        for (Village village : BV.villages().all()) {
            if (!village.world().equalsIgnoreCase(worldName)) {
                continue;
            }
            double dx = location.getX() - village.centerX();
            double dy = location.getY() - village.centerY();
            double dz = location.getZ() - village.centerZ();
            double radius = village.radius() + extra;
            if (dx * dx + dy * dy + dz * dz <= radius * radius) {
                return village;
            }
        }
        return null;
    }

    private boolean sameBlock(Location a, Location b) {
        return a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ()
                && a.getWorld() == b.getWorld();
    }
}
