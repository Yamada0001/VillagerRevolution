package dev.bettervillagers;

import dev.bettervillagers.ai.AIService;
import dev.bettervillagers.behavior.BehaviorEngine;
import dev.bettervillagers.building.BuildingManager;
import dev.bettervillagers.command.BVCommand;
import dev.bettervillagers.config.ConfigManager;
import dev.bettervillagers.debug.DebugMonitor;
import dev.bettervillagers.i18n.MessageService;
import dev.bettervillagers.listener.VillagerListener;
import dev.bettervillagers.listener.VillageEntryListener;
import dev.bettervillagers.profession.ProfessionManager;
import dev.bettervillagers.redstone.RegionManager;
import dev.bettervillagers.scheduler.FoliaLibSchedulerAdapter;
import dev.bettervillagers.scheduler.PlatformDetector;
import dev.bettervillagers.scheduler.SchedulerAdapter;
import dev.bettervillagers.scheduler.ThreadBoundaryGuard;
import dev.bettervillagers.storage.StorageService;
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

/**
 * BetterVillagers 插件主类（规范 0.x / 全局生命周期）。
 * <p>
 * onEnable 中按依赖顺序装配全局服务注册表 {@link BV}；onDisable 中按逆序安全卸载并落盘。
 * 所有跨线程边界遵循规范 0.1（Folia 区域调度模型）。
 */
public final class BetterVillagersPlugin extends JavaPlugin {

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

        // 降级文件启动补录（规范 4.5：DB 写失败时本地落盘，重启后回放）
        Path recoveryDir = getDataFolder().toPath().resolve("recovery");
        if (Files.isDirectory(recoveryDir)) {
            scheduler.runAsync(() -> recoverFallbackFiles(recoveryDir));
        }

        // 4. AI 系统（规范 1.x：异步、熔断、降级链）
        AIService ai = new AIService(config.ai(), config.circuitBreaker());
        BV.ai(ai);

        // 5. 职业系统（规范 2.x）
        ProfessionManager professions = new ProfessionManager(this);
        BV.professions(professions);

        // 6. 村庄 / 生电保护区（规范 2.2 / 5.x）
        VillageManager villages = new VillageManager(config.village().getInt("detection-radius", 64));
        BV.villages(villages);
        villages.load();
        // 村庄外交系统（问题5）
        BV.diplomacy(new dev.bettervillagers.village.DiplomacyManager());

        RegionManager regions = new RegionManager(config.redstoneMode().getBoolean("enabled", true));
        regions.load();
        BV.regions(regions);

        // 7. 社会系统（规范 3.3 / 3.4）
        BV.trade(new TradeService(
                config.performance().getInt("trade-calculation-cache", 300),
                config.performance().getInt("trade-cache-max-size", 2000),
                config.performance().getInt("trade-quantize-step", 8)));
        BV.building(new BuildingManager());

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
        BV.socialEngine(new dev.bettervillagers.behavior.social.SocialEngine());

        // 9. 村民运行期管理器（规范 3.x / 4.5）
        VillagerManager villagers = new VillagerManager(config.village().getInt("king-spawn-population", 6));
        BV.villagers(villagers);
        BV.debug(new DebugMonitor());

        // 10. 命令与事件
        BVCommand cmd = new BVCommand();
        var pluginCmd = getCommand("bettervillagers");
        if (pluginCmd != null) {
            pluginCmd.setExecutor(cmd);
            pluginCmd.setTabCompleter(cmd);
        }
        Bukkit.getPluginManager().registerEvents(new VillagerListener(), this);
        Bukkit.getPluginManager().registerEvents(new VillageEntryListener(), this);

        // 11. 启动周期任务 + 注册已加载村民
        villagers.startTicking(
                config.aiUpdateInterval(),
                config.strategicInterval(),
                config.autoSaveInterval());
        // 已加载区块中的村民由 ChunkLoadEvent 注册，避免在 Folia 全局线程跨区域遍历世界实体。

        // 12. 启动广播
        String platform = PlatformDetector.isFolia() ? "Folia" : "Paper";
        getLogger().info(messages.raw("log.startup").replace("{platform}", platform));
        messages.broadcast("startup", "platform", platform);
    }

    @Override
    public void onDisable() {
        // 取消所有进行中的施工任务（须在全局服务置 null 之前，避免 tick 回调 NPE）
        BuildingManager building = BV.building();
        if (building != null) {
            building.shutdown();
        }
        VillagerManager villagers = BV.villagers();
        if (villagers != null) {
            villagers.shutdown();
        }
        BehaviorEngine behavior = BV.behavior();
        if (behavior != null) {
            behavior.shutdown();
        }
        AIService ai = BV.ai();
        if (ai != null) {
            ai.shutdown();
        }
        StorageService storage = BV.storage();
        if (storage != null) {
            storage.close();
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

    /** 降级文件补录（规范 4.5：将上一次 DB 写失败落盘的 AI 记忆回放到数据库）。 */
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
                    String json = Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
                    Optional<VillagerData> existing = BV.storage().villagers().find(uuid);
                    if (existing.isPresent()) {
                        VillagerData data = existing.get();
                        VillagerData updated = new VillagerData(
                                data.uuid(), data.name(), data.profession(),
                                data.health(), data.attack(), data.defense(),
                                data.locationWorld(), data.locationX(), data.locationY(), data.locationZ(),
                                data.villageId(), data.aiEnabled(), json,
                                data.createdAt(), System.currentTimeMillis());
                        BV.storage().villagers().upsert(updated);
                        Files.deleteIfExists(file);
                        success++;
                    }
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
