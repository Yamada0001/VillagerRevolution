package dev.bettervillagers.command;

import dev.bettervillagers.BV;
import dev.bettervillagers.ai.AIContext;
import dev.bettervillagers.gui.BetterVillagersGui;
import dev.bettervillagers.profession.Profession;
import dev.bettervillagers.redstone.RegionSelectionListener;
import dev.bettervillagers.redstone.RegionVisualizer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * /bv 主命令分发器（规范 七、命令系统）。
 * <p>
 * 使用 Paper 官方 CommandExecutor（无 NMS、Folia 兼容）。
 */
public final class BVCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN_PERMISSION = "bettervillagers.admin";
    private static final String REGION_VIEW_PERMISSION = "bettervillagers.redstone.view";

    private final RegionVisualizer visualizer = new RegionVisualizer();
    private final RegionSelectionListener regionSelection;
    private BetterVillagersGui gui;

    public BVCommand(RegionSelectionListener regionSelection) {
        this.regionSelection = regionSelection;
    }

    public void gui(BetterVillagersGui gui) {
        this.gui = gui;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String @NonNull [] args) {
        execute(sender, args);
        return true;
    }

    public void execute(Player player, String[] args) {
        execute((CommandSender) player, args);
    }

    private void execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) {
                gui.openMain(player);
            } else {
                BV.messages().send(sender, "player-only");
            }
            return;
        }
        String subcommand = args[0].toLowerCase();
        if (requiresAdmin(subcommand) && !sender.hasPermission(ADMIN_PERMISSION)) {
            BV.messages().send(sender, "no-permission");
            return;
        }
        switch (subcommand) {
            case "help" -> sendHelp(sender);
            case "gui" -> {
                if (sender instanceof Player player) {
                    gui.openMain(player);
                } else {
                    BV.messages().send(sender, "player-only");
                }
            }
            case "reload" -> reload(sender);
            case "debug" -> BV.debug().sendDebug(sender);
            case "profession" -> profession(sender, args);
            case "region" -> region(sender, args);
            case "yes" -> regionConfirm(sender);
            case "rename" -> regionRename(sender, args);
            case "village" -> village(sender, args);
            case "ai" -> ai(sender, args);
            case "sel" -> select(sender);
            case "tp" -> teleport(sender, args);
            default -> BV.messages().send(sender, "unknown-command");
        }
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
        line(sender, "/bv yes", BV.messages().raw("help-region-confirm"));
        line(sender, "/bv rename <当前名字> <新名字>", BV.messages().raw("help-region-rename"));
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
            double x = Double.parseDouble(parts[0]);
            double y = Double.parseDouble(parts[1]);
            double z = Double.parseDouble(parts[2]);
            BV.scheduler().runGlobal(() -> {
                var world = org.bukkit.Bukkit.getWorld(worldName);
                if (world == null) {
                    respond(sender, () -> BV.messages().send(sender,
                            "tp-world-not-found", "world", worldName));
                    return;
                }
                org.bukkit.Location probe = new org.bukkit.Location(world, x, y, z);
                BV.scheduler().runAtRegion(probe, () -> {
                    double safeY = findSafeY(world, (int) x, (int) y, (int) z);
                    org.bukkit.Location destination = new org.bukkit.Location(
                            world, x + 0.5, safeY, z + 0.5);
                    BV.scheduler().runForEntity(p, () -> p.teleportAsync(destination)
                            .thenAccept(success -> respond(sender, () -> BV.messages().send(sender,
                                    success ? "tp-success" : "tp-failed", "loc",
                                    (int) x + "," + (int) safeY + "," + (int) z))), null);
                });
            });
        } catch (Exception e) {
            BV.messages().send(sender, "tp-failed");
        }
    }

    private void reload(CommandSender sender) {
        if (BV.plugin() instanceof dev.bettervillagers.BetterVillagersPlugin plugin) {
            plugin.reloadRuntime();
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
        Optional<Profession> requested = Profession.find(args[1]);
        if (requested.isEmpty()) {
            BV.messages().send(sender, "profession-invalid", "profession", args[1]);
            return;
        }
        Profession prof = requested.orElseThrow();
        BVillagerTarget t = resolveTarget(p);
        if (t == null) {
            BV.messages().send(sender, "villager-not-selected");
            return;
        }
        if (BV.villagers().setProfession(t.uuid(), prof)) {
            BV.messages().send(sender, "profession-set", "name", t.name(), "profession", prof.id());
        } else {
            BV.messages().send(sender, "profession-change-failed", "profession", prof.id());
        }
    }

    private void region(CommandSender sender, String[] args) {
        if (args.length < 2) {
            BV.messages().send(sender, "unknown-command");
            return;
        }
        switch (args[1].toLowerCase()) {
            case "create" -> regionCreate(sender);
            case "delete" -> regionDelete(sender, args);
            case "list" -> regionList(sender);
            case "info" -> regionInfo(sender, args);
            case "viz", "visualize" -> regionViz(sender, args);
            default -> BV.messages().send(sender, "unknown-command");
        }
    }

    private void regionCreate(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            BV.messages().send(sender, "player-only");
            return;
        }
        if (!player.hasPermission("bettervillagers.redstone.create")) {
            BV.messages().send(sender, "no-permission");
            return;
        }
        regionSelection.begin(player);
    }

    private void regionConfirm(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            BV.messages().send(sender, "player-only");
            return;
        }
        if (!player.hasPermission("bettervillagers.redstone.create")) {
            BV.messages().send(sender, "no-permission");
            return;
        }
        Optional<RegionSelectionListener.Selection> selection = regionSelection.takeSelection(player.getUniqueId());
        if (selection.isEmpty()) {
            BV.messages().send(player, "region-selection-incomplete");
            return;
        }
        RegionSelectionListener.Selection points = selection.get();
        String name = nextRegionName();
        BV.regions().create(name, points.first().getWorld().getName(),
                points.first().getBlockX(), points.first().getBlockY(), points.first().getBlockZ(),
                points.second().getBlockX(), points.second().getBlockY(), points.second().getBlockZ(), player.getName())
                .whenComplete((created, failure) -> respond(sender, () ->
                        BV.messages().send(player,
                                failure != null ? "region-persistence-failed"
                                        : created ? "region-created" : "region-exists",
                                "name", name)));
    }

    private void regionRename(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            BV.messages().send(sender, "player-only");
            return;
        }
        String input = args.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)) : "";
        ProtectedRegionName rename = resolveRename(input);
        if (rename == null) {
            BV.messages().send(player, "region-rename-usage");
            return;
        }
        boolean owner = BV.regions().findByName(rename.current())
                .map(region -> player.getName().equalsIgnoreCase(region.owner())).orElse(false);
        if (!owner && !player.hasPermission("bettervillagers.redstone.modify")) {
            BV.messages().send(player, "no-permission");
            return;
        }
        BV.regions().modify(rename.current(), rename.replacement(), null)
                .whenComplete((modified, failure) -> respond(sender, () -> {
                    if (failure != null) {
                        BV.messages().send(player, "region-persistence-failed");
                    } else if (modified) {
                        BV.messages().send(player, "region-renamed", "old", rename.current(),
                                "new", rename.replacement());
                    } else {
                        BV.messages().send(player, "region-rename-failed", "name", rename.replacement());
                    }
                }));
    }

    private ProtectedRegionName resolveRename(String input) {
        return BV.regions().all().stream()
                .map(dev.bettervillagers.redstone.ProtectedRegion::name)
                .filter(name -> input.length() > name.length() && input.regionMatches(true, 0, name, 0, name.length())
                        && Character.isWhitespace(input.charAt(name.length())))
                .max(java.util.Comparator.comparingInt(String::length))
                .map(current -> new ProtectedRegionName(current, input.substring(current.length()).trim()))
                .filter(rename -> !rename.replacement().isEmpty())
                .orElse(null);
    }

    private String nextRegionName() {
        int sequence = 1;
        String name;
        do {
            name = "region_" + sequence++;
        } while (BV.regions().findByName(name).isPresent());
        return name;
    }

    private record ProtectedRegionName(String current, String replacement) {
    }

    private void regionDelete(CommandSender sender, String[] args) {
        if (args.length < 3) {
            return;
        }
        if (!sender.hasPermission("bettervillagers.redstone.delete")) {
            BV.messages().send(sender, "no-permission");
            return;
        }
        String name = args[2];
        BV.regions().delete(name).whenComplete((deleted, failure) -> respond(sender, () -> {
            if (failure != null) {
                BV.messages().send(sender, "region-persistence-failed");
            } else if (deleted) {
                BV.messages().send(sender, "region-deleted", "name", name);
            } else {
                BV.messages().send(sender, "region-not-found", "name", name);
            }
        }));
    }

    private boolean canViewRegions(CommandSender sender) {
        return sender.hasPermission(ADMIN_PERMISSION) || sender.hasPermission(REGION_VIEW_PERMISSION);
    }

    private void respond(CommandSender sender, Runnable response) {
        if (sender instanceof Player player) {
            BV.scheduler().runForEntity(player, response, null);
        } else {
            BV.scheduler().runGlobal(response);
        }
    }

    private void regionList(CommandSender sender) {
        if (!canViewRegions(sender)) {
            BV.messages().send(sender, "no-permission");
            return;
        }
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
        if (!canViewRegions(sender)) {
            BV.messages().send(sender, "no-permission");
            return;
        }
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
        if (!canViewRegions(sender)) {
            BV.messages().send(sender, "no-permission");
            return;
        }
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
        String location = v.centerX() + "," + v.centerY() + "," + v.centerZ();
        switch (args.length < 2 ? "info" : args[1].toLowerCase()) {
            case "info" -> BV.messages().send(sender, "village-info",
                    "id", String.valueOf(v.id()), "name", vname,
                    "pop", String.valueOf(BV.villages().countVillagersInVillage(v.id())),
                    "loc", location,
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
                    "loc", location,
                    "king", kingName);
            }
            case "verify" -> {
                if (!sender.hasPermission(ADMIN_PERMISSION)) {
                    BV.messages().send(sender, "no-permission");
                    return;
                }
                // 手动触发异常村庄数据校验与去重（异步执行，含 DB 写）
                BV.messages().send(sender, "village-verify-start");
                BV.scheduler().runAsync(() -> {
                    var res = BV.villages().deduplicate();
                    sendAsync(sender, "village-verify-done",
                            "scanned", String.valueOf(res.scanned()),
                            "merged", String.valueOf(res.merged()),
                            "flagged", String.valueOf(res.flagged()));
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
        String uuid = t.uuid();
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
                        .thenAccept(r -> {
                            // 修复问题7：降级结果（非真实大模型回复）显示不可用，而非 "WORK"
                            if (r != null && r.isUsable()) {
                                sendAsync(sender, "ai-test-response", "name", t.name(), "response", r.text());
                            } else {
                                sendAsync(sender, "ai-unavailable");
                            }
                        })
                        .exceptionally(ex -> handleAiFailure(sender, ex));
            }
            case "chat" -> {
                // /bv ai chat <message>：注入本村真实事实，禁止模型编造
                String message = args.length > 2 ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)) : BV.messages().raw("ai-prompt.chat-default");
                BV.messages().send(sender, "ai-test-prompt", "name", t.name());
                VillageFacts facts = villageFactsFor(t);
                String equipment = playerEquipmentFacts(p);
                String system = BV.messages().raw("ai-prompt.chat-system")
                        .replace("{profession}", t.professionId())
                        .replace("{name}", t.name())
                        .replace("{village}", facts.villageName())
                        .replace("{king}", facts.kingName())
                        .replace("{pop}", facts.population())
                        .replace("{equipment}", equipment)
                        .replace("{self}", t.name());
                String user = BV.messages().raw("ai-prompt.chat-user")
                        .replace("{message}", message)
                        .replace("{name}", t.name())
                        .replace("{village}", facts.villageName())
                        .replace("{king}", facts.kingName())
                        .replace("{pop}", facts.population())
                        .replace("{equipment}", equipment);
                AIContext ctx = new AIContext("chat-" + uuid, t.name(), t.professionId(),
                        "chat", system, user);
                BV.ai().decide(ctx)
                        .thenAccept(r -> {
                            if (r != null && r.isUsable()) {
                                sendAsync(sender, "ai-test-response", "name", t.name(), "response", r.text());
                            } else {
                                sendAsync(sender, "ai-unavailable");
                            }
                        })
                        .exceptionally(ex -> handleAiFailure(sender, ex));
            }
            default -> BV.messages().send(sender, "unknown-command");
        }
    }

    // ---- 工具 ----

    /** 将异步工作结果投递回命令发送者所属的安全线程。 */
    private void sendAsync(CommandSender sender, String key, String... pairs) {
        if (sender instanceof Player player) {
            BV.scheduler().runForEntity(player, () -> BV.messages().send(player, key, pairs), null);
            return;
        }
        BV.scheduler().runGlobal(() -> BV.messages().send(sender, key, pairs));
    }

    private Void handleAiFailure(CommandSender sender, Throwable ex) {
        if (BV.config().debugMode() && ex != null) {
            BV.plugin().getLogger().warning("AI command failed: " + ex.getMessage());
        }
        sendAsync(sender, "ai-unavailable");
        return null;
    }

    private record BVillagerTarget(String uuid, String name, String professionId, int villageId,
                                  dev.bettervillagers.villager.BVillager.PositionSnapshot position) {
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
        if (BV.villagers() == null) {
            return null;
        }
        Optional<dev.bettervillagers.villager.BVillager> selected =
                BV.villagers().get(sel.get().toString());
        if (selected.isEmpty()) {
            PlayerSelection.clear(p.getUniqueId());
            return null;
        }
        dev.bettervillagers.villager.BVillager bv = selected.orElseThrow();
        String name = bv.name();
        if (name == null || name.isBlank()) {
            name = "Villager";
        }
        String prof = bv.profession() == null ? "civilian" : bv.profession().id();
        return new BVillagerTarget(bv.uuid(), name, prof.isBlank() ? "civilian" : prof,
                bv.villageId(), bv.lastKnownPosition());
    }

    private String playerEquipmentFacts(Player player) {
        List<String> equipment = new ArrayList<>();
        addEquipmentFact(equipment, "main-hand", player.getInventory().getItemInMainHand());
        addEquipmentFact(equipment, "helmet", player.getInventory().getHelmet());
        addEquipmentFact(equipment, "chestplate", player.getInventory().getChestplate());
        addEquipmentFact(equipment, "leggings", player.getInventory().getLeggings());
        addEquipmentFact(equipment, "boots", player.getInventory().getBoots());
        return equipment.isEmpty() ? BV.messages().raw("ai-prompt.chat-equipment-empty") : String.join("; ", equipment);
    }

    private void addEquipmentFact(List<String> equipment, String slot, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return;
        }
        net.kyori.adventure.text.Component displayName = item.hasItemMeta()
                ? item.getItemMeta().displayName() : null;
        String itemName = displayName == null ? item.getType().translationKey()
                : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(displayName);
        equipment.add(BV.messages().raw("ai-prompt.chat-equipment-entry")
                .replace("{slot}", BV.messages().raw("ai-prompt.chat-equipment-slot." + slot))
                .replace("{item}", itemName)
                .replace("{amount}", String.valueOf(item.getAmount())));
    }

    private VillageFacts villageFactsFor(BVillagerTarget t) {
        String unknown = BV.messages().raw("fact-unknown");
        if (unknown == null || unknown.equals("fact-unknown")) {
            unknown = "?";
        }
        int vid = t.villageId();
        if (vid <= 0 && t.position() != null && BV.villages() != null) {
            var position = t.position();
            vid = BV.villages().all().stream()
                    .filter(v -> v.covers(position.world(), (int) Math.floor(position.x()),
                            (int) Math.floor(position.y()), (int) Math.floor(position.z())))
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
        return bv.lastKnownHealth();
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
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String @NonNull [] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(List.of("help", "gui", "sel", "region", "yes", "rename", "village"));
            if (sender.hasPermission(ADMIN_PERMISSION)) {
                out.addAll(List.of("reload", "debug", "profession", "ai", "tp"));
            }
        } else if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "profession" -> out.addAll(java.util.Arrays.stream(Profession.values()).map(Profession::id).toList());
                case "region" -> {
                    if (canViewRegions(sender)) {
                        out.addAll(List.of("list", "info", "viz"));
                    }
                    if (sender.hasPermission("bettervillagers.redstone.create")) {
                        out.add("create");
                    }
                    if (sender.hasPermission("bettervillagers.redstone.delete")) {
                        out.add("delete");
                    }
                }
                case "village" -> {
                    out.addAll(List.of("info", "king", "stats"));
                    if (sender.hasPermission(ADMIN_PERMISSION)) {
                        out.add("verify");
                    }
                }
                case "ai" -> out.addAll(List.of("toggle", "reset", "test", "chat"));
                default -> {
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("region")
                && List.of("delete", "info", "viz", "visualize").contains(args[1].toLowerCase())) {
            String regionAction = args[1].toLowerCase();
            boolean canCompleteRegionNames = ("delete".equals(regionAction) && sender.hasPermission("bettervillagers.redstone.delete"))
                    || (List.of("info", "viz", "visualize").contains(regionAction) && canViewRegions(sender));
            if (canCompleteRegionNames) {
                out.addAll(BV.regions().all().stream()
                        .map(dev.bettervillagers.redstone.ProtectedRegion::name).toList());
            }
        }
        String prefix = args[args.length - 1].toLowerCase();
        return out.stream().filter(s -> s.toLowerCase().startsWith(prefix)).toList();
    }
}
