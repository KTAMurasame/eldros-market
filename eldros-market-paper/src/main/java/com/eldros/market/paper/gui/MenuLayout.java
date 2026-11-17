package com.eldros.market.paper.gui;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The layout of one screen from {@code menus/<screen>.yml}: rows, filler, content slots and buttons (slot +
 * material). Texts live in the language files. Every value is checked; a bad value is reported with its key and
 * replaced by the bundled default (F-GUI-3), so a typo never breaks a menu.
 */
public final class MenuLayout {

    public record Button(int slot, Material material) {
    }

    private final String screen;
    private final int rows;
    private final Material filler;
    private final List<Integer> contentSlots;
    private final Map<String, Button> buttons;

    private final Map<String, Material> icons;

    private MenuLayout(String screen, int rows, Material filler, List<Integer> contentSlots, Map<String, Button> buttons,
                       Map<String, Material> icons) {
        this.screen = screen;
        this.rows = rows;
        this.filler = filler;
        this.contentSlots = List.copyOf(contentSlots);
        this.buttons = Map.copyOf(buttons);
        this.icons = Map.copyOf(icons);
    }

    private Map<String, List<Integer>> groups = Map.of();

    /** Named slot list from {@code slot-groups:} (e.g. the ask and bid columns of the order book). */
    public List<Integer> group(String id) {
        return groups.getOrDefault(id, List.of());
    }

    /** Material of a list-entry icon from the {@code icons:} section (e.g. money in the mailbox). */
    public Material icon(String id, Material fallback) {
        return icons.getOrDefault(id, fallback);
    }

    public String screen() {
        return screen;
    }

    public int rows() {
        return rows;
    }

    public int size() {
        return rows * 9;
    }

    /** Null when the screen has no filler. */
    public Material filler() {
        return filler;
    }

    public List<Integer> contentSlots() {
        return contentSlots;
    }

    /** Empty when the button is disabled ({@code slot: -1}). */
    public Optional<Button> button(String id) {
        return Optional.ofNullable(buttons.get(id));
    }

    /**
     * Reads {@code user} with {@code defaults} (the bundled file) as fallback. {@code problems} receives one line
     * per rejected value, already naming the file and key.
     */
    static MenuLayout read(String screen, YamlConfiguration user, YamlConfiguration defaults, Consumer<String> problems) {
        String file = "menus/" + screen + ".yml";
        int rows = user.getInt("rows", defaults.getInt("rows", 6));
        if (rows < 1 || rows > 6) {
            problems.accept(file + ": rows must be 1-6, was " + rows + "; using " + defaults.getInt("rows"));
            rows = Math.max(1, Math.min(6, defaults.getInt("rows", 6)));
        }
        int size = rows * 9;

        Material filler = null;
        String fillerName = user.getString("filler", defaults.getString("filler", "AIR"));
        if (!"AIR".equalsIgnoreCase(fillerName) && !"none".equalsIgnoreCase(fillerName)) {
            filler = material(fillerName);
            if (filler == null) {
                problems.accept(file + ": filler '" + fillerName + "' is not an item; using " + defaults.getString("filler"));
                filler = material(defaults.getString("filler", "AIR"));
            }
        }

        List<Integer> content = slots(user.get("content-slots"), size);
        if (content == null) {
            if (user.contains("content-slots")) {
                problems.accept(file + ": content-slots is not a list of slots (0-" + (size - 1) + " or \"a-b\"); using the default");
            }
            content = slots(defaults.get("content-slots"), size);
            if (content == null) {
                content = List.of();
            }
        }

        Map<String, Button> buttons = new LinkedHashMap<>();
        ConfigurationSection defaultButtons = defaults.getConfigurationSection("buttons");
        if (defaultButtons != null) {
            for (String id : defaultButtons.getKeys(false)) {
                String key = "buttons." + id;
                int slot = user.getInt(key + ".slot", defaults.getInt(key + ".slot"));
                if (slot == -1) {
                    continue; // disabled on purpose
                }
                if (slot < 0 || slot >= size) {
                    problems.accept(file + ": " + key + ".slot " + slot + " is outside 0-" + (size - 1) + "; using the default");
                    slot = defaults.getInt(key + ".slot");
                    if (slot < 0 || slot >= size) {
                        continue;
                    }
                }
                String matName = user.getString(key + ".material", defaults.getString(key + ".material", "STONE"));
                Material material = material(matName);
                if (material == null) {
                    problems.accept(file + ": " + key + ".material '" + matName + "' is not an item; using the default");
                    material = Optional.ofNullable(material(defaults.getString(key + ".material"))).orElse(Material.STONE);
                }
                buttons.put(id, new Button(slot, material));
            }
        }
        Map<String, Material> icons = new LinkedHashMap<>();
        ConfigurationSection defaultIcons = defaults.getConfigurationSection("icons");
        if (defaultIcons != null) {
            for (String id : defaultIcons.getKeys(false)) {
                String key = "icons." + id;
                String name = user.getString(key, defaults.getString(key));
                Material material = material(name);
                if (material == null) {
                    problems.accept(file + ": " + key + " '" + name + "' is not an item; using the default");
                    material = material(defaults.getString(key));
                }
                if (material != null) {
                    icons.put(id, material);
                }
            }
        }
        Map<String, List<Integer>> groups = new LinkedHashMap<>();
        ConfigurationSection defaultGroups = defaults.getConfigurationSection("slot-groups");
        if (defaultGroups != null) {
            for (String id : defaultGroups.getKeys(false)) {
                String key = "slot-groups." + id;
                List<Integer> slots = slots(user.get(key), size);
                if (slots == null) {
                    if (user.contains(key)) {
                        problems.accept(file + ": " + key + " is not a list of slots (0-" + (size - 1) + "); using the default");
                    }
                    slots = slots(defaults.get(key), size);
                }
                groups.put(id, slots == null ? List.of() : slots);
            }
        }
        MenuLayout layout = new MenuLayout(screen, rows, filler, content, buttons, icons);
        layout.groups = Map.copyOf(groups);
        return layout;
    }

    static Material material(String name) {
        if (name == null) {
            return null;
        }
        Material m = Material.matchMaterial(name.trim().toUpperCase(Locale.ROOT));
        return m != null && m.isItem() && !m.isAir() ? m : null;
    }

    /** A list of ints and/or "a-b" ranges; null if anything is invalid or out of range. */
    static List<Integer> slots(Object raw, int size) {
        if (!(raw instanceof List<?> list)) {
            return null;
        }
        List<Integer> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Number n) {
                out.add(n.intValue());
            } else if (o instanceof String s && s.matches("\\s*\\d+\\s*-\\s*\\d+\\s*")) {
                String[] p = s.split("-");
                int a = Integer.parseInt(p[0].trim());
                int b = Integer.parseInt(p[1].trim());
                for (int i = Math.min(a, b); i <= Math.max(a, b); i++) {
                    out.add(i);
                }
            } else {
                return null;
            }
        }
        for (int slot : out) {
            if (slot < 0 || slot >= size) {
                return null;
            }
        }
        return out.stream().distinct().toList();
    }
}
