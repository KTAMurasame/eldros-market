package com.eldros.market.paper.gui;

import com.eldros.market.core.service.PriceRules;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Market categories from {@code categories.yml}: each category lists item id patterns ({@code *} = any characters,
 * bare ids mean {@code minecraft:}). The first category in file order whose pattern matches the whole id wins;
 * anything else is {@link #OTHER}. Pure Java so it is tested without a server.
 */
public final class Categories {

    /** Everything (no category filter). Reserved id. */
    public static final String ALL = "all";
    /** Items no category claims. Reserved id. */
    public static final String OTHER = "other";

    public static final Categories NONE = new Categories(List.of());

    public record Category(String id, String icon, Map<String, String> names, List<String> patterns) {
    }

    private record Compiled(String id, List<Pattern> patterns) {
    }

    private final List<Category> categories;
    private final List<Compiled> compiled;

    public Categories(List<Category> categories) {
        Set<String> seen = new HashSet<>();
        List<Compiled> out = new ArrayList<>();
        for (Category c : categories) {
            String id = c.id().toLowerCase(Locale.ROOT);
            if (id.equals(ALL) || id.equals(OTHER)) {
                throw new IllegalArgumentException("category id '" + id + "' is reserved");
            }
            if (!seen.add(id)) {
                throw new IllegalArgumentException("category '" + id + "' is listed twice");
            }
            List<Pattern> ps = new ArrayList<>();
            for (String raw : c.patterns()) {
                ps.add(glob(PriceRules.normalise(raw)));
            }
            out.add(new Compiled(id, List.copyOf(ps)));
        }
        this.categories = List.copyOf(categories);
        this.compiled = List.copyOf(out);
    }

    /** {@code *} becomes "any characters"; everything else is literal. */
    private static Pattern glob(String pattern) {
        List<String> parts = new ArrayList<>();
        for (String part : pattern.split("\\*", -1)) {
            parts.add(part.isEmpty() ? "" : Pattern.quote(part));
        }
        return Pattern.compile(String.join(".*", parts));
    }

    /** The category of the item with namespaced id {@code itemId}, or {@link #OTHER}. */
    public String categoryOf(String itemId) {
        String id = PriceRules.normalise(itemId);
        for (Compiled c : compiled) {
            for (Pattern p : c.patterns()) {
                if (p.matcher(id).matches()) {
                    return c.id();
                }
            }
        }
        return OTHER;
    }

    /** Category ids in file order (without ALL and OTHER). */
    public List<String> ids() {
        return compiled.stream().map(Compiled::id).toList();
    }

    public List<Category> all() {
        return categories;
    }

    /** Display name in {@code language}, else English, else the id. */
    public String name(String id, String language) {
        for (Category c : categories) {
            if (c.id().equalsIgnoreCase(id)) {
                String n = c.names().get(language);
                if (n == null) {
                    n = c.names().get("en_US");
                }
                return n == null ? id : n;
            }
        }
        return id;
    }
}
