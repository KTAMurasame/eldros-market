package com.eldros.market.paper.listener;

import com.eldros.market.paper.MarketRuntime;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Join: language preference, marker reconciliation (docs/03 §7.3), mailbox reminder. Quit: drop per-player state. */
public final class PlayerListener implements Listener {

    private final MarketRuntime rt;

    public PlayerListener(MarketRuntime runtime) {
        this.rt = runtime;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        rt.onJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        rt.messages().setPreference(event.getPlayer().getUniqueId(), null);
    }
}
