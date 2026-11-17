package com.eldros.market.paper.i18n;

import com.eldros.market.core.domain.Money;
import com.eldros.market.core.domain.MoneyScale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Language files (docs/03 §9). Lookup order: player's choice → client locale (optional) → default → en_US → the
 * key itself. Player-provided text only ever enters through {@link Placeholder#unparsed} or components, never as
 * MiniMessage, so item names cannot inject tags. New keys from an update are merged into existing files without
 * touching the server owner's translations.
 */
public final class Messages {

    public static final List<String> BUNDLED = List.of("en_US", "tr_TR");
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final JavaPlugin plugin;
    private final Logger logger;
    private final Map<String, YamlConfiguration> languages = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Map<UUID, String> preferences = new ConcurrentHashMap<>();
    private final Set<String> warnedMissing = ConcurrentHashMap.newKeySet();
    private String defaultLanguage = "en_US";
    private boolean useClientLocale = true;

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public void load(String defaultLanguage, boolean useClientLocale) {
        this.useClientLocale = useClientLocale;
        languages.clear();
        File dir = new File(plugin.getDataFolder(), "lang");
        for (String bundled : BUNDLED) {
            File file = new File(dir, bundled + ".yml");
            if (!file.exists()) {
                plugin.saveResource("lang/" + bundled + ".yml", false);
            }
            mergeMissingKeys(file, bundled);
        }
        File[] files = dir.listFiles((d, name) -> name.endsWith(".yml"));
        if (files != null) {
            for (File f : files) {
                languages.put(f.getName().substring(0, f.getName().length() - 4), YamlConfiguration.loadConfiguration(f));
            }
        }
        if (!languages.containsKey(defaultLanguage)) {
            logger.warning("language.default '" + defaultLanguage + "' has no file in lang/; using en_US");
            defaultLanguage = "en_US";
        }
        this.defaultLanguage = canonical(defaultLanguage);
    }

    private void mergeMissingKeys(File file, String bundled) {
        try (InputStream in = plugin.getResource("lang/" + bundled + ".yml")) {
            if (in == null) {
                return;
            }
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            YamlConfiguration current = YamlConfiguration.loadConfiguration(file);
            boolean changed = false;
            for (String key : defaults.getKeys(true)) {
                if (!defaults.isConfigurationSection(key) && !current.contains(key)) {
                    current.set(key, defaults.get(key));
                    changed = true;
                }
            }
            if (changed) {
                current.save(file);
                logger.info("Added new message keys to lang/" + bundled + ".yml");
            }
        } catch (IOException e) {
            logger.warning("Could not update lang/" + bundled + ".yml: " + e.getMessage());
        }
    }

    public Set<String> available() {
        return Collections.unmodifiableSet(languages.keySet());
    }

    /** The canonical file name for a language code typed in any case, or null. */
    public String canonical(String code) {
        for (String name : languages.keySet()) {
            if (name.equalsIgnoreCase(code)) {
                return name;
            }
        }
        return null;
    }

    public void setPreference(UUID player, String language) {
        if (language == null) {
            preferences.remove(player);
        } else {
            preferences.put(player, language);
        }
    }

    public String languageOf(CommandSender sender) {
        if (sender instanceof Player p) {
            String pref = preferences.get(p.getUniqueId());
            if (pref != null && languages.containsKey(pref)) {
                return pref;
            }
            if (useClientLocale) {
                Locale l = p.locale();
                String client = canonical(l.getLanguage() + "_" + l.getCountry());
                if (client == null) {
                    client = languages.keySet().stream()
                            .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(l.getLanguage().toLowerCase(Locale.ROOT) + "_"))
                            .findFirst().orElse(null);
                }
                if (client != null) {
                    return client;
                }
            }
        }
        return defaultLanguage;
    }

    public String raw(String language, String key) {
        for (String lang : new String[] {language, defaultLanguage, "en_US"}) {
            YamlConfiguration y = lang == null ? null : languages.get(lang);
            if (y != null && y.isString(key)) {
                return y.getString(key);
            }
        }
        if (warnedMissing.add(key)) {
            logger.warning("Missing message key '" + key + "' in all languages");
        }
        return key;
    }

    /** True if the key exists in the language, the default language or English. */
    public boolean has(String language, String key) {
        for (String lang : new String[] {language, defaultLanguage, "en_US"}) {
            YamlConfiguration y = lang == null ? null : languages.get(lang);
            if (y != null && y.isString(key)) {
                return true;
            }
        }
        return false;
    }

    public List<String> rawList(String language, String key) {
        for (String lang : new String[] {language, defaultLanguage, "en_US"}) {
            YamlConfiguration y = lang == null ? null : languages.get(lang);
            if (y != null && y.isList(key)) {
                return y.getStringList(key);
            }
        }
        return List.of(key);
    }

    public Component render(String language, String key, TagResolver... resolvers) {
        return MM.deserialize(raw(language, key), TagResolver.resolver(resolvers));
    }

    public Component render(CommandSender sender, String key, TagResolver... resolvers) {
        return render(languageOf(sender), key, resolvers);
    }

    public void send(CommandSender sender, String key, TagResolver... resolvers) {
        String lang = languageOf(sender);
        sender.sendMessage(MM.deserialize(raw(lang, "prefix")).append(render(lang, key, resolvers)));
    }

    public void sendList(CommandSender sender, String key) {
        String lang = languageOf(sender);
        for (String line : rawList(lang, key)) {
            sender.sendMessage(MM.deserialize(line));
        }
    }

    /** {@code <name>} filled with the formatted amount, e.g. "1.234,50" in Turkish, styled by format.money. */
    public TagResolver money(String language, String name, Money money, MoneyScale scale) {
        String amount = formatAmount(language, money, scale);
        Component styled = MM.deserialize(raw(language, "format.money"), Placeholder.unparsed("amount", amount));
        return Placeholder.component(name, styled);
    }

    public String formatAmount(String language, Money money, MoneyScale scale) {
        BigDecimal value = BigDecimal.valueOf(money.minor(), scale.decimalPlaces());
        String plain = value.toPlainString();
        String integer = plain;
        String fraction = "";
        int dot = plain.indexOf('.');
        if (dot >= 0) {
            integer = plain.substring(0, dot);
            fraction = plain.substring(dot + 1);
        }
        String grouping = raw(language, "format.grouping-separator");
        StringBuilder grouped = new StringBuilder();
        for (int i = 0; i < integer.length(); i++) {
            if (i > 0 && (integer.length() - i) % 3 == 0) {
                grouped.append(grouping);
            }
            grouped.append(integer.charAt(i));
        }
        return fraction.isEmpty() ? grouped.toString() : grouped + raw(language, "format.decimal-separator") + fraction;
    }

    public static TagResolver text(String name, Object value) {
        return Placeholder.unparsed(name, String.valueOf(value));
    }

    public static TagResolver component(String name, Component value) {
        return Placeholder.component(name, value);
    }
}
