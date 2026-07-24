package dev.bettervillagers.redstone;

import dev.bettervillagers.BV;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 生电保护区管理器（规范 5.x：保护区内完全关闭 AI，恢复原版）。
 * <p>
 * 启动时从配置与数据库加载保护区到内存；运行期 {@link #isProtected} 提供高频查询。
 */
public final class RegionManager {

    private final List<ProtectedRegion> regions = new ArrayList<>();
    private final boolean enabled;

    public RegionManager(boolean enabled) {
        this.enabled = enabled;
    }

    /** 异步加载：配置预设 + 数据库。 */
    public void load() {
        BV.scheduler().runAsync(() -> {
            // 数据库
            List<ProtectedRegion> db = BV.storage().regions().findAll();
            synchronized (regions) {
                regions.clear();
                regions.addAll(db);
            }
        });
    }

    /** 该坐标是否处于生电保护区内（高频查询，仅内存判定）。 */
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

    /** 创建保护区（异步落库）。返回 false 表示名称已存在。 */
    public boolean create(String name, String world, int x1, int y1, int z1, int x2, int y2, int z2, String owner) {
        if (findByName(name).isPresent()) {
            return false;
        }
        ProtectedRegion normalized = normalize(0, name, world, x1, y1, z1, x2, y2, z2, owner);
        synchronized (regions) {
            regions.add(normalized);
        }
        BV.scheduler().runAsync(() -> BV.storage().regions().insert(normalized));
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

    /** 调整保护区范围（支持 redstone.modify 权限节点）。返回 false 表示保护区不存在。 */
    public boolean resize(String name, String world, int x1, int y1, int z1, int x2, int y2, int z2) {
        ProtectedRegion existing = findByName(name).orElse(null);
        if (existing == null) {
            return false;
        }
        ProtectedRegion resized = normalize(existing.id(), name, world, x1, y1, z1, x2, y2, z2, existing.owner());
        synchronized (regions) {
            regions.remove(existing);
            regions.add(resized);
        }
        int id = existing.id();
        BV.scheduler().runAsync(() -> {
            if (id > 0) {
                BV.storage().regions().update(resized);
            }
        });
        return true;
    }

    /** 修改保护区名称与所有者（支持 redstone.modify 权限节点）。返回 false 表示不存在或新名称已被占用。 */
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

    private ProtectedRegion normalize(int id, String name, String world, int x1, int y1, int z1, int x2, int y2, int z2, String owner) {
        return new ProtectedRegion(id, name, world,
                Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2),
                owner);
    }
}
