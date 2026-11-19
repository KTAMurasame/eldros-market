package com.eldros.market.paper.integration;

import com.eldros.market.api.model.TemplateId;
import com.eldros.market.core.domain.PlayerId;
import com.eldros.market.paper.MarketRuntime;
import com.eldros.market.paper.action.MarketActions;
import com.eldros.market.paper.integration.PlaceholderValues.ItemQuote;
import com.eldros.market.paper.integration.PlaceholderValues.PlayerStats;
import com.eldros.market.paper.integration.PlaceholderValues.Snapshot;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Keeps the placeholder snapshot fresh: every {@code placeholders.refresh-seconds} the best prices of plain items and
 * the online players' counts are read on the engine thread and swapped in at once. Placeholder requests only read
 * the last snapshot, so they never touch the database or block a thread.
 */
public final class PlaceholderSnapshots {

    private final MarketRuntime rt;
    private final MarketActions actions;
    private volatile Snapshot current = Snapshot.EMPTY;
    private volatile boolean running;
    private BukkitTask task;

    public PlaceholderSnapshots(MarketRuntime rt, MarketActions actions) {
        this.rt = rt;
        this.actions = actions;
    }

    public Snapshot current() {
        return current;
    }

    /** Main thread. Starts (or restarts, after a reload changed the interval) the refresh timer. */
    public void start() {
        stop();
        long ticks = rt.settings().placeholderRefreshSeconds() * 20L;
        task = Bukkit.getScheduler().runTaskTimer(rt.plugin(), this::refresh, 20L, ticks);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** Main thread: gathers what only the main thread may read, then queries on the engine thread. */
    private void refresh() {
        if (running || !rt.isLoaded() || rt.safeMode().isActive()) {
            return;
        }
        Map<TemplateId, String> plain = new HashMap<>();
        for (TemplateId id : rt.templates().all()) {
            ItemStack stack = rt.templates().stack(id, 1);
            if (stack.isSimilar(new ItemStack(stack.getType()))) {
                plain.put(id, stack.getType().getKey().toString());
            }
        }
        Map<UUID, Integer> limits = new HashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            limits.put(p.getUniqueId(), actions.orderLimit(p));
        }
        boolean frozen = rt.isFrozen();
        running = true;
        rt.onEngine(() -> {
            Map<String, ItemQuote> items = new HashMap<>();
            for (var s : rt.engine().summaries()) {
                String key = plain.get(s.template());
                if (key != null) {
                    items.put(key, new ItemQuote(s.bestBid(), s.bestAsk(), s.bidQuantity(), s.askQuantity()));
                }
            }
            Map<UUID, PlayerStats> players = rt.tx().inTransaction(c -> {
                Map<UUID, PlayerStats> out = new HashMap<>();
                for (var e : limits.entrySet()) {
                    PlayerId id = new PlayerId(e.getKey());
                    out.put(e.getKey(), new PlayerStats(rt.journal().availableFor(c, id).size(),
                            rt.ledger().countActiveOrders(c, id), e.getValue()));
                }
                return out;
            });
            return new Snapshot(Map.copyOf(items), Map.copyOf(players), frozen);
        }).whenComplete((snap, error) -> {
            running = false;
            if (error != null) {
                rt.plugin().getLogger().log(Level.WARNING, "Could not refresh placeholder values", error);
            } else {
                current = snap;
            }
        });
    }
}
