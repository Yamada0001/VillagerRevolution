package dev.bettervillagers;

import dev.bettervillagers.ai.AIService;
import dev.bettervillagers.behavior.BehaviorEngine;
import dev.bettervillagers.building.BuildingManager;
import dev.bettervillagers.command.BVCommand;
import dev.bettervillagers.config.ConfigManager;
import dev.bettervillagers.debug.DebugMonitor;
import dev.bettervillagers.gui.BetterVillagersGui;
import dev.bettervillagers.i18n.MessageService;
import dev.bettervillagers.listener.VillagerListener;
import dev.bettervillagers.listener.VillageEntryListener;
import dev.bettervillagers.profession.ProfessionManager;
import dev.bettervillagers.redstone.RegionManager;
import dev.bettervillagers.redstone.RegionSelectionListener;
import dev.bettervillagers.scheduler.FoliaLibSchedulerAdapter;
import dev.bettervillagers.scheduler.PlatformDetector;
import dev.bettervillagers.scheduler.SchedulerAdapter;
import dev.bettervillagers.scheduler.ThreadBoundaryGuard;
import dev.bettervillagers.storage.StorageService;
import dev.bettervillagers.storage.VillagerRecoveryRecord;
import dev.bettervillagers.trade.TradeService;
import dev.bettervillagers.village.VillageManager;
import dev.bettervillagers.villager.VillagerData;
import dev.bettervillagers.villager.VillagerManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * BetterVillagers 插件主类（规范 0.x / 全局生命周期）。
 * <p>
 * onEnable 中按依赖顺序装配全局服务注册表 {@link BV}；onDisable 中按逆序安全卸载并落盘。
 * 所有跨线程边界遵循规范 0.1（Folia 区域调度模型）。
 */
public final class BetterVillagersPlugin extends JavaPlugin {

    private RegionSelectionListener regionSelection;
    private CompletableFuture<Void> bootstrapFuture;
    private volatile boolean ready;

    @Override
    public void onEnable() {
        BV.init(this);

        // 1. 配置与国际化（用户规则：禁止硬编码用户可见文本）
        saveDefaultConfig();
        ConfigManager config = new ConfigManager(this);
        config.reload();
        BV.config(config);

        MessageService messages = new MessageService(this);
        messages.load(config.language());
        // 先注册，再 loadPrompts：Ver.check 依赖 BV.messages() 输出 i18n 警告
        BV.messages(messages);
        // 规范 6.2：加载独立 prompt.yml（提示词模板单独文件，可直接编辑）
        messages.loadPrompts();

        // 2. 调度抽象层（规范 0.1：Paper/Folia 统一，禁止 Bukkit.getScheduler）
        SchedulerAdapter scheduler = new FoliaLibSchedulerAdapter(this);
        BV.scheduler(scheduler);
        BV.guard(new ThreadBoundaryGuard(this, config.debugMode()));

        // 3. 存储层（规范 8.x：schema 幂等初始化）
        StorageService storage = new StorageService(this, config.storage());
        BV.storage(storage);

        // 降级文件会在全部服务装配后、运行态缓存加载前回放。
        Path recoveryDir = getDataFolder().toPath().resolve("recovery");

        // 4. AI 系统（规范 1.x：异步、熔断、降级链）
        AIService ai = new AIService(config.ai(), config.circuitBreaker());
        BV.ai(ai);

        // 5. 职业系统（规范 2.x）
        ProfessionManager professions = new ProfessionManager(this);
        BV.professions(professions);

        // 6. 村庄 / 生电保护区（规范 2.2 / 5.x）
        VillageManager villages = new VillageManager(config.village().getInt("detection-radius", 64));
        BV.villages(villages);
        // 村庄外交系统（问题5）
        BV.diplomacy(new dev.bettervillagers.village.DiplomacyManager());
        BV.activities(new dev.bettervillagers.village.VillageActivityManager());

        RegionManager regions = new RegionManager(config.redstoneMode().getBoolean("enabled", true));
        regions.configure(config.redstoneMode());
        BV.regions(regions);

        // 7. 社会系统（规范 3.3 / 3.4）
        BV.trade(new TradeService(
                config.performance().getInt("trade-calculation-cache", 300),
                config.performance().getInt("trade-cache-max-size", 2000),
                config.performance().getInt("trade-quantize-step", 8)));
        BV.building(new BuildingManager(config.performance().getInt("async-threads", 4)));

        // 8. 行为引擎（规范 3.1：三层决策）
        BehaviorEngine behavior = new BehaviorEngine(
                config.pathfindingRange(),
                config.aiUpdateInterval(),
                config.strategicInterval(),
                config.performance().getInt("block-operation-cooldown", 10));
        BV.behavior(behavior);

        // 8.5 职业专属任务引擎 + 跨职业社交引擎（规范 3.3：各职业核心职责 / 跨职业相遇交互）
        dev.bettervillagers.behavior.task.PatrolRouter patrolRouter =
                new dev.bettervillagers.behavior.task.PatrolRouter();
        BV.patrolRouter(patrolRouter);
        dev.bettervillagers.behavior.task.MilitaryTask militaryTask =
                new dev.bettervillagers.behavior.task.MilitaryTask(patrolRouter, behavior.threatDetector());
        BV.taskEngine(new dev.bettervillagers.behavior.task.ProfessionTaskEngine(militaryTask, behavior.blocks()));
        BV.socialEngine(new dev.bettervillagers.behavior.social.SocialEngine(
                config.performance().getInt("async-threads", 4)));

        // 9. 村民运行期管理器（规范 3.x / 4.5）
        VillagerManager villagers = new VillagerManager(
                config.village().getInt("king-spawn-population", 6),
                config.performance().getInt("async-threads", 4));
        BV.villagers(villagers);
        BV.debug(new DebugMonitor());

        // 10. 关键运行数据加载完成前不开放命令、事件或 AI，避免空缓存保护窗口。
        bootstrapFuture = recoverFallbackFilesAsync(recoveryDir).thenCompose(ignored -> CompletableFuture.allOf(
                villages.load(), regions.load(), BV.diplomacy().load(), BV.building().restoreLayouts()));
        bootstrapFuture.whenComplete((ignored, error) -> scheduler.runGlobal(() -> {
            if (error != null) {
                getLogger().log(java.util.logging.Level.SEVERE,
                        "BetterVillagers bootstrap failed; disabling plugin", unwrap(error));
                Bukkit.getPluginManager().disablePlugin(this);
                return;
            }
            finishEnable(config, messages, villagers);
        }));
    }

    private void finishEnable(ConfigManager config, MessageService messages, VillagerManager villagers) {
        if (ready || !isEnabled()) {
            return;
        }
        regionSelection = new RegionSelectionListener();
        BVCommand cmd = new BVCommand(regionSelection);
        BetterVillagersGui gui = new BetterVillagersGui(cmd, regionSelection);
        cmd.gui(gui);
        var pluginCmd = getCommand("bettervillagers");
        if (pluginCmd != null) {
            pluginCmd.setExecutor(cmd);
            pluginCmd.setTabCompleter(cmd);
        }
        Bukkit.getPluginManager().registerEvents(new VillageEntryListener(), this);
        Bukkit.getPluginManager().registerEvents(new VillagerListener(), this);
        Bukkit.getPluginManager().registerEvents(regionSelection, this);
        Bukkit.getPluginManager().registerEvents(gui, this);
        villagers.startTicking(config.aiUpdateInterval(), config.strategicInterval(), config.autoSaveInterval());
        ready = true;
        registerAlreadyLoadedVillagers();

        String platform = PlatformDetector.isFolia() ? "Folia" : "Paper";
        messages.sendConsoleRaw("startup", "platform", platform);
        messages.broadcastPlayers("startup", "platform", platform);
    }

    private void registerAlreadyLoadedVillagers() {
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
                int chunkX = chunk.getX();
                int chunkZ = chunk.getZ();
                org.bukkit.Location regionKey = new org.bukkit.Location(
                        world, (chunkX << 4) + 8, world.getMinHeight(), (chunkZ << 4) + 8);
                BV.scheduler().runAtRegion(regionKey, () -> {
                    if (!world.isChunkLoaded(chunkX, chunkZ) || BV.villagers() == null) {
                        return;
                    }
                    for (org.bukkit.entity.Entity entity : world.getChunkAt(chunkX, chunkZ).getEntities()) {
                        if (entity instanceof org.bukkit.entity.Villager villager) {
                            BV.villagers().register(villager);
                        }
                    }
                });
            }
        }
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /** Applies all hot-reloadable configuration and replaces config-bound runtime components. */
    public synchronized void reloadRuntime() {
        ConfigManager config = BV.config();
        config.reload();
        BV.messages().load(config.language());
        BV.messages().loadPrompts();
        BV.professions().load();
        BV.guard(new ThreadBoundaryGuard(this, config.debugMode()));

        if (BV.ai() != null) {
            BV.ai().reconfigure(config.ai(), config.circuitBreaker());
        }
        if (BV.regions() != null) {
            BV.regions().enabled(config.redstoneMode().getBoolean("enabled", true));
            BV.regions().configure(config.redstoneMode());
        }
        if (BV.villages() != null) {
            BV.villages().detectionRadius(config.village().getInt("detection-radius", 64));
        }
        if (BV.diplomacy() != null) {
            BV.diplomacy().configure();
        }
        if (BV.activities() != null) {
            BV.activities().configure();
        }
        BV.trade(new TradeService(
                config.performance().getInt("trade-calculation-cache", 300),
                config.performance().getInt("trade-cache-max-size", 2000),
                config.performance().getInt("trade-quantize-step", 8)));

        int asyncThreads = config.performance().getInt("async-threads", 4);
        if (BV.building() != null) {
            BV.building().reconfigureAsyncThreads(asyncThreads);
            if (!config.feature("autonomous-building")) {
                BV.building().cancelAll();
            }
        }
        if (BV.socialEngine() != null) {
            BV.socialEngine().reconfigureAsyncThreads(asyncThreads);
        }

        BehaviorEngine previousBehavior = BV.behavior();
        dev.bettervillagers.behavior.task.PatrolRouter previousPatrol = BV.patrolRouter();
        BehaviorEngine behavior = new BehaviorEngine(
                config.pathfindingRange(), config.aiUpdateInterval(), config.strategicInterval(),
                config.performance().getInt("block-operation-cooldown", 10));
        dev.bettervillagers.behavior.task.PatrolRouter patrol =
                new dev.bettervillagers.behavior.task.PatrolRouter();
        dev.bettervillagers.behavior.task.MilitaryTask military =
                new dev.bettervillagers.behavior.task.MilitaryTask(patrol, behavior.threatDetector());
        BV.behavior(behavior);
        BV.patrolRouter(patrol);
        BV.taskEngine(new dev.bettervillagers.behavior.task.ProfessionTaskEngine(
                military, behavior.blocks()));
        if (previousBehavior != null) {
            previousBehavior.shutdown();
        }
        if (previousPatrol != null) {
            previousPatrol.clear();
        }

        if (BV.villagers() != null) {
            BV.villagers().reconfigure(
                    config.village().getInt("king-spawn-population", 6), asyncThreads,
                    config.aiUpdateInterval(), config.strategicInterval(), config.autoSaveInterval());
        }
    }

    @Override
    public void onDisable() {
        ready = false;
        if (bootstrapFuture != null && !bootstrapFuture.isDone()) {
            try {
                bootstrapFuture.get(10L, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // Running load tasks are bounded; shutdown below closes remaining services safely.
            }
        }
        // 先停止新交易并冲刷交易日志，再保存村民状态；存储服务此时仍保持可用。
        dev.bettervillagers.behavior.social.SocialEngine social = BV.socialEngine();
        if (social != null) {
            social.shutdown();
        }
        VillagerManager villagers = BV.villagers();
        if (villagers != null) {
            villagers.shutdown();
        }
        // 取消所有进行中的施工任务（须在全局服务置 null 之前，避免回调 NPE）
        BuildingManager building = BV.building();
        if (building != null) {
            building.shutdown();
        }
        BehaviorEngine behavior = BV.behavior();
        if (behavior != null) {
            behavior.shutdown();
        }
        if (BV.diplomacy() != null) {
            BV.diplomacy().shutdown();
        }
        if (BV.activities() != null) {
            BV.activities().shutdown();
        }
        AIService ai = BV.ai();
        if (ai != null) {
            ai.shutdown();
        }
        StorageService storage = BV.storage();
        if (storage != null) {
            storage.close();
        }
        if (regionSelection != null) {
            regionSelection.clearAll();
            regionSelection = null;
        }
        if (BV.messages() != null) {
            BV.messages().broadcast("shutdown");
            getLogger().info(BV.messages().raw("log.shutdown"));
        }
        // 注销本插件注册的所有事件监听器（规范 4.x：避免 /reload 下重复注册与 stale 状态泄漏）
        org.bukkit.event.HandlerList.unregisterAll(this);
        // 清理巡逻路线缓存（持有 Location→World 引用，规范 4.x：停服释放）
        if (BV.patrolRouter() != null) {
            BV.patrolRouter().clear();
        }
        BV.shutdown();
    }

    private CompletableFuture<Void> recoverFallbackFilesAsync(Path recoveryDir) {
        if (!Files.isDirectory(recoveryDir)) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        BV.scheduler().runAsync(() -> {
            try {
                recoverFallbackFiles(recoveryDir);
                future.complete(null);
            } catch (Throwable error) {
                future.completeExceptionally(error);
            }
        });
        return future;
    }

    /** Replays complete database fallback snapshots before runtime caches are loaded. */
    private void recoverFallbackFiles(Path recoveryDir) {
        try (var files = Files.list(recoveryDir)) {
            List<Path> list = files.filter(f -> f.getFileName().toString().endsWith(".json")).toList();
            if (list.isEmpty()) {
                return;
            }
            getLogger().info(BV.messages().raw("log.recovery-start")
                    .replace("{count}", String.valueOf(list.size())));
            int success = 0;
            for (Path file : list) {
                String fileName = file.getFileName().toString();
                try {
                    String uuid = fileName.substring(0, fileName.length() - ".json".length());
                    UUID.fromString(uuid);
                    String json = Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
                    if (json.stripLeading().startsWith("[")) {
                        Optional<VillagerData> existing = BV.storage().villagers().find(uuid);
                        if (existing.isEmpty()) {
                            throw new IllegalStateException("Legacy recovery has no matching villager row");
                        }
                        VillagerData data = existing.get();
                        VillagerData updated = new VillagerData(
                                data.uuid(), data.name(), data.profession(),
                                data.health(), data.attack(), data.defense(),
                                data.locationWorld(), data.locationX(), data.locationY(), data.locationZ(),
                                data.villageId(), data.aiEnabled(), json,
                                data.createdAt(), System.currentTimeMillis());
                        BV.storage().villagers().upsert(updated);
                    } else {
                        VillagerRecoveryRecord record = VillagerRecoveryRecord.fromJson(json);
                        if (!uuid.equals(record.data().uuid())) {
                            throw new IllegalStateException("Recovery filename does not match snapshot UUID");
                        }
                        if (record.attachToVillage()) {
                            BV.storage().villagers().insertNewAndAttach(record.data(), record.claimKing());
                        } else {
                            BV.storage().villagers().upsert(record.data());
                        }
                    }
                    Files.deleteIfExists(file);
                    success++;
                } catch (Exception e) {
                    getLogger().warning(BV.messages().raw("log.recovery-fail")
                            .replace("{file}", fileName).replace("{error}", String.valueOf(e.getMessage())));
                }
            }
            getLogger().info(BV.messages().raw("log.recovery-done")
                    .replace("{success}", String.valueOf(success))
                    .replace("{total}", String.valueOf(list.size())));
        } catch (IOException e) {
            getLogger().warning(BV.messages().raw("log.recovery-fail")
                    .replace("{file}", recoveryDir.toString()).replace("{error}", String.valueOf(e.getMessage())));
        }
    }
}
