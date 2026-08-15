package dev.bettervillagers.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

/**
 * 配置访问门面（规范 6.1）。
 * <p>
 * 集中读取 config.yml，提供类型安全的便捷方法；各模块按需取用。
 * reload 时自动检测配置版本（{@link Ver}）。
 */
public final class ConfigManager {

    private final Plugin plugin;
    private FileConfiguration config;

    public ConfigManager(Plugin plugin) {
        this.plugin = plugin;
    }

    /** 加载/重载配置。 */
    public void reload() {
        plugin.reloadConfig();
        this.config = plugin.getConfig();
        // 规范 6.x：检测配置版本
        Ver.check("config.yml", config, Ver.CONFIG);
    }

    public FileConfiguration raw() {
        return config;
    }

    public String language() {
        return config.getString("plugin.language", "zh_CN");
    }

    public boolean debugMode() {
        return config.getBoolean("plugin.debug-mode", false);
    }

    public long autoSaveInterval() {
        return config.getLong("plugin.auto-save-interval", 300L);
    }

    public ConfigurationSection ai() {
        return config.getConfigurationSection("ai");
    }

    public ConfigurationSection circuitBreaker() {
        return config.getConfigurationSection("circuit-breaker");
    }

    public ConfigurationSection performance() {
        return config.getConfigurationSection("performance");
    }

    public boolean feature(String key) {
        return config.getBoolean("features." + key, true);
    }

    public ConfigurationSection redstoneMode() {
        return config.getConfigurationSection("redstone-mode");
    }

    public ConfigurationSection storage() {
        return config.getConfigurationSection("storage");
    }

    public ConfigurationSection village() {
        return config.getConfigurationSection("village");
    }

    public int aiUpdateInterval() {
        return config.getInt("performance.ai-update-interval", 5);
    }

    public int strategicInterval() {
        return config.getInt("performance.strategic-interval", 30);
    }

    public int villageEntryRangeExtra() {
        return Math.max(0, config.getInt("village.entry-range-extra", 10));
    }

    public boolean villageEntryTitleEnabled() {
        return config.getBoolean("village.entry-title.enabled", true);
    }

    public int maxActiveAiVillagers() {
        return config.getInt("performance.max-active-ai-villagers", 50);
    }

    public int pathfindingRange() {
        return config.getInt("performance.pathfinding-range", 32);
    }
}
