package com.eldros.market.paper.gui;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Loads every screen layout; missing files are written from the jar, bad values fall back per key. */
public final class MenuConfigs {

    public static final List<String> SCREENS = List.of(
            "main", "browse", "catalog", "book", "create", "confirm", "orders", "history", "mailbox", "language");

    private final JavaPlugin plugin;
    private volatile Map<String, MenuLayout> layouts = Map.of();

    public MenuConfigs(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        Map<String, MenuLayout> next = new HashMap<>();
        for (String screen : SCREENS) {
            String path = "menus/" + screen + ".yml";
            File file = new File(plugin.getDataFolder(), path);
            if (!file.exists()) {
                plugin.saveResource(path, false);
            }
            YamlConfiguration defaults = bundled(path);
            YamlConfiguration user;
            try {
                user = new YamlConfiguration();
                user.load(file);
            } catch (Exception e) {
                plugin.getLogger().warning(path + " could not be read (" + e.getMessage() + "); using the bundled layout");
                user = defaults;
            }
            next.put(screen, MenuLayout.read(screen, user, defaults, p -> plugin.getLogger().warning(p)));
        }
        layouts = Map.copyOf(next);
    }

    private YamlConfiguration bundled(String path) {
        try (InputStream in = plugin.getResource(path)) {
            if (in == null) {
                throw new IllegalStateException(path + " missing from the plugin jar");
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read bundled " + path, e);
        }
    }

    public MenuLayout layout(String screen) {
        MenuLayout l = layouts.get(screen);
        if (l == null) {
            throw new IllegalArgumentException("unknown screen " + screen);
        }
        return l;
    }
}
