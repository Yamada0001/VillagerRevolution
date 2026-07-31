package dev.bettervillagers.config;

import dev.bettervillagers.BV;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Version checks for generated configuration files.
 */
public final class Ver {

    public static final int CONFIG = 5;
    public static final int PROFESSIONS = 2;
    public static final int PROMPT = 7;
    public static final String KEY = "ver";

    private Ver() {
    }

    public static void check(String fileName, FileConfiguration cfg, int expected) {
        if (cfg == null) {
            return;
        }
        int actual = cfg.getInt(KEY, 0);
        if (actual == 0) {
            warn(i18nOr(
                    "log.version-missing",
                    "[{file}] missing version ({key}), expected {expected}. Delete to regenerate.",
                    "{file}", fileName,
                    "{key}", KEY,
                    "{expected}", String.valueOf(expected)));
            return;
        }
        if (actual != expected) {
            warn(i18nOr(
                    "log.version-mismatch",
                    "[{file}] version mismatch: file={actual}, expected={expected}. Backup then regenerate.",
                    "{file}", fileName,
                    "{actual}", String.valueOf(actual),
                    "{expected}", String.valueOf(expected)));
        }
    }

    private static void warn(String message) {
        if (BV.plugin() != null) {
            BV.plugin().getLogger().warning(message);
        }
    }

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
}
