package dev.bettervillagers.storage;

import dev.bettervillagers.BV;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * 原子文件写入（规范 4.5：写临时文件后 rename，防止写入中途崩溃导致文件损坏）。
 */
public final class AtomicFileWriter {

    private AtomicFileWriter() {
    }

    /**
     * 原子写入字节内容：先写 {@code .tmp}，再 move 替换目标。
     * 目标所在目录不存在时自动创建。
     */
    public static void write(Path target, byte[] bytes) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        Files.write(tmp, bytes,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AccessDeniedException | UnsupportedOperationException e) {
            // 某些文件系统不支持原子移动，回退为非原子替换
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 写入字符串（UTF-8）。 */
    public static void writeText(Path target, String text) throws IOException {
        write(target, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * 数据库写入失败时的本地降级落盘（规范 4.5：启动时自动补录）。
     * 将待持久化内容追加到 {@code data/recovery/<name>.json}。
     */
    public static void fallbackDump(Plugin plugin, String name, String json) {
        try {
            Path recovery = plugin.getDataFolder().toPath().resolve("recovery").resolve(name + ".json");
            writeText(recovery, json);
        } catch (IOException e) {
            plugin.getLogger().severe(BV.messages().raw("errors.fallback-dump-fail")
                    .replace("{name}", name).replace("{error}", e.getMessage()));
        }
    }
}
