package com.eldros.market.paper;

import com.eldros.market.paper.action.MarketActions;
import com.eldros.market.paper.command.AdminCommand;
import com.eldros.market.paper.command.MarketCommand;
import com.eldros.market.paper.config.PluginSettings;
import com.eldros.market.paper.gui.ChatPrompt;
import com.eldros.market.paper.gui.Icons;
import com.eldros.market.paper.gui.MenuConfigs;
import com.eldros.market.paper.gui.MenuListener;
import com.eldros.market.paper.gui.Menus;
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
        menuConfigs = new MenuConfigs(this);
        menuConfigs.load();
        MarketActions actions = new MarketActions(runtime);
        Icons icons = new Icons(this);
        ChatPrompt prompt = new ChatPrompt(this);
        Menus menus = new Menus(actions, menuConfigs, icons, prompt);
        register("market", new MarketCommand(actions, menus));
        register("marketadmin", new AdminCommand(runtime));
        getServer().getPluginManager().registerEvents(new PlayerListener(runtime), this);
        getServer().getPluginManager().registerEvents(new MenuListener(prompt, icons, getLogger()), this);
        registerDebugCommand();
    }

    /** Test builds only: the class is absent from release jars, so this does nothing there. */
    private void registerDebugCommand() {
        try {
            org.bukkit.command.Command debug = (org.bukkit.command.Command) Class.forName("com.eldros.market.paper.debug.DebugCommand")
                    .getConstructor(MarketRuntime.class).newInstance(runtime);
            getServer().getCommandMap().register("eldrosmarket", debug);
            getLogger().warning("TEST BUILD: /eldrosdebug is available. Do not run this jar on a live server.");
        } catch (ClassNotFoundException e) {
            // release build
        } catch (ReflectiveOperationException e) {
            getLogger().warning("Could not register the debug command: " + e);
        }
    }

    private MenuConfigs menuConfigs;

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
        menuConfigs.load();
        runtime.applySettings(next);
    }
}
