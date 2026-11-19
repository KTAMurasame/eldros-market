package com.eldros.market.paper.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Text input in an anvil window: the rename field starts with the current value, the player edits it and clicks the
 * result to confirm; closing the window cancels. The anvil is virtual (no block) and its paper never reaches the
 * player: every click is cancelled and the slots are emptied before the window closes, so nothing can be taken or
 * returned. Answers are delivered on the main thread, one tick later, so a new menu can open safely.
 */
public final class AnvilPrompt implements Listener {

    private static final int RESULT_SLOT = 2;

    private record Session(AnvilView view, String initial, Component confirmHint, Consumer<String> onAnswer, Runnable onCancel) {
    }

    private final JavaPlugin plugin;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private volatile boolean broken;

    public AnvilPrompt(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Opens the anvil. Returns false (and the caller falls back to chat) if this server cannot build anvil windows
     * through the API; that is logged once.
     */
    public boolean ask(Player player, Component title, String initial, List<Component> hint, Component confirmHint,
                       Consumer<String> onAnswer, Runnable onCancel) {
        if (broken) {
            return false;
        }
        AnvilView view;
        try {
            view = MenuType.ANVIL.builder().title(title).checkReachable(false).build(player);
        } catch (RuntimeException | LinkageError e) {
            broken = true;
            plugin.getLogger().log(Level.WARNING, "Anvil input is not available on this server version; using chat input", e);
            return false;
        }
        ItemStack paper = new ItemStack(Material.PAPER);
        ItemMeta meta = paper.getItemMeta();
        meta.displayName(Component.text(initial));
        meta.lore(hint);
        paper.setItemMeta(meta);
        view.getTopInventory().setItem(0, paper);
        Session old = sessions.put(player.getUniqueId(), new Session(view, initial, confirmHint, onAnswer, onCancel));
        if (old != null) {
            clear(old.view());
        }
        view.open();
        return true;
    }

    private Session sessionOf(InventoryView view, UUID player) {
        Session s = sessions.get(player);
        if (s == null) {
            return null;
        }
        return view == s.view() || view.getTopInventory() == s.view().getTopInventory() ? s : null;
    }

    private static void clear(AnvilView view) {
        for (int i = 0; i <= RESULT_SLOT; i++) {
            view.getTopInventory().setItem(i, null);
        }
    }

    /** Shows the typed text on the result paper; costs nothing. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepare(PrepareAnvilEvent event) {
        Session s = sessionOf(event.getView(), event.getView().getPlayer().getUniqueId());
        if (s == null) {
            return;
        }
        String text = event.getView().getRenameText();
        ItemStack result = new ItemStack(Material.PAPER);
        ItemMeta meta = result.getItemMeta();
        meta.displayName(Component.text(text == null ? s.initial() : text));
        meta.lore(List.of(s.confirmHint()));
        result.setItemMeta(meta);
        event.setResult(result);
        event.getView().setRepairCost(0);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Session s = sessionOf(event.getView(), player.getUniqueId());
        if (s == null) {
            return;
        }
        event.setCancelled(true);
        if (event.getRawSlot() != RESULT_SLOT) {
            return;
        }
        String text = s.view().getRenameText();
        String answer = text == null ? s.initial() : text.trim();
        if (!sessions.remove(player.getUniqueId(), s)) {
            return;
        }
        clear(s.view());
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            player.closeInventory();
            s.onAnswer().accept(answer);
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (sessionOf(event.getView(), event.getWhoClicked().getUniqueId()) != null) {
            event.setCancelled(true);
        }
    }

    /** Esc, death, teleport, disconnect: the paper is removed before the server would hand it back; then cancel. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        Session s = sessionOf(event.getView(), id);
        if (s == null) {
            return;
        }
        clear(s.view());
        if (sessions.remove(id, s) && event.getPlayer().isValid()) {
            plugin.getServer().getScheduler().runTask(plugin, s.onCancel());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Session s = sessions.remove(event.getPlayer().getUniqueId());
        if (s != null) {
            clear(s.view());
        }
    }
}
