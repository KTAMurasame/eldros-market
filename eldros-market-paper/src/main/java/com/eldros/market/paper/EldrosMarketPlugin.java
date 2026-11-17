package com.eldros.market.paper;

import com.eldros.market.paper.command.AdminCommand;
import com.eldros.market.paper.command.MarketCommand;
import com.eldros.market.paper.config.PluginSettings;
import com.eldros.market.paper.i18n.Messages;
import com.eldros.market.paper.listener.PlayerListener;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/** Plugin entry point. Any startup failure keeps the market closed instead of running on unknown state. */
public final class EldrosMarketPlugin extends JavaPlugin {

    private MarketRuntime runtime;
    private Messages messages;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        PluginSettings settings;
        try {
            settings = PluginSettings.load(getConfig());
        } catch (PluginSettings.ConfigException e) {
            getLogger().severe(e.getMessage() + " — fix config.yml and restart. The market is disabled.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        messages = new Messages(this);
        messages.load(settings.defaultLanguage(), settings.useClientLocale());
        runtime = new MarketRuntime(this, settings, messages);
        try {
            runtime.start();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Eldros Market could not start and stays disabled: " + e.getMessage(), e);
            runtime.stop();
            runtime = null;
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        register("market", new MarketCommand(runtime));
        register("marketadmin", new AdminCommand(runtime));
        getServer().getPluginManager().registerEvents(new PlayerListener(runtime), this);
    }

    private void register(String name, TabExecutor executor) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            throw new IllegalStateException("command '" + name + "' missing from plugin.yml");
        }
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    @Override
    public void onDisable() {
        if (runtime != null) {
            runtime.stop();
            runtime = null;
        }
    }

    /**
     * Re-reads config.yml and the language files. The money scale cannot change while the server runs: amounts are
     * stored in minor units, so a different scale would silently change every stored price.
     */
    public void reloadSettings() {
        reloadConfig();
        PluginSettings next = PluginSettings.load(getConfig());
        if (!next.scale().equals(runtime.settings().scale())) {
            throw new IllegalStateException("economy.decimal-places cannot change at runtime (stored amounts depend on it)");
        }
        messages.load(next.defaultLanguage(), next.useClientLocale());
        runtime.applySettings(next);
    }
}
