package dev.bettervillagers.i18n;

import dev.bettervillagers.BV;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 国际化消息服务（规范 0.2 Adventure API / 用户规则：禁止硬编码用户可见文本）。
 * <p>
 * 从 {@code lang/<locale>.yml} 加载消息键，支持 {@code {key}} 占位符替换。
 * Prompt 模板从独立的 {@code prompt.yml} 加载（规范 6.2：可单独编辑的提示词文件）。
 * <p>
 * 颜色格式双轨支持（用户规则：支持原版颜色逻辑及其渐变色逻辑）：
 * <ul>
 *   <li>原版颜色码：{@code &c}、{@code &l} 等 → {@link LegacyComponentSerializer}</li>
 *   <li>MiniMessage 高级格式：{@code <gradient:#fff:#000>}、{@code <color:#ff0000>}、{@code <bold>} 等
 *       → {@link MiniMessage}</li>
 * </ul>
 * 当字符串包含 {@code <} 标签时使用 MiniMessage（支持渐变色/十六进制色/装饰标签），
 * 否则使用原版 {@code &} 颜色码解析。
 */
public final class MessageService {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final Plugin plugin;
    private FileConfiguration messages;
    /** 独立 prompt.yml 配置（规范 6.2：提示词模板单独文件，优先于语言文件内置默认）。 */
    private FileConfiguration promptConfig;
    private Component prefix = Component.empty();

    public MessageService(Plugin plugin) {
        this.plugin = plugin;
    }

    /** 加载指定语言；不存在则回退到内置 zh_CN。 */
    public void load(String locale) {
        String loc = (locale == null || locale.isBlank()) ? "zh_CN" : locale;
        Path folder = plugin.getDataFolder().toPath().resolve("lang");
        try {
            Files.createDirectories(folder);
        } catch (IOException e) {
            plugin.getLogger().warning(logOrFallback("log.lang-dir-fail", "{error}", e.getMessage()));
        }
        ensureResource(folder, loc + ".yml", "lang/" + loc + ".yml");
        File file = folder.resolve(loc + ".yml").toFile();
        if (!file.exists()) {
            ensureResource(folder, "zh_CN.yml", "lang/zh_CN.yml");
            file = folder.resolve("zh_CN.yml").toFile();
        }
        try (Reader reader = Files.newBufferedReader(file.toPath())) {
            this.messages = YamlConfiguration.loadConfiguration(reader);
        } catch (IOException e) {
            plugin.getLogger().severe(logOrFallback("log.lang-read-fail", "{error}", e.getMessage()));
            this.messages = new YamlConfiguration();
        }
        this.prefix = deserialize(messages.getString("messages.prefix", ""));
    }

    /**
     * 加载独立 prompt.yml（规范 6.2：提示词模板单独文件）。
     * <p>
     * 首次启动时从 jar 内置资源释放；已存在则保留用户编辑，不覆盖。
     */
    public void loadPrompts() {
        Path dataDir = plugin.getDataFolder().toPath();
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            plugin.getLogger().warning(logOrFallback("log.data-dir-fail", "{error}", e.getMessage()));
        }
        ensureResource(dataDir, "prompt.yml", "prompt.yml");
        File file = dataDir.resolve("prompt.yml").toFile();
        try (Reader reader = Files.newBufferedReader(file.toPath())) {
            this.promptConfig = YamlConfiguration.loadConfiguration(reader);
        } catch (IOException e) {
            plugin.getLogger().severe(logOrFallback("log.prompt-read-fail", "{error}", e.getMessage()));
            this.promptConfig = new YamlConfiguration();
        }
        // 规范 6.x：检测 prompt.yml 版本
        dev.bettervillagers.config.Ver.check("prompt.yml", promptConfig, dev.bettervillagers.config.Ver.PROMPT);
    }

    private void ensureResource(Path folder, String name, String resource) {
        File target = folder.resolve(name).toFile();
        if (target.exists()) {
            return;
        }
        try (InputStream in = plugin.getResource(resource)) {
            if (in != null) {
                Files.copy(in, target.toPath());
            }
        } catch (IOException e) {
            plugin.getLogger().warning(logOrFallback("log.resource-save-fail", "{resource}", resource, "{error}", e.getMessage()));
        }
    }

    /**
     * 取原始字符串（含颜色码/Prompt 模板），无键则返回 key 本身。
     * <p>
     * 对于 {@code ai-prompt.*} 路径，优先返回 prompt.yml 中的值（规范 6.2），
     * 若该文件未定义或为空则回退到语言文件 {@code messages.ai-prompt.*} 内置默认。
     */
    public String raw(String key) {
        if (key.startsWith("ai-prompt.")) {
            String rel = key.substring("ai-prompt.".length());
            // 用户修改过的 prompt 只覆盖对应键；缺键时回退语言包默认，避免所有请求变成默认/键名。
            String custom = promptConfig == null ? null : promptConfig.getString(rel);
            if (custom != null && !custom.isBlank()) {
                return custom;
            }
            String fallback = messages == null ? null : messages.getString("messages." + key);
            return fallback == null || fallback.isBlank() ? key : fallback;
        }
        return Objects.requireNonNullElse(messages.getString("messages." + key), key);
    }

    /**
     * 日志取值：优先从已加载的 messages 读取，初始化阶段（messages 为空）回退到 BV.messages()。
     * 用于 MessageService 自身初始化期间的日志输出。
     */
    private String logOrFallback(String logKey, String... pairs) {
        String template = null;
        if (messages != null) {
            template = messages.getString("messages." + logKey);
        }
        if (template == null && BV.messages() != null) {
            template = BV.messages().raw(logKey);
        }
        if (template == null) {
            template = logKey;
        }
        return applyPlaceholders(template, pairs);
    }

    /**
     * 取原始字符串列表（名字库等数据，规范 6.2 / i18n）。
     * 无键则返回空列表。
     */
    public List<String> rawList(String key) {
        return messages.getStringList("messages." + key);
    }

    /* 前缀组件。 */
    /**
     * 颜色格式解析（用户规则：支持原版颜色逻辑及其渐变色逻辑）。
     * <p>
     * 含 {@code <} 标签 → MiniMessage（渐变色/十六进制色/装饰标签）；
     * 否则 → 原版 {@code &} 颜色码。
     */
    public static Component deserialize(String input) {
        if (input == null || input.isEmpty()) {
            return Component.empty();
        }
        if (input.indexOf('<') >= 0) {
            try {
                return MINI.deserialize(input);
            } catch (Throwable ignored) {
                // MiniMessage 解析失败时回退到原版颜色码
                return LEGACY.deserialize(input);
            }
        }
        return LEGACY.deserialize(input);
    }

    public static String escapeUntrusted(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        return input.replace("<", "\\<")
                .replaceAll("(?i)&(?=[0-9A-FK-ORX#])", "\uFF06");
    }

    /** 解析为带前缀的组件（含占位符替换）。占位符以键值对成对传入。 */
    public Component get(String key, String... pairs) {
        Component body = deserialize(applyPlaceholders(raw(key), pairs));
        if (prefix == Component.empty()) {
            return body;
        }
        return prefix.append(body);
    }

    /** 发送消息到指令发送者（自动附加前缀）。 */
    public void send(CommandSender sender, String key, String... pairs) {
        Component full = get(key, pairs);
        sender.sendMessage(full);
    }

    /** 广播消息（带前缀）。 */
    public void broadcast(String key, String... pairs) {
        org.bukkit.Bukkit.getServer().sendMessage(get(key, pairs));
    }

    /** 仅向在线玩家广播，避免与控制台日志重复输出。 */
    public void broadcastPlayers(String key, String... pairs) {
        Component full = get(key, pairs);
        for (org.bukkit.entity.Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
            BV.scheduler().runForEntity(player, () -> player.sendMessage(full), null);
        }
    }

    /*
      获取控制台可显示的消息。控制台不支持 Minecraft 颜色码，因此只输出解析后的纯文本。
     */
    /**
     * 向控制台发送带颜色的消息（现代 Paper/Folia 控制台支持 ANSI 24-bit 色）。
     * 直接把 Adventure 组件发给 {@link org.bukkit.command.ConsoleCommandSender}（其实现了 Audience），
     * 从而保留渐变色 / 十六进制色 / 原版颜色码。用于启动横幅等场景。
     */
    public void sendConsoleRaw(String key, String... pairs) {
        Component full = deserialize(applyPlaceholders(raw(key), pairs));
        org.bukkit.command.ConsoleCommandSender console = org.bukkit.Bukkit.getConsoleSender();
        console.sendMessage(full);
    }

    /**
     * 广播可点击的消息（修复问题3：点击传送到建造位置）。
     * <p>
     * teleportData 格式：x,y,z,world，点击后执行 /bv tp 命令传送。
     *
     * @param key           消息键
     * @param teleportData  传送坐标数据（x,y,z,world）
     * @param pairs         额外占位符键值对
     */
    public void broadcastClickable(String key, String teleportData, String... pairs) {
        Component body = deserialize(applyPlaceholders(raw(key), pairs));
        String clickCmd = "/bv tp " + teleportData;
        Component clickable = body.clickEvent(ClickEvent.runCommand(clickCmd))
                .hoverEvent(deserialize(raw("click-to-teleport")));
        Component full = prefix == Component.empty() ? clickable : prefix.append(clickable);
        org.bukkit.Bukkit.getServer().sendMessage(full);
    }

    private static String applyPlaceholders(String text, String... pairs) {
        if (pairs == null || pairs.length == 0) {
            return text;
        }
        Map<String, String> map = new HashMap<>(pairs.length / 2 + 1);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        String out = text;
        for (Map.Entry<String, String> e : map.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", escapeUntrusted(e.getValue()));
        }
        return out;
    }
}
