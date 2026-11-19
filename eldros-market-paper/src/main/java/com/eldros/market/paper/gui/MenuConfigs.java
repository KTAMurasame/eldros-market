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
            "main", "browse", "catalog", "book", "create", "confirm", "orders", "history", "mailbox", "language",
            "instant", "sell-all", "edit");

    private final JavaPlugin plugin;
    private volatile Map<String, MenuLayout> layouts = Map.of();
    private volatile Categories categories = Categories.NONE;

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
        categories = loadCategories();
    }

    /** categories.yml; a broken file is reported and the bundled categories are used instead. */
    private Categories loadCategories() {
        String path = "categories.yml";
        File file = new File(plugin.getDataFolder(), path);
        if (!file.exists()) {
            plugin.saveResource(path, false);
        }
        try {
            YamlConfiguration user = new YamlConfiguration();
            user.load(file);
            return readCategories(user);
        } catch (Exception e) {
            plugin.getLogger().warning(path + " could not be used (" + e.getMessage() + "); using the bundled categories");
            return readCategories(bundled(path));
        }
    }

    static Categories readCategories(YamlConfiguration c) {
        var section = c.getConfigurationSection("categories");
        List<Categories.Category> out = new java.util.ArrayList<>();
        if (section != null) {
            for (String id : section.getKeys(false)) {
                var cat = section.getConfigurationSection(id);
                if (cat == null) {
                    throw new IllegalArgumentException("category '" + id + "' needs icon, name and items");
                }
                Map<String, String> names = new HashMap<>();
                var nameSection = cat.getConfigurationSection("name");
                if (nameSection != null) {
                    for (String lang : nameSection.getKeys(false)) {
                        names.put(lang, nameSection.getString(lang, id));
                    }
                }
                out.add(new Categories.Category(id, cat.getString("icon", "CHEST"), Map.copyOf(names), cat.getStringList("items")));
            }
        }
        return new Categories(out);
    }

    public Categories categories() {
        return categories;
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
