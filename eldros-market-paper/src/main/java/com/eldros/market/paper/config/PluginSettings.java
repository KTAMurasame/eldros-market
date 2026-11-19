package com.eldros.market.paper.config;

import com.eldros.market.core.domain.Money;
import com.eldros.market.core.domain.MoneyScale;
import com.eldros.market.core.service.AmountParser;
import com.eldros.market.core.service.LimitSettings;
import com.eldros.market.core.service.PriceRules;
import org.bukkit.GameMode;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Typed, validated view of config.yml. A bad value fails loudly at startup naming the key, so a typo never turns
 * into silently wrong prices or limits.
 */
public record PluginSettings(String defaultLanguage, boolean useClientLocale, MoneyScale scale, LimitSettings limits,
                             int defaultMaxActiveOrders, boolean expiryEnabled, int expiryDays, Set<String> blacklist,
                             boolean rejectFilledContainers, Set<String> blockedPdcNamespaces, int itemOpsPerSecond,
                             String notifySound, int joinSummaryMaxLines, Fees fees, PriceRules prices,
                             Set<String> disabledWorlds, Set<GameMode> disabledGameModes,
                             int placeholderRefreshSeconds, String placeholderNone, boolean autoCollectMoney,
                             boolean anvilInput) {

    /**
     * Sales tax (basis points, with lower rates by permission {@code eldrosmarket.tax.<tier>}) and the buy-order
     * creation fee (flat + basis points of the order value, capped; 0 cap = no cap). All zero = off (the default).
     */
    public record Fees(int salesTaxBps, java.util.Map<String, Integer> taxTiers, Money buyFeeFlat, int buyFeeBps, Money buyFeeMax) {
        public boolean buyFeeEnabled() {
            return buyFeeFlat.minor() > 0 || buyFeeBps > 0;
        }
    }

    public static final class ConfigException extends RuntimeException {
        public ConfigException(String key, String problem) {
            super("config.yml: '" + key + "' " + problem);
        }
    }

    public static PluginSettings load(FileConfiguration c) {
        int decimals = intIn(c, "economy.decimal-places", 0, MoneyScale.MAX_DECIMAL_PLACES);
        MoneyScale scale = MoneyScale.of(decimals);
        Money min = money(c, "limits.min-unit-price", scale);
        Money max = money(c, "limits.max-unit-price", scale);
        int maxQty = intIn(c, "limits.max-quantity-per-order", 1, Integer.MAX_VALUE);
        LimitSettings limits;
        try {
            limits = new LimitSettings(min, max, maxQty);
        } catch (IllegalArgumentException e) {
            throw new ConfigException("limits", e.getMessage());
        }
        return new PluginSettings(
                c.getString("language.default", "en_US"),
                c.getBoolean("language.use-client-locale", true),
                scale,
                limits,
                intIn(c, "limits.default-max-active-orders", 1, 10_000),
                c.getBoolean("orders.expiry.enabled", false),
                intIn(c, "orders.expiry.days", 1, 3_650),
                lowerSet(c.getStringList("items.blacklist")),
                c.getBoolean("items.reject-filled-containers", true),
                lowerSet(c.getStringList("items.blocked-pdc-namespaces")),
                intIn(c, "performance.item-ops-per-second", 1, 1_000),
                c.getString("notifications.sound", "").trim(),
                intIn(c, "notifications.join-summary-max-lines", 1, 50),
                fees(c, scale),
                prices(c, scale, limits),
                lowerSet(c.getStringList("restrictions.disabled-worlds")),
                gameModes(c, "restrictions.disabled-gamemodes"),
                intIn(c, "placeholders.refresh-seconds", 1, 3_600),
                c.getString("placeholders.none", "-"),
                c.getBoolean("mailbox.auto-collect-money", false),
                inputMethod(c));
    }

    private static boolean inputMethod(FileConfiguration c) {
        String m = c.getString("input.method", "anvil").trim().toLowerCase(Locale.ROOT);
        return switch (m) {
            case "anvil" -> true;
            case "chat" -> false;
            default -> throw new ConfigException("input.method", "must be anvil or chat (got '" + m + "')");
        };
    }

    private static PriceRules prices(FileConfiguration c, MoneyScale scale, LimitSettings base) {
        java.util.Map<String, PriceRules.Range> rules = new java.util.HashMap<>();
        var section = c.getConfigurationSection("limits.per-item");
        if (section != null) {
            for (String item : section.getKeys(false)) {
                String key = "limits.per-item." + item;
                if (!c.isConfigurationSection(key)) {
                    throw new ConfigException(key, "must have min and/or max, e.g. min: \"10\"");
                }
                java.util.Optional<Money> min = c.isSet(key + ".min") ? java.util.Optional.of(money(c, key + ".min", scale)) : java.util.Optional.empty();
                java.util.Optional<Money> max = c.isSet(key + ".max") ? java.util.Optional.of(money(c, key + ".max", scale)) : java.util.Optional.empty();
                rules.put(item, new PriceRules.Range(min, max));
            }
        }
        try {
            return new PriceRules(base, rules);
        } catch (IllegalArgumentException e) {
            throw new ConfigException("limits.per-item", e.getMessage());
        }
    }

    private static Set<GameMode> gameModes(FileConfiguration c, String key) {
        Set<GameMode> out = java.util.EnumSet.noneOf(GameMode.class);
        for (String raw : c.getStringList(key)) {
            try {
                out.add(GameMode.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new ConfigException(key, "has an unknown game mode '" + raw + "' (survival, creative, adventure, spectator)");
            }
        }
        return java.util.Collections.unmodifiableSet(out);
    }

    private static Fees fees(FileConfiguration c, MoneyScale scale) {
        int tax = percent(c, "economy.sales-tax.percent");
        java.util.Map<String, Integer> tiers = new java.util.TreeMap<>();
        var section = c.getConfigurationSection("economy.sales-tax.tiers");
        if (section != null) {
            for (String name : section.getKeys(false)) {
                tiers.put(name.toLowerCase(Locale.ROOT), percent(c, "economy.sales-tax.tiers." + name));
            }
        }
        return new Fees(tax, java.util.Map.copyOf(tiers), money(c, "economy.buy-order-fee.flat", scale),
                percent(c, "economy.buy-order-fee.percent"), money(c, "economy.buy-order-fee.max", scale));
    }

    private static int percent(FileConfiguration c, String key) {
        String raw = c.getString(key, "0");
        try {
            return com.eldros.market.core.service.Fees.basisPoints(raw);
        } catch (IllegalArgumentException e) {
            throw new ConfigException(key, "must be a percentage from 0 to 100 with at most two decimals (got '" + raw + "')");
        }
    }

    private static Money money(FileConfiguration c, String key, MoneyScale scale) {
        String raw = c.getString(key);
        AmountParser.Result<Money> parsed = AmountParser.parseMoney(raw, scale);
        if (!parsed.isOk()) {
            throw new ConfigException(key, "must be a plain number with at most " + scale.decimalPlaces()
                    + " decimal places (got '" + raw + "': " + parsed.error() + ")");
        }
        return parsed.value();
    }

    private static int intIn(FileConfiguration c, String key, int min, int max) {
        if (!c.isInt(key)) {
            throw new ConfigException(key, "must be a whole number");
        }
        int v = c.getInt(key);
        if (v < min || v > max) {
            throw new ConfigException(key, "must be between " + min + " and " + max + " (got " + v + ")");
        }
        return v;
    }

    private static Set<String> lowerSet(List<String> values) {
        Set<String> out = new HashSet<>();
        for (String v : values) {
            out.add(v.toLowerCase(Locale.ROOT).trim());
        }
        return Set.copyOf(out);
    }
}
