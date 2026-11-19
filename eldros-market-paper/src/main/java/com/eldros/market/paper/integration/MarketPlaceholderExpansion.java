package com.eldros.market.paper.integration;

import com.eldros.market.paper.MarketRuntime;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * {@code %eldrosmarket_...%} for PlaceholderAPI (see {@link PlaceholderValues} for the list). Loaded only when
 * PlaceholderAPI is installed, so the plugin runs without it.
 */
public final class MarketPlaceholderExpansion extends PlaceholderExpansion {

    private final MarketRuntime rt;
    private final PlaceholderSnapshots snapshots;

    public MarketPlaceholderExpansion(MarketRuntime rt, PlaceholderSnapshots snapshots) {
        this.rt = rt;
        this.snapshots = snapshots;
    }

    @Override
    public String getIdentifier() {
        return "eldrosmarket";
    }

    @Override
    public String getAuthor() {
        return "Eldros";
    }

    @Override
    public String getVersion() {
        return rt.plugin().getPluginMeta().getVersion();
    }

    /** Stay registered across {@code /papi reload}: this expansion lives in the plugin, not in PlaceholderAPI's folder. */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        var messages = rt.messages();
        Player online = player == null ? null : player.getPlayer();
        String lang = online != null ? messages.languageOf(online) : messages.languageOf(rt.plugin().getServer().getConsoleSender());
        UUID id = player == null ? null : player.getUniqueId();
        var settings = rt.settings();
        return PlaceholderValues.resolve(snapshots.current(), id, params,
                m -> messages.formatAmount(lang, m, settings.scale()), settings.scale(), settings.placeholderNone());
    }
}
