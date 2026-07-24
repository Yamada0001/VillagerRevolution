package dev.bettervillagers.command;

import dev.bettervillagers.BV;
import dev.bettervillagers.ai.AIContext;
import dev.bettervillagers.profession.Profession;
import dev.bettervillagers.redstone.RegionVisualizer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * /bv 主命令分发器（规范 七、命令系统）。
 * <p>
 * 使用 Paper 官方 CommandExecutor（无 NMS、Folia 兼容）。
 */
public final class BVCommand implements CommandExecutor, TabCompleter {

    private final RegionVisualizer visualizer = new RegionVisualizer();

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        String subcommand = args[0].toLowerCase();
        if (requiresAdmin(subcommand) && !sender.hasPermission("bettervillagers.admin")) {
            BV.messages().send(sender, "no-permission");
            return true;
        }
        switch (subcommand) {
            case "help" -> sendHelp(sender);
            case "reload" -> reload(sender);
            case "debug" -> BV.debug().sendDebug(sender);
            case "profession" -> profession(sender, args);
            case "region" -> region(sender, args);
            case "village" -> village(sender, args);
            case "ai" -> ai(sender, args);
            case "sel" -> select(sender);
            case "tp" -> teleport(sender, args);
            default -> BV.messages().send(sender, "unknown-command");
        }
        return true;
    }

    private boolean requiresAdmin(String subcommand) {
        return switch (subcommand) {
            case "reload", "debug", "profession", "ai", "tp" -> true;
            default -> false;
        };
    }

    private void sendHelp(CommandSender sender) {
        BV.messages().send(sender, "help-header");
        BV.messages().send(sender, "help-select-hint");
        line(sender, "/bv reload", BV.messages().raw("help-reload"));
        line(sender, "/bv debug", BV.messages().raw("help-debug"));
        line(sender, "/bv profession " + BV.messages().raw("usage-profession"), BV.messages().raw("help-profession"));
        line(sender, "/bv region create|delete|list|info|viz", BV.messages().raw("help-region"));
        line(sender, "/bv village info|king|stats", BV.messages().raw("help-village"));
        line(sender, "/bv ai toggle|reset|test|chat " + BV.messages().raw("usage-prompt"), BV.messages().raw("help-ai"));
    }

    private void line(CommandSender s, String usage, String desc) {
        BV.messages().send(s, "help-line", "usage", usage, "desc", desc);
    }

    private void select(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            BV.messages().send(sender, "player-only");
            return;
        }
        PlayerSelection.begin(player.getUniqueId());
        BV.messages().send(player, "selection-start");
    }

    /**
     * 传送命令（修复问题3：点击建造消息传送）。
     * 用法：/bv tp x,y,z,world
     */
    private void teleport(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            BV.messages().send(sender, "player-only");
            return;
        }
        if (args.length < 2) {
            return;
        }
        try {
            String[] parts = args[1].split(",");
            if (parts.length < 4) {
                return;
            }
            String worldName = parts[3];
            var world = org.bukkit.Bukkit.getWorld(worldName);
            if (world == null) {
                BV.messages().send(sender, "tp-world-not-found", "world", worldName);
                return;
            }
            double x = Double.parseDouble(parts[0]);
            double y = Double.parseDouble(parts[1]);
            double z = Double.parseDouble(parts[2]);
            // 修复问题3：传送到安全落地位置（从目标 Y 向下找实心地面，上方 2 格无遮挡）
            double safeY = findSafeY(world, (int) x, (int) y, (int) z);
            org.bukkit.Location dest = new org.bukkit.Location(world, x + 0.5, safeY, z + 0.5);
            // Folia 兼容：通过实体调度器执行传送
            BV.scheduler().runForEntity(p, () -> p.teleport(dest), null);
            BV.messages().send(sender, "tp-success", "loc",
                    (int) x + "," + (int) safeY + "," + (int) z);
        } catch (Exception e) {
            BV.messages().send(sender, "tp-failed");
        }
    }

    private void reload(CommandSender sender) {
        BV.config().reload();
        // 规范 6.2：reload 时刷新独立 prompt.yml（提示词模板）
        BV.messages().loadPrompts();
        BV.professions().load();
        // 修复问题8：reload 时重建 AI provider 链，使 provider/api-key/endpoint 立即生效
        if (BV.ai() != null) {
            BV.ai().reconfigure(BV.config().ai(), BV.config().circuitBreaker());
        }
        int count = BV.villagers() != null ? BV.villagers().count() : 0;
        BV.messages().send(sender, "reloaded", "count", String.valueOf(count));
    }

    private void profession(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            BV.messages().send(sender, "player-only");
            return;
        }
        if (args.length < 2) {
            BV.messages().send(sender, "profession-invalid", "profession", "?");
            return;
        }
        Profession prof = Profession.parse(args[1]);
        BVillagerTarget t = resolveTarget(p);
        if (t == null) {
            BV.messages().send(sender, "villager-not-selected");
            return;
        }
        if (BV.villagers().setProfession(t.villager().getUniqueId().toString(), prof)) {
            BV.messages().send(sender, "profession-set", "name", t.name(), "profession", prof.id());
        }
    }

    private void region(CommandSender sender, String[] args) {
        if (args.length < 2) {
            BV.messages().send(sender, "unknown-command");
            return;
        }
        switch (args[1].toLowerCase()) {
            case "create" -> regionCreate(sender, args);
            case "delete" -> regionDelete(sender, args);
            case "list" -> regionList(sender);
            case "info" -> regionInfo(sender, args);
            case "viz", "visualize" -> regionViz(sender, args);
            default -> BV.messages().send(sender, "unknown-command");
        }
    }

    private void regionCreate(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            BV.messages().send(sender, "player-only");
            return;
        }
        if (!p.hasPermission("bettervillagers.redstone.create")) {
            BV.messages().send(sender, "no-permission");
            return;
        }
        String name = args.length > 2 ? args[2] : ("region_" + System.currentTimeMillis() % 10000);
        var loc = p.getLocation();
        // 区域范围参数（原硬编码，规范：魔法值提取为具名局部变量）
        final int half = 16;            // 区域半径（格）
        final int yDown = 4;            // 向下延伸（格）
        final int yUp = 8;              // 向上延伸（格）
        boolean ok = BV.regions().create(name, loc.getWorld().getName(),
                loc.getBlockX() - half, loc.getBlockY() - yDown, loc.getBlockZ() - half,
                loc.getBlockX() + half, loc.getBlockY() + yUp, loc.getBlockZ() + half,
                p.getName());
        if (ok) {
            BV.messages().send(sender, "region-created", "name", name);
        } else {
            BV.messages().send(sender, "region-exists", "name", name);
        }
    }

    private void regionDelete(CommandSender sender, String[] args) {
        if (args.length < 3) {
            return;
        }
        if (!sender.hasPermission("bettervillagers.redstone.delete")) {
            BV.messages().send(sender, "no-permission");
            return;
        }
        if (BV.regions().delete(args[2])) {
            BV.messages().send(sender, "region-deleted", "name", args[2]);
        } else {
            BV.messages().send(sender, "region-not-found", "name", args[2]);
        }
    }

    private void regionList(CommandSender sender) {
        var all = BV.regions().all();
        if (all.isEmpty()) {
            BV.messages().send(sender, "region-none");
            return;
        }
        BV.messages().send(sender, "region-list-header");
        for (var r : all) {
            BV.messages().send(sender, "region-list-line",
                    "name", r.name(), "world", r.world(),
                    "x1", String.valueOf(r.minX()), "y1", String.valueOf(r.minY()), "z1", String.valueOf(r.minZ()),
                    "x2", String.valueOf(r.maxX()), "y2", String.valueOf(r.maxY()), "z2", String.valueOf(r.maxZ()),
                    "owner", String.valueOf(r.owner()));
        }
    }

    private void regionInfo(CommandSender sender, String[] args) {
        if (args.length < 3) {
            return;
        }
        BV.regions().findByName(args[2]).ifPresentOrElse(r -> BV.messages().send(sender, "region-list-line",
                "name", r.name(), "world", r.world(),
                "x1", String.valueOf(r.minX()), "y1", String.valueOf(r.minY()), "z1", String.valueOf(r.minZ()),
                "x2", String.valueOf(r.maxX()), "y2", String.valueOf(r.maxY()), "z2", String.valueOf(r.maxZ()),
                "owner", String.valueOf(r.owner())),
                () -> BV.messages().send(sender, "region-not-found", "name", args[2]));
    }

    private void regionViz(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            BV.messages().send(sender, "player-only");
            return;
        }
        if (args.length < 3) {
            return;
        }
        BV.regions().findByName(args[2]).ifPresent(r -> visualizer.showBoundary(p, r));
    }

    private void village(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            BV.messages().send(sender, "player-only");
            return;
        }
        var opt = nearestVillageOf(p);
        if (opt.isEmpty()) {
            BV.messages().send(sender, "village-none");
            return;
        }
        var v = opt.get();
        String vname = (v.name() == null || v.name().isBlank())
                ? ("#" + v.id())
                : v.name();
        // 修复问题1：国王实时扫描解析（名字/同步 kingUuid）
        String kingName = BV.villages().resolveKingName(v.id());
        switch (args.length < 2 ? "info" : args[1].toLowerCase()) {
            case "info" -> BV.messages().send(sender, "village-info",
                    "id", String.valueOf(v.id()), "name", vname,
                    "pop", String.valueOf(BV.villages().countVillagersInVillage(v.id())),
                    "loc", v.centerX() + "," + v.centerY() + "," + v.centerZ(),
                    "king", kingName);
            case "king" -> {
                // 先触发实时扫描同步 kingUuid
                BV.villages().resolveKingName(v.id());
                var refreshed = BV.villages().get(v.id()).orElse(v);
                if (refreshed.kingUuid() == null || refreshed.kingUuid().isBlank()) {
                    BV.messages().send(sender, "king-absent");
                } else {
                    BV.villagers().get(refreshed.kingUuid()).ifPresentOrElse(
                            bv -> BV.messages().send(sender, "king-info",
                                    "name", bv.name(), "hp", String.valueOf((int) Math.round(entityHp(bv))),
                                    "state", bv.state().name()),
                            () -> BV.messages().send(sender, "king-absent"));
                }
            }
            case "stats" -> {
                // 修复问题4：实时统计村庄范围内的村民数量
                int realPop = BV.villages().countVillagersInVillage(v.id());
                BV.messages().send(sender, "village-info",
                    "id", String.valueOf(v.id()), "name", vname, "pop", String.valueOf(realPop),
                    "loc", v.centerX() + "," + v.centerY() + "," + v.centerZ(),
                    "king", kingName);
            }
            case "verify" -> {
                // 手动触发异常村庄数据校验与去重（异步执行，含 DB 写）
                BV.messages().send(sender, "village-verify-start");
                BV.scheduler().runAsync(() -> {
                    var res = BV.villages().deduplicate();
                    BV.scheduler().runAsync(() -> BV.messages().send(sender, "log.village-verify-done",
                            "scanned", String.valueOf(res.scanned()),
                            "merged", String.valueOf(res.merged()),
                            "flagged", String.valueOf(res.flagged())));
                });
            }
            default -> BV.messages().send(sender, "unknown-command");
        }
    }

    private void ai(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            BV.messages().send(sender, "player-only");
            return;
        }
        BVillagerTarget t = resolveTarget(p);
        if (t == null) {
            BV.messages().send(sender, "villager-not-selected");
            return;
        }
        String uuid = t.villager().getUniqueId().toString();
        if (args.length < 2) {
            return;
        }
        switch (args[1].toLowerCase()) {
            case "toggle" -> {
                boolean nowOn = BV.villagers().toggleAI(uuid);
                if (nowOn) {
                    BV.messages().send(sender, "ai-toggle-on", "name", t.name());
                } else {
                    BV.messages().send(sender, "ai-toggle-off", "name", t.name());
                }
            }
            case "reset" -> {
                BV.villagers().resetMemory(uuid);
                BV.messages().send(sender, "ai-reset", "name", t.name());
            }
            case "test" -> {
                // 规范 1.3：完全异步，立即返回，不阻塞命令发送方输入。
                // 测试请求使用独立 uuid 前缀，避免与战术 AI 共用村民串行锁导致排队。
                String prompt = args.length > 2 ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)) : BV.messages().raw("ai-prompt.test-default");
                BV.messages().send(sender, "ai-test-prompt", "name", t.name(), "prompt", prompt);
                String system = BV.messages().raw("ai-prompt.test-system");
                AIContext ctx = new AIContext("test-" + uuid, t.name(), t.professionId(),
                        "chat", system, prompt);
                BV.ai().decide(ctx)
                        .thenAccept(r -> BV.scheduler().runAsync(() -> {
                            // 修复问题7：降级结果（非真实大模型回复）显示不可用，而非 "WORK"
                            if (r != null && r.isUsable()) {
                                BV.messages().send(sender, "ai-test-response", "name", t.name(), "response", r.text());
                            } else {
                                BV.messages().send(sender, "ai-unavailable");
                            }
                        }))
                        .exceptionally(ex -> {
                            BV.scheduler().runAsync(() -> BV.messages().send(sender, "ai-unavailable"));
                            return null;
                        });
            }
            case "chat" -> {
                // /bv ai chat <message>：注入本村真实事实，禁止模型编造
                String message = args.length > 2 ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)) : BV.messages().raw("ai-prompt.chat-default");
                BV.messages().send(sender, "ai-test-prompt", "name", t.name());
                VillageFacts facts = villageFactsFor(t);
                String system = BV.messages().raw("ai-prompt.chat-system")
                        .replace("{profession}", t.professionId())
                        .replace("{name}", t.name())
                        .replace("{village}", facts.villageName())
                        .replace("{king}", facts.kingName())
                        .replace("{pop}", facts.population())
                        .replace("{self}", t.name());
                String user = BV.messages().raw("ai-prompt.chat-user")
                        .replace("{message}", message)
                        .replace("{name}", t.name())
                        .replace("{village}", facts.villageName())
                        .replace("{king}", facts.kingName())
                        .replace("{pop}", facts.population());
                AIContext ctx = new AIContext("chat-" + uuid, t.name(), t.professionId(),
                        "chat", system, user);
                BV.ai().decide(ctx)
                        .thenAccept(r -> BV.scheduler().runAsync(() -> {
                            if (r != null && r.isUsable()) {
                                BV.messages().send(sender, "ai-test-response", "name", t.name(), "response", r.text());
                            } else {
                                BV.messages().send(sender, "ai-unavailable");
                            }
                        }))
                        .exceptionally(ex -> {
                            BV.scheduler().runAsync(() -> BV.messages().send(sender, "ai-unavailable"));
                            return null;
                        });
            }
            default -> BV.messages().send(sender, "unknown-command");
        }
    }

    // ---- 工具 ----

    private record BVillagerTarget(Villager villager, String name, String professionId, int villageId) {
    }

    private record VillageFacts(String villageName, String kingName, String population) {
    }

    /**
     * 优先使用右键选定的村民；未选定或实体失效时提示玩家先右键，不再静默回退到“最近村民”。
     */
    private BVillagerTarget resolveTarget(Player p) {
        Optional<java.util.UUID> sel = PlayerSelection.get(p.getUniqueId());
        if (sel.isEmpty()) {
            return null;
        }
        Entity e = org.bukkit.Bukkit.getEntity(sel.get());
        if (!(e instanceof Villager v) || v.isDead()) {
            PlayerSelection.clear(p.getUniqueId());
            return null;
        }
        return wrap(v);
    }

    private BVillagerTarget wrap(Villager v) {
        String uuid = v.getUniqueId().toString();
        Optional<dev.bettervillagers.villager.BVillager> opt = BV.villagers() != null
                ? BV.villagers().get(uuid) : Optional.empty();
        String name = opt.map(dev.bettervillagers.villager.BVillager::name).orElse(v.getName());
        if (name == null || name.isBlank()) {
            name = "Villager";
        }
        String prof = opt.map(b -> b.profession() != null ? b.profession().id() : "civilian")
                .orElse(v.getProfession().name().toLowerCase());
        int vid = opt.map(dev.bettervillagers.villager.BVillager::villageId).orElse(-1);
        return new BVillagerTarget(v, name, prof == null || prof.isBlank() ? "civilian" : prof, vid);
    }

    private VillageFacts villageFactsFor(BVillagerTarget t) {
        String unknown = BV.messages().raw("fact-unknown");
        if (unknown == null || unknown.equals("fact-unknown")) {
            unknown = "?";
        }
        int vid = t.villageId();
        if (vid <= 0 && t.villager() != null && BV.villages() != null) {
            var loc = t.villager().getLocation();
            vid = BV.villages().all().stream()
                    .filter(v -> v.covers(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()))
                    .map(dev.bettervillagers.village.Village::id)
                    .findFirst()
                    .orElse(-1);
        }
        if (vid <= 0 || BV.villages() == null) {
            return new VillageFacts(unknown, unknown, "0");
        }
        var villageOpt = BV.villages().get(vid);
        if (villageOpt.isEmpty()) {
            return new VillageFacts(unknown, unknown, "0");
        }
        var village = villageOpt.get();
        String vname = village.name() == null || village.name().isBlank()
                ? ("#" + village.id()) : village.name();
        String king = BV.villages().resolveKingName(vid);
        if (king == null || king.isBlank()) {
            king = unknown;
        }
        int pop = BV.villages().countVillagersInVillage(vid);
        return new VillageFacts(vname, king, String.valueOf(pop));
    }

    private Optional<dev.bettervillagers.village.Village> nearestVillageOf(Player p) {
        int x = p.getLocation().getBlockX();
        int z = p.getLocation().getBlockZ();
        String world = p.getWorld().getName();
        return BV.villages().all().stream()
                .filter(v -> v.world().equals(world))
                .min(java.util.Comparator.comparingInt(v ->
                        Math.abs(v.centerX() - x) + Math.abs(v.centerZ() - z)));
    }

    private double entityHp(dev.bettervillagers.villager.BVillager bv) {
        return bv.entity() != null ? bv.entity().getHealth() : 0;
    }

    /**
     * 从目标 Y 向下查找安全的落地高度（修复问题3：遮挡物检测）。
     * <p>
     * 从给定 y 开始向下搜索，找到第一个实心方块且其上方两格为空气的坐标。
     * 最高建筑通常不超过 64 格高，最多向下搜索 30 格防止死循环。
     */
    private double findSafeY(org.bukkit.World world, int x, int y, int z) {
        int maxY = world.getMaxHeight();
        int startY = Math.min(y + 2, maxY - 2);
        for (int cy = startY; cy > world.getMinHeight() + 1; cy--) {
            var foot = world.getBlockAt(x, cy - 1, z).getType();
            var head1 = world.getBlockAt(x, cy, z).getType();
            var head2 = world.getBlockAt(x, cy + 1, z).getType();
            if (foot.isSolid() && head1.isAir() && head2.isAir()) {
                return cy;
            }
        }
        return y;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(List.of("help", "reload", "debug", "profession", "region", "village", "ai"));
        } else if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "profession" -> out.addAll(java.util.Arrays.stream(Profession.values()).map(Profession::id).toList());
                case "region" -> out.addAll(List.of("create", "delete", "list", "info", "viz"));
                case "village" -> out.addAll(List.of("info", "king", "stats"));
                case "ai" -> out.addAll(List.of("toggle", "reset", "test", "chat"));
                default -> {
                }
            }
        }
        String prefix = args[args.length - 1].toLowerCase();
        return out.stream().filter(s -> s.startsWith(prefix)).toList();
    }
}
