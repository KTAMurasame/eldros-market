package com.eldros.market.core.port;

import com.eldros.market.core.domain.PlayerId;

import java.util.Map;

/**
 * Operation markers in the player's own data (PDC in the paper module). Written in the same tick as the inventory
 * change and made durable together with it by {@link #save}; after a crash, a marker's presence proves the change
 * survived (03 §7.1, verified in F0 B11). Main thread only.
 */
public interface PlayerMarkerStore {

    void put(PlayerId player, String opId, long quantity);

    /** All markers of a player: op id to quantity. */
    Map<String, Long> all(PlayerId player);

    void remove(PlayerId player, String opId);

    /** Player#saveData: makes inventory and markers durable together. Costly (F0 B12), so callers batch it (D9). */
    void save(PlayerId player);
}
