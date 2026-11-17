package com.eldros.market.paper.inventory;

import com.eldros.market.core.domain.PlayerId;
import com.eldros.market.core.port.PlayerMarkerStore;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Operation markers in the player's PersistentDataContainer, saved with the inventory by {@code Player#saveData}
 * (docs/03 §7.1; verified on 1.21.4 and 26.3 in F0 B11). Main thread; online players only.
 */
public final class PdcMarkerStore implements PlayerMarkerStore {

    private static final String PREFIX = "op_";

    private final Plugin plugin;

    public PdcMarkerStore(Plugin plugin) {
        this.plugin = plugin;
    }

    private NamespacedKey key(String opId) {
        return new NamespacedKey(plugin, PREFIX + opId.toLowerCase(Locale.ROOT));
    }

    private static Player online(PlayerId id) {
        Player p = Bukkit.getPlayer(id.value());
        if (p == null) {
            throw new IllegalStateException("player " + id.value() + " is not online");
        }
        return p;
    }

    @Override
    public void put(PlayerId player, String opId, long quantity) {
        online(player).getPersistentDataContainer().set(key(opId), PersistentDataType.LONG, quantity);
    }

    @Override
    public Map<String, Long> all(PlayerId player) {
        Player p = Bukkit.getPlayer(player.value());
        Map<String, Long> out = new HashMap<>();
        if (p == null) {
            return out;
        }
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        String namespace = plugin.getName().toLowerCase(Locale.ROOT);
        for (NamespacedKey k : pdc.getKeys()) {
            if (k.getNamespace().equals(namespace) && k.getKey().startsWith(PREFIX)) {
                Long v = pdc.get(k, PersistentDataType.LONG);
                if (v != null) {
                    out.put(k.getKey().substring(PREFIX.length()), v);
                }
            }
        }
        return out;
    }

    @Override
    public void remove(PlayerId player, String opId) {
        Player p = Bukkit.getPlayer(player.value());
        if (p != null) {
            p.getPersistentDataContainer().remove(key(opId));
        }
    }

    @Override
    public void save(PlayerId player) {
        online(player).saveData();
    }
}
