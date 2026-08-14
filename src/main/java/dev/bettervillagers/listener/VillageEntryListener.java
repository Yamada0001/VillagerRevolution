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
    private final Map<UUID, Long> checkSequences = new ConcurrentHashMap<>();
    private final Map<UUID, Position> latestPositions = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> trailingChecks = ConcurrentHashMap.newKeySet();
    private static final long THROTTLE_TICKS = 10L;

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (sameBlock(event.getFrom(), event.getTo())) {
            return;
        }
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Location to = event.getTo();
        Position position = new Position(to.getWorld() == null ? null : to.getWorld().getName(),
                to.getX(), to.getY(), to.getZ());
        latestPositions.put(uuid, position);
        long sequence = checkSequences.merge(uuid, 1L, Long::sum);
        Long last = lastCheck.get(uuid);
        if (last != null && now - last < THROTTLE_TICKS * 50L) {
            scheduleTrailingCheck(player, uuid, THROTTLE_TICKS * 50L - (now - last));
            return;
        }
        lastCheck.put(uuid, now);
        checkPosition(player, uuid, position, sequence);
    }

    private void scheduleTrailingCheck(Player player, UUID uuid, long remainingMillis) {
        if (!trailingChecks.add(uuid)) {
            return;
        }
        long delayTicks = Math.max(1L, (long) Math.ceil(remainingMillis / 50.0));
        BV.scheduler().runAsyncDelayed(() -> {
            trailingChecks.remove(uuid);
            Position latest = latestPositions.get(uuid);
            Long sequence = checkSequences.get(uuid);
            if (latest == null || sequence == null) {
                return;
            }
            lastCheck.put(uuid, System.currentTimeMillis());
            checkPosition(player, uuid, latest, sequence);
        }, delayTicks);
    }

    private void checkPosition(Player player, UUID uuid, Position position, long sequence) {
        BV.scheduler().runAsync(() -> {
            Village current = findVillageAsync(position);
            if (!isCurrent(uuid, sequence)) {
                return;
            }
            int currentId = current == null ? -1 : current.id();
            int previousId = inside.getOrDefault(uuid, -1);
            if (currentId == previousId) {
                if (current != null) {
                    refreshActionBar(player, current, sequence);
                }
                return;
            }
            if (current == null) {
                inside.remove(uuid);
                clearActionBar(player, sequence);
                return;
            }
            inside.put(uuid, currentId);
            showEntry(player, current, sequence);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        inside.remove(uuid);
        lastCheck.remove(uuid);
        checkSequences.remove(uuid);
        latestPositions.remove(uuid);
        trailingChecks.remove(uuid);
    }

    private void showEntry(Player player, Village current, long sequence) {
        // 标题显示也回到玩家所在区域线程发送（原生 sendTitle 线程要求）
        BV.scheduler().runForEntity(player, () -> {
            if (!isCurrent(player.getUniqueId(), sequence) || !player.isOnline()
                    || !BV.config().villageEntryTitleEnabled()) {
                return;
            }
            String unknown = BV.messages().raw("village-entry-unknown");
            String name = villageName(current);
            String king = BV.villages().resolveKingName(current.id());
            if (king == null || king.isBlank() || king.equals("-")) {
                king = unknown;
            } else {
                king = MessageService.escapeUntrusted(king);
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

    private void refreshActionBar(Player player, Village current, long sequence) {
        BV.scheduler().runForEntity(player, () -> {
            if (!isCurrent(player.getUniqueId(), sequence) || !player.isOnline()) {
                return;
            }
            String actionBar = BV.messages().raw("village-entry-actionbar")
                    .replace("{village}", villageName(current));
            player.sendActionBar(MessageService.deserialize(actionBar));
        }, null);
    }

    private void clearActionBar(Player player, long sequence) {
        BV.scheduler().runForEntity(player, () -> {
            if (isCurrent(player.getUniqueId(), sequence) && player.isOnline()) {
                player.sendActionBar(MessageService.deserialize(""));
            }
        }, null);
    }

    private String villageName(Village current) {
        return current.name() == null || current.name().isBlank()
                ? BV.messages().raw("village-id-format").replace("{id}", String.valueOf(current.id()))
                : MessageService.escapeUntrusted(current.name());
    }

    /**
     * 异步检测：纯内存遍历村庄列表 + 空间校验，不触碰游戏世界对象（worldName 由调用方在主线程预先取好）。
     */
    private Village findVillageAsync(Position position) {
        if (BV.villages() == null || position.worldName() == null) {
            return null;
        }
        int extra = BV.config().villageEntryRangeExtra();
        for (Village village : BV.villages().all()) {
            if (!village.world().equalsIgnoreCase(position.worldName())) {
                continue;
            }
            double dx = position.x() - village.centerX();
            double dy = position.y() - village.centerY();
            double dz = position.z() - village.centerZ();
            double radius = village.radius() + extra;
            if (dx * dx + dy * dy + dz * dz <= radius * radius) {
                return village;
            }
        }
        return null;
    }

    private boolean sameBlock(Location a, Location b) {
        return a != null && b != null && a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ()
                && a.getWorld() == b.getWorld();
    }

    private boolean isCurrent(UUID playerId, long sequence) {
        return Long.valueOf(sequence).equals(checkSequences.get(playerId));
    }

    private record Position(String worldName, double x, double y, double z) {
    }
}
