package dev.bettervillagers.gui;

import dev.bettervillagers.BV;
import dev.bettervillagers.command.BVCommand;
import dev.bettervillagers.i18n.MessageService;
import dev.bettervillagers.profession.Profession;
import dev.bettervillagers.redstone.ProtectedRegion;
import dev.bettervillagers.redstone.RegionSelectionListener;
import dev.bettervillagers.village.Village;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Optional;

public final class BetterVillagersGui implements Listener {

    private static final int SIZE = 54;
    private static final int BACK_SLOT = 45;
    private static final int CLOSE_SLOT = 49;
    private static final int PREVIOUS_SLOT = 47;
    private static final int NEXT_SLOT = 51;
    private static final int REGION_FIRST_SLOT = 0;
    private static final int REGION_PAGE_SIZE = 45;

    private final BVCommand command;
    private final RegionSelectionListener regionSelection;

    public BetterVillagersGui(BVCommand command, RegionSelectionListener regionSelection) {
        this.command = command;
        this.regionSelection = regionSelection;
    }

    public void openMain(Player player) {
        Inventory inventory = inventory(GuiHolder.Page.MAIN, 0);
        inventory.setItem(10, item(player, Material.VILLAGER_SPAWN_EGG, "gui.main.villager"));
        inventory.setItem(12, item(player, Material.EMERALD, "gui.main.profession", "bettervillagers.admin"));
        inventory.setItem(14, item(player, Material.REDSTONE, "gui.main.ai", "bettervillagers.admin"));
        inventory.setItem(28, item(player, Material.BELL, "gui.main.village"));
        inventory.setItem(30, item(player, Material.OBSERVER, "gui.main.region"));
        inventory.setItem(32, item(player, Material.COMPARATOR, "gui.main.system", "bettervillagers.admin"));
        inventory.setItem(CLOSE_SLOT, item(player, Material.BARRIER, "gui.common.close"));
        player.openInventory(inventory);
    }

    private void openProfession(Player player) {
        Inventory inventory = inventory(GuiHolder.Page.PROFESSION, 0);
        Profession[] professions = Profession.values();
        for (int slot = 0; slot < professions.length; slot++) {
            Profession profession = professions[slot];
            inventory.setItem(slot, item(player, professionMaterial(profession), "gui.profession.entry", "bettervillagers.admin",
                    "profession", BV.messages().raw("professions." + profession.id())));
        }
        controls(player, inventory);
        player.openInventory(inventory);
    }

    private Material professionMaterial(Profession profession) {
        return switch (profession) {
            case KING -> Material.GOLDEN_HELMET;
            case KNIGHT -> Material.IRON_SWORD;
            case SOLDIER -> Material.SHIELD;
            case ARCHER -> Material.BOW;
            case BUTCHER -> Material.IRON_AXE;
            case CHEF -> Material.COOKED_BEEF;
            case FARMER -> Material.WOODEN_HOE;
            case MINER -> Material.IRON_PICKAXE;
            case BUILDER -> Material.BRICKS;
            case MERCHANT -> Material.EMERALD;
            case DOCTOR -> Material.POTION;
            case FISHERMAN -> Material.FISHING_ROD;
            case ENCHANTER -> Material.ENCHANTED_BOOK;
            case BLACKSMITH -> Material.ANVIL;
            case CIVILIAN -> Material.VILLAGER_SPAWN_EGG;
        };
    }

    private void openAi(Player player) {
        Inventory inventory = inventory(GuiHolder.Page.AI, 0);
        inventory.setItem(11, item(player, Material.REDSTONE_TORCH, "gui.ai.toggle", "bettervillagers.admin"));
        inventory.setItem(13, item(player, Material.MILK_BUCKET, "gui.ai.reset", "bettervillagers.admin"));
        inventory.setItem(15, item(player, Material.WRITABLE_BOOK, "gui.ai.test", "bettervillagers.admin"));
        inventory.setItem(31, item(player, Material.PAPER, "gui.ai.chat", "bettervillagers.admin"));
        controls(player, inventory);
        player.openInventory(inventory);
    }

    private void openVillage(Player player) {
        Inventory inventory = inventory(GuiHolder.Page.VILLAGE, 0);
        Optional<Village> village = nearestVillageOf(player);
        if (village.isPresent()) {
            Village current = village.get();
            String name = villageName(current);
            String king = BV.villages().resolveKingName(current.id());
            String population = String.valueOf(BV.villages().countVillagersInVillage(current.id()));
            String location = current.centerX() + "," + current.centerY() + "," + current.centerZ();
            inventory.setItem(11, item(player, Material.BOOK, "gui.village.info", null,
                    "id", String.valueOf(current.id()), "name", name, "population", population, "location", location, "radius", String.valueOf(current.radius())));
            inventory.setItem(13, item(player, Material.GOLDEN_HELMET, "gui.village.king", null,
                    "name", king));
            inventory.setItem(15, item(player, Material.FILLED_MAP, "gui.village.stats", null,
                    "population", population, "radius", String.valueOf(current.radius())));
        } else {
            inventory.setItem(11, item(player, Material.BOOK, "gui.village.none"));
            inventory.setItem(13, item(player, Material.GOLDEN_HELMET, "gui.village.none"));
            inventory.setItem(15, item(player, Material.FILLED_MAP, "gui.village.none"));
        }
        inventory.setItem(31, item(player, Material.ENDER_EYE, "gui.village.verify", "bettervillagers.admin"));
        controls(player, inventory);
        player.openInventory(inventory);
    }

    private void openRegion(Player player, int page) {
        List<ProtectedRegion> regions = BV.regions().all();
        int start = page * REGION_PAGE_SIZE;
        if (start >= regions.size() && page > 0) {
            openRegion(player, page - 1);
            return;
        }
        Inventory inventory = inventory(GuiHolder.Page.REGION, page);
        for (int slot = 0; slot < REGION_PAGE_SIZE && start + slot < regions.size(); slot++) {
            ProtectedRegion region = regions.get(start + slot);
            inventory.setItem(REGION_FIRST_SLOT + slot, item(player, Material.REDSTONE_BLOCK, "gui.region.entry", null,
                    "name", region.name(), "world", region.world(), "x1", String.valueOf(region.minX()), "y1", String.valueOf(region.minY()), "z1", String.valueOf(region.minZ()),
                    "x2", String.valueOf(region.maxX()), "y2", String.valueOf(region.maxY()), "z2", String.valueOf(region.maxZ()), "owner", String.valueOf(region.owner())));
        }
        inventory.setItem(BACK_SLOT, item(player, Material.ARROW, "gui.common.back"));
        inventory.setItem(46, item(player, Material.ANVIL, "gui.region.create", "bettervillagers.redstone.create"));
        inventory.setItem(CLOSE_SLOT, item(player, Material.BARRIER, "gui.common.close"));
        if (page > 0) {
            inventory.setItem(PREVIOUS_SLOT, item(player, Material.ARROW, "gui.common.previous"));
        }
        if (start + REGION_PAGE_SIZE < regions.size()) {
            inventory.setItem(NEXT_SLOT, item(player, Material.ARROW, "gui.common.next"));
        }
        player.openInventory(inventory);
    }

    private void openSystem(Player player) {
        Inventory inventory = inventory(GuiHolder.Page.SYSTEM, 0);
        inventory.setItem(11, item(player, Material.COMPASS, "gui.system.select"));
        inventory.setItem(13, item(player, Material.REDSTONE_LAMP, "gui.system.debug", "bettervillagers.admin"));
        inventory.setItem(15, item(player, Material.CLOCK, "gui.system.reload", "bettervillagers.admin"));
        controls(player, inventory);
        player.openInventory(inventory);
    }

    private Inventory inventory(GuiHolder.Page page, int index) {
        GuiHolder holder = new GuiHolder(page, index);
        Inventory inventory = Bukkit.createInventory(holder, SIZE,
                MessageService.deserialize(BV.messages().raw("gui.title." + page.name().toLowerCase())));
        holder.bind(inventory);
        return inventory;
    }

    private void controls(Player player, Inventory inventory) {
        inventory.setItem(BACK_SLOT, item(player, Material.ARROW, "gui.common.back"));
        inventory.setItem(CLOSE_SLOT, item(player, Material.BARRIER, "gui.common.close"));
    }

    private ItemStack item(Player player, Material material, String key) {
        return item(player, material, key, null);
    }

    private ItemStack item(Player player, Material material, String key, String permission, String... pairs) {
        boolean locked = permission != null && !player.hasPermission(permission);
        ItemStack stack = new ItemStack(locked ? Material.BARRIER : material);
        ItemMeta meta = stack.getItemMeta();
        String displayKey = locked ? "gui.common.locked.name" : key + ".name";
        List<String> lore = BV.messages().rawList(locked ? "gui.common.locked.lore" : key + ".lore");
        meta.displayName(MessageService.deserialize(replace(BV.messages().raw(displayKey), pairs)));
        meta.lore(lore.stream().map(line -> MessageService.deserialize(replace(line, pairs))).toList());
        stack.setItemMeta(meta);
        return stack;
    }

    private String replace(String text, String... pairs) {
        String result = text;
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            result = result.replace("{" + pairs[i] + "}", pairs[i + 1]);
        }
        return result;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof GuiHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() < 0 || event.getRawSlot() >= SIZE) {
            return;
        }
        if (event.getCurrentItem() == null || event.getCurrentItem().getType().isAir()) {
            return;
        }
        if (event.getRawSlot() == CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        if (event.getRawSlot() == BACK_SLOT) {
            openMain(player);
            return;
        }
        switch (holder.page()) {
            case MAIN -> handleMain(player, event.getRawSlot());
            case PROFESSION -> profession(player, event.getRawSlot());
            case AI -> ai(player, event.getRawSlot());
            case VILLAGE -> village(player, event.getRawSlot());
            case REGION -> region(player, holder.index(), event);
            case SYSTEM -> system(player, event.getRawSlot());
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof GuiHolder) {
            event.setCancelled(true);
        }
    }

    private void handleMain(Player player, int slot) {
        switch (slot) {
            case 10 -> command.execute(player, new String[]{"sel"});
            case 12 -> openProfession(player);
            case 14 -> openAi(player);
            case 28 -> openVillage(player);
            case 30 -> openRegion(player, 0);
            case 32 -> openSystem(player);
            default -> { }
        }
    }

    private void profession(Player player, int slot) {
        if (!player.hasPermission("bettervillagers.admin") || slot >= Profession.values().length) {
            return;
        }
        command.execute(player, new String[]{"profession", Profession.values()[slot].id()});
    }

    private void ai(Player player, int slot) {
        if (!player.hasPermission("bettervillagers.admin")) {
            return;
        }
        if (slot == 11) {
            command.execute(player, new String[]{"ai", "toggle"});
        } else if (slot == 13) {
            command.execute(player, new String[]{"ai", "reset"});
        } else if (slot == 15) {
            prompt(player, "gui.prompt.ai-test");
        } else if (slot == 31) {
            prompt(player, "gui.prompt.ai-chat");
        }
    }

    private void village(Player player, int slot) {
        if (slot == 31 && player.hasPermission("bettervillagers.admin")) {
            player.closeInventory();
            command.execute(player, new String[]{"village", "verify"});
        }
    }

    private void region(Player player, int page, InventoryClickEvent event) {
        int slot = event.getRawSlot();
        if (slot == PREVIOUS_SLOT && page > 0) {
            openRegion(player, page - 1);
        } else if (slot == NEXT_SLOT) {
            openRegion(player, page + 1);
        } else if (slot == 46 && player.hasPermission("bettervillagers.redstone.create")) {
            player.closeInventory();
            regionSelection.begin(player);
        } else if (slot < REGION_PAGE_SIZE) {
            List<ProtectedRegion> regions = BV.regions().all();
            int index = page * REGION_PAGE_SIZE + slot;
            if (index >= regions.size()) {
                return;
            }
            String name = regions.get(index).name();
            if (event.isShiftClick() && event.isRightClick()) {
                if (player.hasPermission("bettervillagers.redstone.delete")) {
                    command.execute(player, new String[]{"region", "delete", name});
                }
            } else if (event.isShiftClick()) {
                ProtectedRegion selected = regions.get(index);
                if (player.hasPermission("bettervillagers.redstone.modify") || player.getName().equalsIgnoreCase(selected.owner())) {
                    regionSelection.beginRename(player, name);
                }
            } else if (event.isRightClick()) {
                command.execute(player, new String[]{"region", "info", name});
            } else {
                command.execute(player, new String[]{"region", "viz", name});
            }
        }
    }

    private void system(Player player, int slot) {
        if (slot == 11) {
            command.execute(player, new String[]{"sel"});
        } else if (slot == 13 && player.hasPermission("bettervillagers.admin")) {
            command.execute(player, new String[]{"debug"});
        } else if (slot == 15 && player.hasPermission("bettervillagers.admin")) {
            command.execute(player, new String[]{"reload"});
        }
    }

    private void prompt(Player player, String key) {
        player.closeInventory();
        BV.messages().send(player, key);
    }

    private Optional<Village> nearestVillageOf(Player player) {
        int x = player.getLocation().getBlockX();
        int z = player.getLocation().getBlockZ();
        String world = player.getWorld().getName();
        return BV.villages().all().stream()
                .filter(village -> village.world().equals(world))
                .min(java.util.Comparator.comparingInt(village -> Math.abs(village.centerX() - x) + Math.abs(village.centerZ() - z)));
    }

    private String villageName(Village village) {
        return village.name() == null || village.name().isBlank()
                ? BV.messages().raw("village-id-format").replace("{id}", String.valueOf(village.id()))
                : village.name();
    }
}
