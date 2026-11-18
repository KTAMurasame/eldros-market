package com.eldros.market.paper.gui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Item search by words. Vanilla item names are translated by the client, so the server only knows English item ids;
 * the language files add {@code search-aliases} (local word → id fragments, e.g. {@code elmas: diamond}). Every word
 * of the query must match: either the id contains the word, or the id contains a fragment of an alias that starts
 * with the word ("elma" also finds "elmas"). So "elmas kılıç" finds diamond_sword and "açık mavi yün" light_blue_wool.
 */
public final class ItemSearch {

    private final List<Set<String>> words;

    private ItemSearch(List<Set<String>> words) {
        this.words = words;
    }

    /** {@code aliases}: normalized local word → id fragments (lower case, underscores). */
    public static ItemSearch of(String query, Map<String, List<String>> aliases) {
        List<Set<String>> words = new ArrayList<>();
        for (String word : normalize(query).replace('_', ' ').split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            // A whole word ("elma") uses only the aliases that contain it as a word, so it does not also pull in
            // "elmas"; an unfinished word ("elm") falls back to every alias starting with it.
            Set<String> exact = new HashSet<>();
            Set<String> prefix = new HashSet<>();
            aliases.forEach((key, targets) -> {
                if (hasWord(key, word)) {
                    exact.addAll(targets);
                } else if (key.startsWith(word) || containsWordPrefix(key, word)) {
                    prefix.addAll(targets);
                }
            });
            Set<String> options = new HashSet<>(exact.isEmpty() ? prefix : exact);
            options.add(word);
            words.add(options);
        }
        return new ItemSearch(words);
    }

    /** A multi-word alias key like "açık mavi" also answers to its words, "açık" and "mavi". */
    private static boolean hasWord(String key, String word) {
        for (String part : key.split(" ")) {
            if (part.equals(word)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsWordPrefix(String key, String word) {
        for (String part : key.split(" ")) {
            if (part.startsWith(word)) {
                return true;
            }
        }
        return false;
    }

    /** True if every query word matches the id (e.g. {@code light_blue_wool}) or the visible name. */
    public boolean matches(String itemId, String visibleName) {
        String id = itemId.toLowerCase(Locale.ROOT);
        String name = visibleName == null ? "" : normalize(visibleName);
        for (Set<String> options : words) {
            boolean any = false;
            for (String option : options) {
                if (id.contains(option.replace(' ', '_')) || name.contains(option)) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                return false;
            }
        }
        return true;
    }

    public boolean isEmpty() {
        return words.isEmpty();
    }

    /**
     * Lower case plus Turkish letter folding (ı/i, ş, ğ, ü, ö, ç → ASCII) on both the query and the alias keys, so
     * "KILIÇ", "kılıç" and "kilic" all compare equal. English ids are ASCII, so they are not affected.
     */
    public static String normalize(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (char ch : s.toLowerCase(Locale.ROOT).toCharArray()) {
            out.append(switch (ch) {
                case 'ı', 'i' -> 'i';
                case 'ş' -> 's';
                case 'ğ' -> 'g';
                case 'ü' -> 'u';
                case 'ö' -> 'o';
                case 'ç' -> 'c';
                case '̇' -> '\0'; // combining dot left by lower-casing 'İ'
                default -> ch;
            });
        }
        return out.toString().replace("\0", "").trim();
    }
}
