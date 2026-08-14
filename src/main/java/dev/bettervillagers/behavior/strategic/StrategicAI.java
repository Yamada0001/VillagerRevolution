package dev.bettervillagers.behavior.strategic;

import dev.bettervillagers.BV;
import dev.bettervillagers.ai.AIContext;
import dev.bettervillagers.ai.AIResult;
import dev.bettervillagers.building.BuildType;
import dev.bettervillagers.profession.ProfessionData;
import dev.bettervillagers.village.Village;
import dev.bettervillagers.villager.BVillager;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 战略层 AI：四阶段「要想富先修路」建造优先级 + 异步下发。
 * <ol>
 *   <li>道路网络</li>
 *   <li>道路两侧街景</li>
 *   <li>存量房屋优化</li>
 *   <li>景观 + 防御城墙闭环</li>
 * </ol>
 */
public final class StrategicAI {

    private final long intervalMillis;
    private static final long COMMAND_COOLDOWN_MS = 90_000L;
    private static final int MAX_PARALLEL_SAME_PHASE = 2;
    /** Minecraft 夜间起始/结束 tick（与 SocialEngine 共用语义）。 */
    private static final long NIGHT_START_TICKS = 13000L;
    private static final long NIGHT_END_TICKS = 23000L;
    /** 夜间跳过战略决策的概率（原硬编码 0.7）。 */
    private static final double NIGHT_SKIP_PROBABILITY = 0.7;
    private final Set<Integer> planningVillages = ConcurrentHashMap.newKeySet();
    private final Map<Integer, Long> lastCommandTime = new ConcurrentHashMap<>();
    private volatile boolean shutdown;

    /**
     * 清理指定村庄的战略相关缓存（村庄合并/删除时调用，规范 4.x：避免静态 Map 残留 stale 条目）。
     */
    public void clearVillage(int villageId) {
        planningVillages.remove(villageId);
        lastCommandTime.remove(villageId);
    }

    public StrategicAI(long intervalMillis) {
        this.intervalMillis = intervalMillis;
    }

    public void decide(BVillager king) {
        if (shutdown) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - king.lastStrategic() < intervalMillis) {
            return;
        }
        king.lastStrategic(now);
        Village village = BV.villages().get(king.villageId()).orElse(null);
        if (village == null || king.entity() == null) {
            return;
        }
        if (BV.diplomacy() != null) {
            BV.diplomacy().review(king, village);
        }
        if (BV.activities() != null) {
            BV.activities().tick(village);
        }
        if (!BV.config().feature("autonomous-building")) {
            return;
        }
        Long lastCmd = lastCommandTime.get(king.villageId());
        if (lastCmd != null && now - lastCmd < COMMAND_COOLDOWN_MS) {
            return;
        }
        if (planningVillages.contains(king.villageId())) {
            return;
        }
        if (king.entity() != null) {
            long worldTime = king.entity().getWorld().getTime();
            if (worldTime >= NIGHT_START_TICKS && worldTime <= NIGHT_END_TICKS
                    && Math.random() < NIGHT_SKIP_PROBABILITY) {
                return;
            }
        }
        if (BV.building() == null) {
            return;
        }
        int pop = BV.villages().countVillagersInVillage(village.id());
        BuildType.DevPhase phase = BV.building().currentPhase(village.id(), pop);

        // 同阶段可并行（上限 MAX_PARALLEL），跨阶段串行：本村有其它阶段施工时等待
        int same = BV.building().activeJobsInPhase(village.id(), phase);
        if (same >= MAX_PARALLEL_SAME_PHASE) {
            return;
        }
        if (same == 0 && BV.building().isVillageBuilding(village.id())) {
            // 本村有施工但不是当前发展阶段：等待完成后再跨阶段
            return;
        }

        if (!BV.villages().hasBuilder(village.id())) {
            BV.plugin().getLogger().info(
                    BV.messages().raw("log.building-no-builder")
                            .replace("{village}", String.valueOf(village.id())));
            return;
        }

        planningVillages.add(village.id());
        ProfessionData pd = king.professionData();
        double bravery = pd != null ? pd.personality().bravery() : 0.3;
        double greed = pd != null ? pd.personality().greed() : 0.3;

        BuildType recommended = BV.building().recommendType(village.id(), pop);
        String phaseName = phase.name();
        String system = BV.messages().raw("ai-prompt.strategic-king-system");
        String locText = village.centerX() + "," + village.centerY() + "," + village.centerZ();
        String user = BV.messages().raw("ai-prompt.strategic-king-user")
                .replace("{pop}", String.valueOf(pop))
                .replace("{loc}", locText)
                .replace("{bravery}", String.format("%.2f", bravery))
                .replace("{greed}", String.format("%.2f", greed))
                .replace("{integrity}", "phase=" + phaseName + ",recommend=" + recommended.name()
                        + ",roads=" + BV.building().cache().count(village.id(), BuildType.ROAD)
                        + ",street=" + BV.building().cache().count(village.id(), BuildType.STREETSCAPE)
                        + ",house=" + BV.building().cache().count(village.id(), BuildType.HOUSE)
                        + ",wall=" + BV.building().cache().count(village.id(), BuildType.WALL)
                        + BV.messages().raw("ai-prompt.strategic-phase-order"));

        AIContext ctx = new AIContext(king.uuid(), king.name(), "king", "strategic", system, user);
        BV.ai().decide(ctx)
                .thenAccept(r -> {
                    if (!isRunning()) {
                        planningVillages.remove(village.id());
                        return;
                    }
                    applyPlan(king, village, r, recommended, phase);
                })
                .exceptionally(ex -> {
                    planningVillages.remove(village.id());
                    if (isRunning()) {
                        BV.plugin().getLogger().warning(
                                BV.messages().raw("log.strategic-tick-error")
                                        .replace("{uuid}", king.uuid()).replace("{error}", String.valueOf(ex)));
                    }
                    return null;
                });
    }

    private void applyPlan(BVillager king, Village village, AIResult result,
                           BuildType recommended, BuildType.DevPhase phase) {
        if (!isRunning()) {
            planningVillages.remove(village.id());
            return;
        }
        boolean dispatched = false;
        try {
            BuildType type = recommended;
            boolean isDegraded = result == null || !result.isUsable();
            if (!isDegraded) {
                String upper = result.text().toUpperCase();
                if (upper.contains("HOLD")) {
                    return;
                }
                BuildType parsed = BuildType.fromCommand(upper);
                // 严格阶段约束：AI 不得跳阶段
                if (parsed != null && parsed.physical() && parsed.phase() == phase) {
                    type = parsed;
                }
            }
            // 推荐结果本身必须满足当前阶段，避免异常配置导致空指针或跨阶段建造。
            if (type == null || !type.physical() || type.phase() != phase) {
                return;
            }

            final BuildType finalType = type;
            String displayName = BV.messages().raw("structure." + finalType.structureKey());
            if (displayName.startsWith("structure.")) {
                displayName = finalType.name();
            }
            final String notificationName = displayName;
            BV.scheduler().runGlobal(() -> dispatchPlan(king, village, finalType, notificationName));
            dispatched = true;
        } finally {
            if (!dispatched) {
                planningVillages.remove(village.id());
            }
        }
    }

    private void dispatchPlan(BVillager king, Village village, BuildType type, String notificationName) {
        try {
            if (!isRunning() || !BV.config().feature("autonomous-building")) {
                return;
            }
            org.bukkit.Location center = pickSite(village, type);
            if (center == null || center.getWorld() == null) {
                return;
            }
            lastCommandTime.put(village.id(), System.currentTimeMillis());
            BV.scheduler().runAtRegion(center, () -> {
                int surfaceY = center.getWorld().getHighestBlockYAt(center.getBlockX(), center.getBlockZ()) + 1;
                center.setY(surfaceY);
                BV.building().issueTask(type.name(), village.id(), center);
            });
            notifyVillagePlayers(village, king.name(), notificationName);
        } finally {
            planningVillages.remove(village.id());
        }
    }

    public void shutdown() {
        shutdown = true;
        planningVillages.clear();
        lastCommandTime.clear();
    }

    private boolean isRunning() {
        return !shutdown && BV.plugin() != null && BV.plugin().isEnabled()
                && BV.scheduler() != null && BV.messages() != null && BV.building() != null;
    }

    private void notifyVillagePlayers(Village village, String kingName, String command) {
        int extra = BV.config().villageEntryRangeExtra();
        double radius = village.radius() + extra;
        double radiusSq = radius * radius;
        String message = BV.messages().raw("village-entry-command")
                .replace("{king}", dev.bettervillagers.i18n.MessageService.escapeUntrusted(kingName))
                .replace("{command}", command);
        org.bukkit.Bukkit.getOnlinePlayers().forEach(player ->
            BV.scheduler().runForEntity(player, () -> {
                if (!player.getWorld().getName().equals(village.world())) {
                    return;
                }
                org.bukkit.Location pl = player.getLocation();
                double dx = pl.getX() - village.centerX();
                double dy = pl.getY() - village.centerY();
                double dz = pl.getZ() - village.centerZ();
                if (dx * dx + dy * dy + dz * dz <= radiusSq) {
                    player.sendMessage(dev.bettervillagers.i18n.MessageService.deserialize(message));
                }
            }, null));
    }

    /**
     * 按类型选择与村庄格局协调的落点：
     * 道路沿中心十字偏移；街景贴路；房屋内环；城墙外环。
     */
    private org.bukkit.Location pickSite(Village village, BuildType type) {
        org.bukkit.World world = org.bukkit.Bukkit.getWorld(village.world());
        if (world == null) {
            return null;
        }
        org.bukkit.Location base = new org.bukkit.Location(
                world, village.centerX(), village.centerY(), village.centerZ());
        double angle = Math.random() * Math.PI * 2;
        int idx = BV.building().cache().count(village.id(), type);
        return switch (type) {
            case ROAD -> BV.building().nextRoadSite(village.id(), base);
            case STREETSCAPE -> {
                double r = 6 + (idx % 4) * 4;
                yield base.clone().add(Math.cos(angle) * r, 0, Math.sin(angle) * r);
            }
            case HOUSE, UPGRADE_HOUSE, FARM, TRADE_FAIR -> {
                double r = 10 + (idx % 5) * 5;
                yield base.clone().add(Math.cos(angle) * r, 0, Math.sin(angle) * r);
            }
            case WALL -> {
                double r = Math.max(16, village.radius() * 0.8);
                double a = (idx * (Math.PI / 2)) + angle * 0.1;
                yield base.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r);
            }
            case LANDSCAPE -> {
                double r = 8 + idx * 3;
                yield base.clone().add(Math.cos(angle) * r, 0, Math.sin(angle) * r);
            }
            default -> base.clone().add(Math.cos(angle) * 10, 0, Math.sin(angle) * 10);
        };
    }
}
