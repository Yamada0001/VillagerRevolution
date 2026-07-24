package dev.bettervillagers.debug;

import dev.bettervillagers.BV;
import dev.bettervillagers.scheduler.PlatformDetector;
import org.bukkit.command.CommandSender;

/**
 * 调试监控（规范 4.5 可观测性：/bv debug 输出运行状态与指标）。
 * 所有标签文本经 i18n 获取（规范 6.2 / 用户规则）。
 */
public final class DebugMonitor {

    public void sendDebug(CommandSender sender) {
        BV.messages().send(sender, "debug-header");
        BV.messages().send(sender, "debug-platform", "platform", PlatformDetector.isFolia() ? "Folia" : "Paper");
        int online = BV.villagers() != null ? BV.villagers().count() : 0;
        BV.messages().send(sender, "debug-online-villagers", "count", String.valueOf(online));
        int activeAi = 0;
        if (BV.villagers() != null) {
            activeAi = (int) BV.villagers().all().stream()
                    .filter(dev.bettervillagers.villager.BVillager::aiEnabled).count();
        }
        BV.messages().send(sender, "debug-active-ai", "count", String.valueOf(activeAi));

        String state = BV.messages().raw("debug-circuit-disabled");
        if (BV.ai() != null && BV.ai().breaker() != null) {
            state = BV.ai().breaker().state().name();
        }
        BV.messages().send(sender, "debug-circuit", "state", state);

        String stats = BV.messages().raw("debug-stats-format")
                .replace("{provider}", BV.ai() != null ? BV.ai().primaryProviderId() : "-")
                .replace("{hits}", String.valueOf(BV.ai() != null ? BV.ai().cache().hits() : 0))
                .replace("{misses}", String.valueOf(BV.ai() != null ? BV.ai().cache().misses() : 0));
        BV.messages().send(sender, "debug-provider-latency", "stats", stats);
    }
}
