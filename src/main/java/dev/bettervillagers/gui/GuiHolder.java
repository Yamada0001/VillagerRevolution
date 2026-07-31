package dev.bettervillagers.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public final class GuiHolder implements InventoryHolder {

    private final Page page;
    private final int index;
    private Inventory inventory;

    public GuiHolder(Page page, int index) {
        this.page = page;
        this.index = index;
    }

    public Page page() {
        return page;
    }

    public int index() {
        return index;
    }

    void bind(Inventory inventory) {
        if (this.inventory != null) {
            throw new IllegalStateException("GUI holder 已绑定 inventory");
        }
        this.inventory = Objects.requireNonNull(inventory, "inventory");
    }

    @Override
    public @NotNull Inventory getInventory() {
        return Objects.requireNonNull(inventory, "GUI holder 尚未绑定 inventory");
    }

    public enum Page {
        MAIN, PROFESSION, AI, VILLAGE, REGION, SYSTEM
    }
}
