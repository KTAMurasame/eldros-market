package com.eldros.market.paper.config;

import com.eldros.market.core.domain.Money;
import com.eldros.market.core.domain.MoneyScale;
import com.eldros.market.core.service.AmountParser;
import com.eldros.market.core.service.LimitSettings;
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
                             boolean rejectFilledContainers, Set<String> blockedPdcNamespaces, int itemOpsPerSecond) {

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
                intIn(c, "performance.item-ops-per-second", 1, 1_000));
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
