package com.eldros.market.paper.gui;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * "Type a value in chat" input for menus. The next chat line of the player is taken (and not broadcast); "cancel"
 * or "iptal", leaving, or the timeout abort it. Answers are delivered on the main thread.
 */
public final class ChatPrompt {

    private static final long TIMEOUT_TICKS = 20L * 60;

    private record Pending(long token, Consumer<String> onAnswer, Runnable onCancel) {
    }

    private final JavaPlugin plugin;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private long nextToken;

    public ChatPrompt(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Main thread. Replaces an older prompt of the same player (whose cancel runs). */
    public void ask(Player player, Consumer<String> onAnswer, Runnable onCancel) {
        long token = ++nextToken;
        Pending old = pending.put(player.getUniqueId(), new Pending(token, onAnswer, onCancel));
        if (old != null) {
            old.onCancel().run();
        }
        UUID id = player.getUniqueId();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Pending p = pending.get(id);
            if (p != null && p.token() == token && pending.remove(id, p)) {
                p.onCancel().run();
            }
        }, TIMEOUT_TICKS);
    }

    public boolean isWaiting(UUID player) {
        return pending.containsKey(player);
    }

    /** Called from the (async) chat event; true if the line was ours. */
    boolean offer(UUID player, String line) {
        Pending p = pending.remove(player);
        if (p == null) {
            return false;
        }
        String text = line.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (lower.equals("cancel") || lower.equals("iptal") || lower.equals("vazgeç") || lower.equals("vazgec")) {
                p.onCancel().run();
            } else {
                p.onAnswer().accept(text);
            }
        });
        return true;
    }

    /** Player left: drop silently. */
    void forget(UUID player) {
        pending.remove(player);
    }
}
