package dev.bettervillagers.config;

import dev.bettervillagers.BV;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 配置文件版本控制（规范 6.x：配置文件版本化，旧版自动提示升级）。
 * <p>
 * 集中管理 config.yml / professions.yml / prompt.yml 的版本号。
 * 修改任意 yml 结构时递增对应版本常量，插件启动时检测到版本不一致会输出警告。
 * <p>
 * 使用方式：
 * <pre>
 *   Ver.check("config.yml", fileConfig, Ver.CONFIG);
 *   Ver.check("professions.yml", yamlConfig, Ver.PROFESSIONS);
 *   Ver.check("prompt.yml", yamlConfig, Ver.PROMPT);
 * </pre>
 */
public final class Ver {

    private Ver() {
    }

    /** 当前 config.yml 版本（修改结构时递增）。 */
    public static final int CONFIG = 3;
    /** 当前 professions.yml 版本。 */
    public static final int PROFESSIONS = 2;
    /** 当前 prompt.yml 版本。 */
    public static final int PROMPT = 5;

    /** config.yml 中的版本键名。 */
    public static final String KEY = "ver";

    /**
     * 检测文件版本，不一致时输出警告日志。
     *
     * @param fileName 文件显示名（仅用于日志）
     * @param cfg      已加载的配置
     * @param expected 期望版本号（取自 {@link #CONFIG} / {@link #PROFESSIONS} / {@link #PROMPT}）
     * @return true 表示版本匹配或文件首次创建
     */
    public static boolean check(String fileName, FileConfiguration cfg, int expected) {
        if (cfg == null) {
            return false;
        }
        int actual = cfg.getInt(KEY, 0);
        if (actual == 0) {
            // 文件无版本号（极旧版或手动创建），输出提示
            // 初始化早期 BV.messages() 可能尚未注册，必须空安全
            warn(i18nOr(
                    "log.version-missing",
                    "[{file}] missing version ({key}), expected {expected}. Delete to regenerate.",
                    "{file}", fileName,
                    "{key}", KEY,
                    "{expected}", String.valueOf(expected)));
            return false;
        }
        if (actual != expected) {
            warn(i18nOr(
                    "log.version-mismatch",
                    "[{file}] version mismatch: file={actual}, expected={expected}. Backup then regenerate.",
                    "{file}", fileName,
                    "{actual}", String.valueOf(actual),
                    "{expected}", String.valueOf(expected)));
            return false;
        }
        return true;
    }

    private static void warn(String message) {
        if (BV.plugin() != null) {
            BV.plugin().getLogger().warning(message);
        }
    }

    /** messages 未就绪时使用英文回退模板，避免启动 NPE。 */
    private static String i18nOr(String key, String fallback, String... pairs) {
        String template = fallback;
        if (BV.messages() != null) {
            String raw = BV.messages().raw(key);
            if (raw != null && !raw.equals(key)) {
                template = raw;
            }
        }
        String out = template;
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            out = out.replace(pairs[i], pairs[i + 1]);
        }
        return out;
    }

    /**
     * 确保文件包含正确的版本号；若文件不存在则从 jar 释放（带正确版本号）。
     *
     * @param plugin   插件实例
     * @param dataDir  数据目录
     * @param fileName 文件名（如 "config.yml"）
     * @param expected 期望版本号
     * @return 加载后的 FileConfiguration
     */
    public static FileConfiguration ensureVersioned(Plugin plugin, Path dataDir, String fileName, int expected) {
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            plugin.getLogger().warning(BV.messages().raw("log.dir-create-fail").replace("{error}", e.getMessage()));
        }
        File file = dataDir.resolve(fileName).toFile();
        if (!file.exists()) {
            // 从 jar 释放
            try (var in = plugin.getResource(fileName)) {
                if (in != null) {
                    Files.copy(in, file.toPath());
                }
            } catch (IOException e) {
                plugin.getLogger().warning(BV.messages().raw("log.resource-release-fail").replace("{file}", fileName).replace("{error}", e.getMessage()));
            }
        }
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        // 检测版本
        check(fileName, cfg, expected);
        return cfg;
    }
}
