package com.eldros.market.core.service;

import com.eldros.market.core.domain.Money;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Global order limits plus per-item price ranges ({@code limits.per-item} in config.yml). A rule replaces only the
 * prices it sets; the quantity limit always stays global. A rule may lie outside the global range (an expensive item
 * may need a higher maximum). Inconsistent rules fail loudly at load, naming the item.
 */
public final class PriceRules {

    /** A per-item override; an empty side keeps the global value. */
    public record Range(Optional<Money> min, Optional<Money> max) {
    }

    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    private final LimitSettings base;
    private final Map<String, LimitSettings> overrides;

    public PriceRules(LimitSettings base, Map<String, Range> rules) {
        this.base = base;
        Map<String, LimitSettings> out = new TreeMap<>();
        for (var e : rules.entrySet()) {
            String key = normalise(e.getKey());
            if (!ID.matcher(key).matches()) {
                throw new IllegalArgumentException("'" + e.getKey() + "' is not an item id like minecraft:diamond");
            }
            if (out.containsKey(key)) {
                throw new IllegalArgumentException(key + " is listed twice");
            }
            Range r = e.getValue();
            try {
                out.put(key, new LimitSettings(r.min().orElse(base.minUnitPrice()), r.max().orElse(base.maxUnitPrice()),
                        base.maxQuantityPerOrder()));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException(key + ": " + ex.getMessage());
            }
        }
        this.overrides = Map.copyOf(out);
    }

    /** Lower-case namespaced id; a bare name gets the {@code minecraft:} namespace. */
    public static String normalise(String key) {
        String k = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        return k.isEmpty() || k.contains(":") ? k : "minecraft:" + k;
    }

    public LimitSettings base() {
        return base;
    }

    /** Limits for the item with namespaced id {@code itemKey}. */
    public LimitSettings forItem(String itemKey) {
        return overrides.getOrDefault(normalise(itemKey), base);
    }

    public Map<String, LimitSettings> overrides() {
        return overrides;
    }
}
