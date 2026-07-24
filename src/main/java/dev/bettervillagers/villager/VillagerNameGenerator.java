package dev.bettervillagers.villager;

import dev.bettervillagers.BV;
import dev.bettervillagers.ai.AIContext;
import dev.bettervillagers.ai.AIResult;
import dev.bettervillagers.profession.Profession;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 村民名字生成器（问题1：AI 中世纪风格随机名字）。
 * <p>
 * 优先异步请求大模型生成中世纪风格名字；AI 不可用时用内置规则引擎兜底
 * （名字库从 lang 文件加载，规范 6.2 / i18n）。
 */
public final class VillagerNameGenerator {

    private VillagerNameGenerator() {
    }

    /**
     * 规则引擎生成中世纪风格名字（AI 不可用时兜底）。
     * 名字库从 lang 文件的 names 节加载（规范 6.2 / i18n）。
     */
    public static String ruleBasedName() {
        List<String> prefixes = BV.messages().rawList("names.villager-prefix");
        List<String> suffixes = BV.messages().rawList("names.villager-suffix");
        List<String> titles = BV.messages().rawList("names.villager-title");
        // 兜底：lang 文件未加载时使用 i18n 默认名，避免在代码中硬编码可见文本（用户规则：禁止硬编码）
        if (prefixes.isEmpty()) prefixes = List.of(BV.messages().raw("villager-default"));
        if (suffixes.isEmpty()) suffixes = List.of(BV.messages().raw("villager-default"));
        if (titles.isEmpty()) titles = List.of("");

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        String part1 = prefixes.get(rng.nextInt(prefixes.size()));
        String part2 = suffixes.get(rng.nextInt(suffixes.size()));
        String name = part1 + part2;
        // 20% 概率附加称号
        if (rng.nextInt(5) == 0) {
            String title = titles.get(rng.nextInt(titles.size()));
            return title + name;
        }
        return name;
    }

    /**
     * 异步请求 AI 生成中世纪风格名字。
     * <p>
     * AI 真正成功（非降级）才用 AI 名字；否则保持规则引擎兜底名字不变。
     */
    public static void generateAsync(Profession profession, java.util.function.Consumer<String> callback) {
        String system = BV.messages().raw("ai-prompt.villager-name-system");
        String user = BV.messages().raw("ai-prompt.villager-name-user")
                .replace("{profession}", profession.id());
        AIContext ctx = new AIContext("name-" + System.nanoTime(), "NameGen",
                profession.id(), "villager-name", system, user);
        BV.ai().decide(ctx)
                .thenAccept(r -> {
                    // 只有 AI 真正成功（非降级、文本可用）才使用 AI 名字
                    if (r != null && r.isUsable()) {
                        String name = sanitize(r);
                        if (name != null) {
                            callback.accept(name);
                        }
                    }
                    // AI 失败/降级时不回调，保持规则引擎兜底名字
                })
                .exceptionally(ex -> null);
    }

    /** 清洗 AI 返回的名字；返回 null 表示不可用（应保持兜底名字）。 */
    private static String sanitize(AIResult r) {
        if (r == null || !r.isUsable()) {
            return null;
        }
        String name = r.text().trim()
                .replaceAll("[\"'`]", "")
                .replaceAll("[\\p{Punct}]", "")
                .replaceAll("\\s+", "")
                .trim();
        // 过滤 AI 协议关键词（避免 WORK/FLEE 等被当作名字）
        if (name.matches("(?i)^(WORK|FLEE|ATTACK|PATROL|REST|TRADE|HOLD|ACCEPT|REJECT|IDLE)$")) {
            return null;
        }
        if (name.length() < 2 || name.length() > 12) {
            return name.length() > 12 ? name.substring(0, 12) : null;
        }
        return name.isBlank() ? null : name;
    }
}
