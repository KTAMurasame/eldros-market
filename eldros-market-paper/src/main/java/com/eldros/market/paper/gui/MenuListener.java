package com.eldros.market.paper.gui;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Dupe protection for menus (F-SEC-8, docs/03 §11): while one of our menus is the top inventory, every click and
 * drag in the top <i>and</i> the bottom inventory is cancelled (shift-click, number keys, F swap, double-click
 * collect, Q drop, drag). Clicks on our slots are dispatched with a debounce. Stray icons are purged on close.
 */
public final class MenuListener implements Listener {

    private static final long DEBOUNCE_MS = 200;

    private final ChatPrompt prompt;
    private final Icons icons;
    private final Logger logger;
    private final Map<UUID, Long> lastClick = new ConcurrentHashMap<>();

    public MenuListener(ChatPrompt prompt, Icons icons, Logger logger) {
        this.prompt = prompt;
        this.icons = icons;
        this.logger = logger;
    }

    private static MenuHolder ours(Inventory top) {
        return top != null && top.getHolder() instanceof MenuHolder h ? h : null;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        MenuHolder holder = ours(event.getView().getTopInventory());
        if (holder == null) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastClick.put(player.getUniqueId(), now);
        if (last != null && now - last < DEBOUNCE_MS) {
            return;
        }
        holder.menu().click(event.getRawSlot(), event.getClick());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (ours(event.getView().getTopInventory()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (ours(event.getView().getTopInventory()) != null && event.getPlayer() instanceof Player player) {
            int removed = icons.purge(player);
            if (removed > 0) {
                logger.warning("Removed " + removed + " menu icon stack(s) from " + player.getName() + "'s inventory");
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (prompt.isWaiting(id) && prompt.offer(id, PlainTextComponentSerializer.plainText().serialize(event.message()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        prompt.forget(id);
        lastClick.remove(id);
    }
}
