package dev.bettervillagers.redstone;

import dev.bettervillagers.BV;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Runtime manager for redstone protected regions.
 */
public final class RegionManager {

    private final List<ProtectedRegion> regions = new ArrayList<>();
    private List<ProtectedRegion> configuredRegions = List.of();
    private volatile Map<String, List<ProtectedRegion>> regionsByWorld = Map.of();
    private volatile boolean enabled;
    private final Set<String> pendingNames = ConcurrentHashMap.newKeySet();

    public RegionManager(boolean enabled) {
        this.enabled = enabled;
    }

    public void enabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void configure(ConfigurationSection config) {
        List<ProtectedRegion> parsed = new ArrayList<>();
        if (config != null) {
            int index = 0;
            for (Map<?, ?> entry : config.getMapList("protected-regions")) {
                index++;
                String world = text(entry.get("world"));
                if (world.isBlank()) {
                    continue;
                }
                String name = text(entry.get("name"));
                if (name.isBlank()) {
                    name = "config-" + index;
                }
                parsed.add(normalize(name, world,
                        number(entry.get("x1")), number(entry.get("y1")), number(entry.get("z1")),
                        number(entry.get("x2")), number(entry.get("y2")), number(entry.get("z2")),
                        text(entry.get("owner"))));
            }
        }
        synchronized (regions) {
            configuredRegions = List.copyOf(parsed);
            rebuildWorldIndex();
        }
    }

    public CompletableFuture<Void> load() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        BV.scheduler().runAsync(() -> {
            try {
                List<ProtectedRegion> db = BV.storage().regions().findAll();
                synchronized (regions) {
                    regions.clear();
                    regions.addAll(db);
                    rebuildWorldIndex();
                }
                future.complete(null);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    public boolean isProtected(org.bukkit.Location location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }
        return isProtected(location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public boolean isProtected(String world, int x, int y, int z) {
        if (!enabled) {
            return false;
        }
        if (world == null) {
            return false;
        }
        for (ProtectedRegion region : regionsByWorld.getOrDefault(world.toLowerCase(Locale.ROOT), List.of())) {
            if (region.contains(world, x, y, z)) {
                return true;
            }
        }
        return false;
    }

    public Optional<ProtectedRegion> findByName(String name) {
        synchronized (regions) {
            return combinedRegions().stream().filter(r -> r.name().equalsIgnoreCase(name)).findFirst();
        }
    }

    public List<ProtectedRegion> all() {
        synchronized (regions) {
            return combinedRegions();
        }
    }

    public CompletableFuture<Boolean> create(String name, String world, int x1, int y1, int z1,
                                             int x2, int y2, int z2, String owner) {
        String operationKey = name.toLowerCase(java.util.Locale.ROOT);
        if (findByName(name).isPresent() || !pendingNames.add(operationKey)) {
            return CompletableFuture.completedFuture(false);
        }
        ProtectedRegion normalized = normalize(name, world, x1, y1, z1, x2, y2, z2, owner);
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        BV.scheduler().runAsync(() -> {
            try {
                int id = BV.storage().regions().insert(normalized);
                if (id <= 0) {
                    throw new IllegalStateException("Region insert returned no id");
                }
                ProtectedRegion persisted = new ProtectedRegion(id, normalized.name(), normalized.world(),
                        normalized.minX(), normalized.minY(), normalized.minZ(),
                        normalized.maxX(), normalized.maxY(), normalized.maxZ(), normalized.owner());
                synchronized (regions) {
                    regions.add(persisted);
                    rebuildWorldIndex();
                }
                result.complete(true);
            } catch (RuntimeException e) {
                logPersistenceFailure(e);
                result.completeExceptionally(e);
            } finally {
                pendingNames.remove(operationKey);
            }
        });
        return result;
    }

    public CompletableFuture<Boolean> delete(String name) {
        ProtectedRegion r = findByName(name).orElse(null);
        String operationKey = name.toLowerCase(java.util.Locale.ROOT);
        if (r == null || r.id() <= 0 || !pendingNames.add(operationKey)) {
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        int id = r.id();
        BV.scheduler().runAsync(() -> {
            try {
                BV.storage().regions().delete(id);
                synchronized (regions) {
                    regions.remove(r);
                    rebuildWorldIndex();
                }
                result.complete(true);
            } catch (RuntimeException e) {
                logPersistenceFailure(e);
                result.completeExceptionally(e);
            } finally {
                pendingNames.remove(operationKey);
            }
        });
        return result;
    }

    public CompletableFuture<Boolean> modify(String name, String newName, String owner) {
        ProtectedRegion existing = findByName(name).orElse(null);
        if (existing == null || existing.id() <= 0) {
            return CompletableFuture.completedFuture(false);
        }
        if (newName != null && !newName.equalsIgnoreCase(name) && findByName(newName).isPresent()) {
            return CompletableFuture.completedFuture(false);
        }
        String resolvedName = newName != null ? newName : existing.name();
        String resolvedOwner = owner != null ? owner : existing.owner();
        String oldKey = name.toLowerCase(java.util.Locale.ROOT);
        String newKey = resolvedName.toLowerCase(java.util.Locale.ROOT);
        if (!pendingNames.add(oldKey)) {
            return CompletableFuture.completedFuture(false);
        }
        if (!newKey.equals(oldKey) && !pendingNames.add(newKey)) {
            pendingNames.remove(oldKey);
            return CompletableFuture.completedFuture(false);
        }
        ProtectedRegion modified = new ProtectedRegion(existing.id(), resolvedName, existing.world(),
                existing.minX(), existing.minY(), existing.minZ(),
                existing.maxX(), existing.maxY(), existing.maxZ(), resolvedOwner);
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        int id = existing.id();
        BV.scheduler().runAsync(() -> {
            try {
                BV.storage().regions().update(modified);
                synchronized (regions) {
                    int index = regions.indexOf(existing);
                    if (index >= 0) {
                        regions.set(index, modified);
                        rebuildWorldIndex();
                    }
                }
                result.complete(true);
            } catch (RuntimeException e) {
                logPersistenceFailure(e);
                result.completeExceptionally(e);
            } finally {
                pendingNames.remove(oldKey);
                pendingNames.remove(newKey);
            }
        });
        return result;
    }

    private void logPersistenceFailure(RuntimeException error) {
        BV.plugin().getLogger().warning("Protected-region persistence failed: " + error.getMessage());
    }

    private void rebuildWorldIndex() {
        Map<String, List<ProtectedRegion>> grouped = new HashMap<>();
        for (ProtectedRegion region : combinedRegions()) {
            grouped.computeIfAbsent(region.world().toLowerCase(Locale.ROOT), ignored -> new ArrayList<>())
                    .add(region);
        }
        grouped.replaceAll((ignored, values) -> List.copyOf(values));
        regionsByWorld = Map.copyOf(grouped);
    }

    private List<ProtectedRegion> combinedRegions() {
        List<ProtectedRegion> combined = new ArrayList<>(regions.size() + configuredRegions.size());
        combined.addAll(regions);
        combined.addAll(configuredRegions);
        return combined;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int number(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(text(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private ProtectedRegion normalize(String name, String world, int x1, int y1, int z1, int x2, int y2, int z2, String owner) {
        return new ProtectedRegion(0, name, world,
                Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2),
                owner);
    }
}
