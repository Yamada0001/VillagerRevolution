package dev.bettervillagers.redstone;

import dev.bettervillagers.BV;
import dev.bettervillagers.scheduler.ScheduledHandle;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Optional;
import java.util.UUID;

/** 保护区的两点选择、预览与 GUI 聊天重命名交互。 */
public final class RegionSelectionListener implements Listener {

    private final RegionVisualizer visualizer = new RegionVisualizer();
    private final Map<UUID, Selection> selections = new ConcurrentHashMap<>();
    private final Map<UUID, String> pendingRenames = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledHandle> previewTasks = new ConcurrentHashMap<>();

    public void begin(Player player) {
        clearSelection(player.getUniqueId());
        selections.put(player.getUniqueId(), new Selection(null, null));
        BV.messages().send(player, "region-selection-start");
    }

    public void beginRename(Player player, String currentName) {
        pendingRenames.put(player.getUniqueId(), currentName);
        player.closeInventory();
        BV.messages().send(player, "region-rename-input", "name", currentName);
    }

    public Optional<Selection> takeSelection(UUID playerId) {
        Selection selection = selections.remove(playerId);
        cancelPreview(playerId);
        return Optional.ofNullable(selection).filter(Selection::complete);
    }

    public void clearSelection(UUID playerId) {
        selections.remove(playerId);
        cancelPreview(playerId);
    }

    public void clearAll() {
        selections.clear();
        pendingRenames.clear();
        previewTasks.values().forEach(ScheduledHandle::cancel);
        previewTasks.clear();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSelect(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        if (!selections.containsKey(playerId) || event.getClickedBlock() == null) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        event.setCancelled(true);
        Location point = event.getClickedBlock().getLocation();
        Selection previous = selections.get(playerId);
        if (previous == null) {
            return;
        }
        if (previous.first() != null && !previous.first().getWorld().equals(point.getWorld())) {
            BV.messages().send(player, "region-selection-world-mismatch");
            return;
        }
        if (action == Action.RIGHT_CLICK_BLOCK && previous.first() == null) {
            BV.messages().send(player, "region-selection-first-required");
            return;
        }
        Selection next = action == Action.LEFT_CLICK_BLOCK
                ? new Selection(point, previous.second())
                : new Selection(previous.first(), point);
        selections.put(playerId, next);
        BV.messages().send(player, action == Action.LEFT_CLICK_BLOCK ? "region-selection-first" : "region-selection-second",
                "x", String.valueOf(point.getBlockX()), "y", String.valueOf(point.getBlockY()), "z", String.valueOf(point.getBlockZ()));
        refreshPreview(player, next);
        if (next.complete()) {
            BV.messages().send(player, "region-selection-confirm");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRenameChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        String currentName = pendingRenames.remove(player.getUniqueId());
        if (currentName == null) {
            return;
        }
        event.setCancelled(true);
        String newName = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(event.message()).trim();
        if (newName.isEmpty()) {
            sendPlayer(player, "region-rename-empty");
            return;
        }
        BV.scheduler().runForEntity(player, () -> {
            if (!canModify(player, currentName)) {
                BV.messages().send(player, "no-permission");
                return;
            }
            BV.regions().modify(currentName, newName, null).whenComplete((modified, failure) ->
                    BV.scheduler().runForEntity(player, () -> {
                        if (failure != null) {
                            BV.messages().send(player, "region-persistence-failed");
                        } else if (modified) {
                            BV.messages().send(player, "region-renamed", "old", currentName, "new", newName);
                        } else {
                            BV.messages().send(player, "region-rename-failed", "name", newName);
                        }
                    }, null));
        }, null);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        selections.remove(playerId);
        pendingRenames.remove(playerId);
        cancelPreview(playerId);
    }

    private boolean canModify(Player player, String name) {
        return player.hasPermission("bettervillagers.redstone.modify")
                || BV.regions().findByName(name).map(region -> player.getName().equalsIgnoreCase(region.owner())).orElse(false);
    }

    private void refreshPreview(Player player, Selection selection) {
        cancelPreview(player.getUniqueId());
        if (selection.first() == null) {
            return;
        }
        previewTasks.put(player.getUniqueId(), visualizer.preview(player, selection.first(), selection.second()));
    }

    private void cancelPreview(UUID playerId) {
        ScheduledHandle task = previewTasks.remove(playerId);
        if (task != null) {
            task.cancel();
        }
    }

    private void sendPlayer(Player player, String key, String... pairs) {
        BV.scheduler().runForEntity(player, () -> BV.messages().send(player, key, pairs), null);
    }

    public record Selection(Location first, Location second) {
        public boolean complete() {
            return first != null && second != null;
        }
    }
}
