package com.eldros.market.paper.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/** Identifies our menus by holder, never by title (docs/03 §11). */
public final class MenuHolder implements InventoryHolder {

    private final Menu menu;
    private Inventory inventory;

    MenuHolder(Menu menu) {
        this.menu = menu;
    }

    void bind(Inventory inventory) {
        this.inventory = inventory;
    }

    public Menu menu() {
        return menu;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
