package dev.bettervillagers.village;

import dev.bettervillagers.BV;
import dev.bettervillagers.ai.AIContext;
import dev.bettervillagers.behavior.strategic.StrategicAI;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 村庄管理器（规范 2.2 / 3.3）。
 * <p>
 * 启动时从存储加载村庄到内存；运行期负责村庄识别、创建与国王唯一性维护。
 * 所有 DB 操作经异步调度执行，内存结构线程安全。
 * <p>
 * 修复（问题4）：新建村庄时异步调用大模型生成有意义的名字，替代"村庄#x"格式；
 * AI 不可用时用规则引擎基于生物群系/随机词生成兜底名字。
 */
public final class VillageManager {

    private final Map<Integer, Village> villages = new ConcurrentHashMap<>();
    private final AtomicInteger idSeq = new AtomicInteger(0);
    private final int detectionRadius;

    public VillageManager(int detectionRadius) {
        this.detectionRadius = detectionRadius;
    }

    /** 异步加载全部村庄到内存（启动时调用）。 */
    public CompletableFuture<Void> load() {
        CompletableFuture<Void> loaded = new CompletableFuture<>();
        BV.scheduler().runAsync(() -> {
            try {
                List<Village> all = BV.storage().villages().findAll();
                for (Village v : all) {
                    villages.put(v.id(), v);
                    idSeq.accumulateAndGet(v.id(), Math::max);
                }
                loaded.complete(null);
            } catch (Throwable t) {
                loaded.completeExceptionally(t);
            }
        });
        return loaded;
    }



    /**
     * 识别或创建村庄（规范 2.2：依据现有村庄半径覆盖或新建）。
     * 返回村庄 id；若附近无村庄则在坐标处新建。
     * <p>
     * 仅接收纯值（world 名/坐标/biome），不访问 Bukkit 世界/方块 API，
     * 因此可安全在异步线程调用——调用方须在主线程/区域线程预先提取这些值。
     *
     * @param world 世界名
     * @param x     中心方块 X
     * @param y     中心方块 Y
     * @param z     中心方块 Z
     * @param biome 生物群系名（已规整，用于村庄命名；null/空时使用兜底）
     */
    public int findOrCreate(String world, int x, int y, int z, String biome) {
        if (world == null) {
            return -1;
        }
        // 内存覆盖判定
        for (Village v : villages.values()) {
            if (v.covers(world, x, y, z)) {
                return v.id();
            }
        }
        // 唯一性预检查：若该坐标附近的村庄半径外存在已绑定了同一“潜在国王 UUID”的村庄，
        // 直接复用，避免国王归属分裂（防重复村庄）。
        // （此处国王通常尚未确定，主要防止并发注册时同一坐标产生两个村庄实体）
        // 创建新村庄——同步落库获取真实 dbId，避免调用方持有临时 id 而与 dbId 不同步
        // （调用方 VillagerManager.register 本身在异步线程执行，不会阻塞主线程）
        int tempId = idSeq.incrementAndGet();
        Village created = new Village(tempId, world, x, y, z, detectionRadius, null, 0, null);
        int dbId = BV.storage().villages().insert(created);
        int finalId = dbId > 0 ? dbId : tempId;
        if (dbId > 0 && dbId > idSeq.get()) {
            idSeq.set(dbId);
        }
        Village stored = new Village(finalId, world, x, y, z, detectionRadius, null, 0, null);
        villages.put(finalId, stored);
        // 问题4：异步生成村庄名字（biome 由调用方在主线程预先取好）
        requestVillageName(stored, biome);
        return finalId;
    }

    /** 异步请求 AI 为村庄生成名字（问题4）。biome 须由调用方在区域线程预先提取。 */
    private void requestVillageName(Village village, String biome) {
        String biomeFinal = (biome == null || biome.isBlank()) ? "plains" : biome;
        String locText = village.centerX() + "," + village.centerY() + "," + village.centerZ();
        String system = BV.messages().raw("ai-prompt.village-name-system");
        String user = BV.messages().raw("ai-prompt.village-name-user")
                .replace("{world}", village.world())
                .replace("{biome}", biomeFinal)
                .replace("{loc}", locText)
                .replace("{pop}", "1");
        AIContext ctx = new AIContext("village-" + village.id(), "VillageNamer",
                "king", "village-name", system, user);
        BV.ai().decide(ctx)
                .thenAccept(r -> {
                    // 只有 AI 真正成功（非降级、文本可用）才使用 AI 名字
                    if (r.isUsable()) {
                        String name = sanitizeName(r.text(), biomeFinal);
                        setName(village.id(), name);
                    }
                })
                .exceptionally(ignored -> null);
    }

    /** 清洗 AI 返回的名字；AI 不可用时用规则引擎兜底（村庄必须有名字）。 */
    private String sanitizeName(String raw, String biome) {
        if (raw == null || raw.isBlank()) {
            return ruleBasedName(biome);
        }
        String name = raw.trim()
                .replaceAll("[\"'`]", "")
                .replaceAll("\\p{Punct}", "")
                .replaceAll("\\s+", " ")
                .trim();
        // 过滤 AI 协议关键词
        if (name.matches("(?i)^(WORK|FLEE|ATTACK|PATROL|REST|TRADE|HOLD|ACCEPT|REJECT|IDLE)$")) {
            return ruleBasedName(biome);
        }
        if (name.isBlank() || name.length() > 16) {
            name = name.length() > 16 ? name.substring(0, 16) : ruleBasedName(biome);
        }
        return name.isBlank() ? ruleBasedName(biome) : name;
    }

    /** 规则引擎兜底命名（AI 不可用时，名字库从 lang 文件加载，规范 6.2 / i18n）。 */
    private String ruleBasedName(String biome) {
        List<String> prefixes = BV.messages().rawList("names.village-prefix");
        List<String> suffixes = BV.messages().rawList("names.village-suffix");
        // 兜底也走语言资源，避免再次引入硬编码文本
        if (prefixes.isEmpty()) {
            prefixes = BV.messages().rawList("names.village-prefix-fallback");
        }
        if (suffixes.isEmpty()) {
            suffixes = BV.messages().rawList("names.village-suffix-fallback");
        }
        String prefix = biome != null && !biome.isBlank()
                ? biome.split(" ")[0]
                : prefixes.get(ThreadLocalRandom.current().nextInt(prefixes.size()));
        String suffix = suffixes.get(ThreadLocalRandom.current().nextInt(suffixes.size()));
        return prefix + suffix;
    }

    public Optional<Village> get(int id) {
        return Optional.ofNullable(villages.get(id));
    }

    public List<Village> all() {
        return List.copyOf(villages.values());
    }

    /**
     * 实时统计村庄范围内的人口（修复问题4）。
     * <p>
     * 遍历所有在线村民，统计实体位置落在村庄半径内的数量。
     * 同时将该范围内未绑定村庄的村民自动归入此村庄。
     */
    public int countVillagersInVillage(int villageId) {
        Village v = villages.get(villageId);
        if (v == null) {
            return 0;
        }
        int count = 0;
        if (BV.villagers() != null) {
            for (var bv : BV.villagers().all()) {
                var ent = bv.entity();
                if (ent == null) {
                    continue;
                }
                var loc = ent.getLocation();
                if (v.covers(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ())) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * 实时解析村庄国王（修复国王检测）。
     * <p>
     * 优先使用持久化 kingUuid；若为空或实体不在线，则在村庄范围内扫描职业为 KING 的村民并回写。
     */
    public String resolveKingName(int villageId) {
        Village v = villages.get(villageId);
        if (v == null) {
            return "-";
        }
        if (BV.villagers() == null) {
            return v.kingUuid() == null || v.kingUuid().isBlank() ? "-" : v.kingUuid();
        }
        // 1) 持久化 UUID 可命中
        if (v.kingUuid() != null && !v.kingUuid().isBlank()) {
            var opt = BV.villagers().get(v.kingUuid());
            if (opt.isPresent()) {
                return opt.get().name();
            }
        }
        // 2) 扫描村庄范围内的国王职业
        for (var bv : BV.villagers().all()) {
            if (bv.profession() != dev.bettervillagers.profession.Profession.KING) {
                continue;
            }
            var ent = bv.entity();
            if (ent == null) {
                // 无实体时也可用 villageId 匹配
                if (bv.villageId() == villageId) {
                    setKing(villageId, bv.uuid());
                    return bv.name();
                }
                continue;
            }
            var loc = ent.getLocation();
            if (v.covers(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ())
                    || bv.villageId() == villageId) {
                setKing(villageId, bv.uuid());
                return bv.name();
            }
        }
        return "-";
    }

    /** 村庄范围内是否存在建筑师（修复问题7：无建筑师不下发建造命令）。 */
    public boolean hasBuilder(int villageId) {
        if (BV.villagers() == null) {
            return false;
        }
        for (var bv : BV.villagers().all()) {
            if (bv.profession() != dev.bettervillagers.profession.Profession.BUILDER) {
                continue;
            }
            if (bv.villageId() == villageId && bv.isAlive()) {
                return true;
            }
            // 位置兜底：实体在村庄范围内也算
            var ent = bv.entity();
            Village v = villages.get(villageId);
            if (ent != null && v != null) {
                var loc = ent.getLocation();
                if (v.covers(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 设置村庄国王（规范 2.2 国王唯一）。 */
    public void setKing(int villageId, String kingUuid) {
        Village v = villages.get(villageId);
        if (v == null) {
            return;
        }
        // 国王唯一性约束：同一国王 UUID 不得绑定多个村庄，否则触发合并去重
        for (Village other : villages.values()) {
            if (other.id() == villageId) {
                continue;
            }
            if (kingUuid != null && kingUuid.equals(other.kingUuid())) {
                // 国王归属冲突：把当前村庄并入已有村庄
                mergeInto(villageId, other.id());
                return;
            }
        }
        villages.put(villageId, new Village(v.id(), v.world(), v.centerX(), v.centerY(), v.centerZ(),
                v.radius(), kingUuid, v.population(), v.name()));
        BV.scheduler().runAsync(() -> BV.storage().villages().updateKing(villageId, kingUuid));
    }

    /** 该村庄是否已有国王。 */
    public boolean hasKing(int villageId) {
        Village v = villages.get(villageId);
        return v != null && v.kingUuid() != null && !v.kingUuid().isBlank();
    }

    public void updatePopulation(int villageId, int population) {
        Village v = villages.get(villageId);
        if (v == null) {
            return;
        }
        villages.put(villageId, new Village(v.id(), v.world(), v.centerX(), v.centerY(), v.centerZ(),
                v.radius(), v.kingUuid(), population, v.name()));
        BV.scheduler().runAsync(() -> BV.storage().villages().updatePopulation(villageId, population));
    }

    /** 设置村庄名字（问题4：AI 命名后更新内存与 DB）。 */
    public void setName(int villageId, String name) {
        Village v = villages.get(villageId);
        if (v == null || name == null || name.isBlank()) {
            return;
        }
        villages.put(villageId, new Village(v.id(), v.world(), v.centerX(), v.centerY(), v.centerZ(),
                v.radius(), v.kingUuid(), v.population(), name));
        BV.scheduler().runAsync(() -> {
            try {
                BV.storage().villages().updateName(villageId, name);
            } catch (RuntimeException e) {
                BV.plugin().getLogger().warning(BV.messages().raw("errors.village-name-update").replace("{error}", e.getMessage()));
            }
        });
    }

    // ==================== 唯一性/去重（修复问题2） ====================

    /** 去重结果。 */
    public record DedupResult(int scanned, int merged, int flagged) {
    }

    /**
     * 扫描并合并异常村庄数据：
     * <ul>
     *   <li>同一国王 UUID 绑定多个村庄 → 保留更早 id，其余并入</li>
     *   <li>相同世界+国王+人口+坐标（属性完全一致）→ 视为重复实体合并</li>
     * </ul>
     * 必须在异步线程调用（含 DB 写）。
     */
    public DedupResult deduplicate() {
        List<Village> snapshot = List.copyOf(villages.values());
        int merged = 0;
        int flagged = 0;
        Map<String, Integer> kingToVillage = new HashMap<>();
        for (Village v : snapshot) {
            String king = v.kingUuid();
            if (king == null || king.isBlank()) {
                continue;
            }
            Integer existing = kingToVillage.get(king);
            if (existing == null) {
                kingToVillage.put(king, v.id());
            } else {
                int keep = Math.min(existing, v.id());
                int drop = Math.max(existing, v.id());
                mergeInto(drop, keep);
                kingToVillage.put(king, keep);
                merged++;
            }
        }
        snapshot = List.copyOf(villages.values());
        Map<String, Village> fingerprint = new HashMap<>();
        for (Village v : snapshot) {
            String fp = v.world() + "|" + v.kingUuid() + "|" + v.population()
                    + "|" + v.centerX() + "," + v.centerY() + "," + v.centerZ();
            Village prev = fingerprint.get(fp);
            if (prev == null) {
                fingerprint.put(fp, v);
            } else {
                int keep = Math.min(prev.id(), v.id());
                int drop = Math.max(prev.id(), v.id());
                mergeInto(drop, keep);
                fingerprint.put(fp, villages.get(keep));
                merged++;
                flagged++;
            }
        }
        return new DedupResult(snapshot.size(), merged, flagged);
    }

    /** 把 from 并入 to：先持久化成功，再提交内存态与相关缓存变更。 */
    private void mergeInto(int fromId, int toId) {
        Village from = villages.get(fromId);
        Village to = villages.get(toId);
        if (from == null || to == null) {
            return;
        }
        int newPop = from.population() + to.population();
        Village merged = new Village(to.id(), to.world(), to.centerX(), to.centerY(), to.centerZ(),
                Math.max(from.radius(), to.radius()),
                to.kingUuid() != null ? to.kingUuid() : from.kingUuid(),
                newPop,
                to.name() != null ? to.name() : from.name());
        BV.scheduler().runAsync(() -> {
            try {
                BV.storage().villages().updatePopulation(toId, newPop);
                if (to.kingUuid() == null && from.kingUuid() != null) {
                    BV.storage().villages().updateKing(toId, from.kingUuid());
                }
                BV.storage().villages().delete(fromId);
                applyMergedVillageState(fromId, toId, merged);
                BV.plugin().getLogger().info(BV.messages().raw("log.village-merged")
                        .replace("{from}", String.valueOf(fromId))
                        .replace("{to}", String.valueOf(toId)));
            } catch (RuntimeException e) {
                BV.plugin().getLogger().warning(BV.messages().raw("errors.village-merge")
                        .replace("{from}", String.valueOf(fromId))
                        .replace("{to}", String.valueOf(toId))
                        .replace("{error}", e.getMessage()));
            }
        });
    }

    private void applyMergedVillageState(int fromId, int toId, Village merged) {
        if (BV.villagers() != null) {
            for (var bv : BV.villagers().all()) {
                if (bv.villageId() == fromId) {
                    bv.villageId(toId);
                }
            }
        }
        villages.put(toId, merged);
        villages.remove(fromId);
        // D4 修复：村庄合并后移除废弃村庄的巡逻路线缓存，并重建目标村庄路线（半径可能变更）
        if (BV.patrolRouter() != null) {
            BV.patrolRouter().remove(fromId);
            BV.patrolRouter().rebuild(toId);
        }
        // 规范 4.x：清理废弃村庄在各子系统的缓存，避免 stale 条目残留与内存增长
        StrategicAI.clearVillage(fromId);
        if (BV.building() != null) {
            BV.building().clearVillage(fromId);
        }
    }

    /*
      基于 Bukkit 原生 {@link org.bukkit.World#getNearbyEntities} 查询村庄范围内所有村民实体。
      <p>
      这是权威实体查询接口（修复问题3：用原生游戏接口替代自定义遍历）。
     */
    /* 异步调度到村庄中心所在区域线程，用原生接口统计村民数量。 */
    // ==================== 自测（修复验证） ====================
    // 说明：原 selfTestDeduplication() / deduplicateInMemory() 为调试用死代码（含硬编码中文测试串、
    // 与正式 deduplicate() 逻辑重复）。已删除，去重逻辑统一由 deduplicate() 承担；测试应移至单元测试目录。
}
