package com.eldros.market.paper.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds menu icons. Every icon carries a marker, so an icon that ever ends up in a real inventory (a client or
 * server bug around a cancelled click) is recognised and removed instead of becoming a free item.
 */
public final class Icons {

    private final NamespacedKey marker;

    public Icons(JavaPlugin plugin) {
        this.marker = new NamespacedKey(plugin, "menu_icon");
    }

    public ItemStack of(Material material, Component name, List<Component> lore) {
        return decorate(new ItemStack(material), name, lore, false);
    }

    /** A copy of a real item (template) with our lore appended under its own. */
    public ItemStack ofItem(ItemStack item, int amount, List<Component> extraLore) {
        ItemStack copy = item.clone();
        copy.setAmount(Math.max(1, Math.min(amount, copy.getMaxStackSize())));
        return decorate(copy, null, extraLore, true);
    }

    private ItemStack decorate(ItemStack stack, Component name, List<Component> lore, boolean keepLore) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        if (name != null) {
            meta.displayName(plain(name));
        }
        List<Component> lines = new ArrayList<>();
        if (keepLore && meta.lore() != null) {
            lines.addAll(meta.lore());
        }
        for (Component line : lore) {
            lines.add(plain(line));
        }
        meta.lore(lines);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        meta.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }

    private static Component plain(Component c) {
        return c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public boolean isIcon(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.hasItemMeta()) {
            return false;
        }
        return stack.getItemMeta().getPersistentDataContainer().has(marker, PersistentDataType.BYTE);
    }

    /** Removes stray icons from the player's inventory and cursor; returns how many stacks were removed. */
    public int purge(Player player) {
        int removed = 0;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isIcon(contents[i])) {
                player.getInventory().setItem(i, null);
                removed++;
            }
        }
        if (isIcon(player.getItemOnCursor())) {
            player.setItemOnCursor(null);
            removed++;
        }
        return removed;
    }
}
