package com.eldros.market.core.port;

import com.eldros.market.api.model.TemplateId;
import com.eldros.market.core.domain.PlayerId;

/**
 * A player's inventory, in units of an exact item template. Main thread only. Changes are durable only once
 * {@link PlayerMarkerStore#save} runs (Player#saveData), together with the markers (03 §7.1, F0 B11).
 */
public interface InventoryGateway {

    boolean isOnline(PlayerId player);

    /** Removes exactly {@code quantity} items or nothing; returns whether it removed them. */
    boolean remove(PlayerId player, TemplateId template, long quantity);

    /** How many of {@code quantity} would fit right now (main storage only). */
    long fit(PlayerId player, TemplateId template, long quantity);

    /** Adds items; the caller has checked {@link #fit}. */
    void add(PlayerId player, TemplateId template, long quantity);
}
