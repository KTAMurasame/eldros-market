package com.eldros.market.paper.item;

import com.eldros.market.paper.config.PluginSettings;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Container;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Locale;
import java.util.Optional;

/**
 * Which items may be traded (docs/02 F-ORD-11, docs/03 §8.4). Returns the message key of the refusal, if any.
 * Bundles are checked without the bundle API (experimental in some supported versions, docs/03 §10.2): a bundle
 * that differs from a plain new bundle may hold items and is refused.
 */
public final class ItemFilter {

    private final PluginSettings settings;

    public ItemFilter(PluginSettings settings) {
        this.settings = settings;
    }

    public Optional<String> refusal(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.getType().isItem()) {
            return Optional.of("item.hold-item");
        }
        if (settings.blacklist().contains(item.getType().getKey().toString().toLowerCase(Locale.ROOT))) {
            return Optional.of("item.blocked");
        }
        if (settings.rejectFilledContainers() && mayHoldItems(item)) {
            return Optional.of("item.filled-container");
        }
        if (!settings.blockedPdcNamespaces().isEmpty() && item.hasItemMeta()) {
            for (NamespacedKey key : item.getItemMeta().getPersistentDataContainer().getKeys()) {
                if (settings.blockedPdcNamespaces().contains(key.getNamespace().toLowerCase(Locale.ROOT))) {
                    return Optional.of("item.blocked");
                }
            }
        }
        return Optional.empty();
    }

    private static boolean mayHoldItems(ItemStack item) {
        if (item.getType().getKey().getKey().endsWith("bundle")) {
            return !item.isSimilar(new ItemStack(item.getType()));
        }
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof BlockStateMeta bsm && bsm.hasBlockState() && bsm.getBlockState() instanceof Container container) {
            return !container.getInventory().isEmpty();
        }
        return false;
    }
}
