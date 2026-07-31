package dev.bettervillagers.redstone;

import dev.bettervillagers.BV;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Runtime manager for redstone protected regions.
 */
public final class RegionManager {

    private final List<ProtectedRegion> regions = new ArrayList<>();
    private final boolean enabled;

    public RegionManager(boolean enabled) {
        this.enabled = enabled;
    }

    public void load() {
        BV.scheduler().runAsync(() -> {
            List<ProtectedRegion> db = BV.storage().regions().findAll();
            synchronized (regions) {
                regions.clear();
                regions.addAll(db);
            }
        });
    }

    public boolean isProtected(org.bukkit.Location location) {
        if (!enabled) {
            return false;
        }
        String world = location.getWorld().getName();
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        synchronized (regions) {
            for (ProtectedRegion r : regions) {
                if (r.contains(world, x, y, z)) {
                    return true;
                }
            }
        }
        return false;
    }

    public Optional<ProtectedRegion> findByName(String name) {
        synchronized (regions) {
            return regions.stream().filter(r -> r.name().equalsIgnoreCase(name)).findFirst();
        }
    }

    public List<ProtectedRegion> all() {
        synchronized (regions) {
            return new ArrayList<>(regions);
        }
    }

    public boolean create(String name, String world, int x1, int y1, int z1, int x2, int y2, int z2, String owner) {
        if (findByName(name).isPresent()) {
            return false;
        }
        ProtectedRegion normalized = normalize(name, world, x1, y1, z1, x2, y2, z2, owner);
        synchronized (regions) {
            regions.add(normalized);
        }
        BV.scheduler().runAsync(() -> {
            int id = BV.storage().regions().insert(normalized);
            if (id <= 0) {
                return;
            }
            ProtectedRegion persisted = new ProtectedRegion(id, normalized.name(), normalized.world(),
                    normalized.minX(), normalized.minY(), normalized.minZ(),
                    normalized.maxX(), normalized.maxY(), normalized.maxZ(), normalized.owner());
            synchronized (regions) {
                int index = regions.indexOf(normalized);
                if (index >= 0) {
                    regions.set(index, persisted);
                    return;
                }
                regions.stream()
                        .filter(region -> samePendingRegion(region, normalized))
                        .findFirst()
                        .ifPresent(current -> {
                            ProtectedRegion currentPersisted = new ProtectedRegion(id,
                                    current.name(), current.world(), current.minX(), current.minY(), current.minZ(),
                                    current.maxX(), current.maxY(), current.maxZ(), current.owner());
                            regions.set(regions.indexOf(current), currentPersisted);
                            if (!current.name().equals(normalized.name())) {
                                BV.storage().regions().update(currentPersisted);
                            }
                        });
            }
        });
        return true;
    }

    public boolean delete(String name) {
        ProtectedRegion r = findByName(name).orElse(null);
        if (r == null) {
            return false;
        }
        synchronized (regions) {
            regions.remove(r);
        }
        int id = r.id();
        BV.scheduler().runAsync(() -> {
            if (id > 0) {
                BV.storage().regions().delete(id);
            }
        });
        return true;
    }

    public boolean modify(String name, String newName, String owner) {
        ProtectedRegion existing = findByName(name).orElse(null);
        if (existing == null) {
            return false;
        }
        if (newName != null && !newName.equalsIgnoreCase(name) && findByName(newName).isPresent()) {
            return false;
        }
        String resolvedName = newName != null ? newName : existing.name();
        String resolvedOwner = owner != null ? owner : existing.owner();
        ProtectedRegion modified = new ProtectedRegion(existing.id(), resolvedName, existing.world(),
                existing.minX(), existing.minY(), existing.minZ(),
                existing.maxX(), existing.maxY(), existing.maxZ(), resolvedOwner);
        synchronized (regions) {
            regions.remove(existing);
            regions.add(modified);
        }
        int id = existing.id();
        BV.scheduler().runAsync(() -> {
            if (id > 0) {
                BV.storage().regions().update(modified);
            }
        });
        return true;
    }

    private boolean samePendingRegion(ProtectedRegion candidate, ProtectedRegion created) {
        return candidate.id() == 0
                && candidate.world().equals(created.world())
                && candidate.minX() == created.minX() && candidate.minY() == created.minY() && candidate.minZ() == created.minZ()
                && candidate.maxX() == created.maxX() && candidate.maxY() == created.maxY() && candidate.maxZ() == created.maxZ()
                && candidate.owner().equals(created.owner());
    }

    private ProtectedRegion normalize(String name, String world, int x1, int y1, int z1, int x2, int y2, int z2, String owner) {
        return new ProtectedRegion(0, name, world,
                Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2),
                owner);
    }
}
