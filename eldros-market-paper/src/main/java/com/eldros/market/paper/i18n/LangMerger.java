package com.eldros.market.paper.i18n;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.List;
import java.util.Objects;

/**
 * Brings a server's language file up to date with the bundled one: missing keys are added, and a key whose value is
 * still exactly an older bundled default (listed in {@code lang/retired.yml}) gets the new default. Text an admin
 * changed is never touched, because it no longer equals any old default.
 */
public final class LangMerger {

    public record Result(int added, int updated) {
        public boolean changed() {
            return added > 0 || updated > 0;
        }
    }

    private LangMerger() {
    }

    /**
     * @param retired this language's section of retired.yml: key → list of former default values (may be null)
     */
    public static Result merge(YamlConfiguration defaults, YamlConfiguration current, ConfigurationSection retired) {
        int added = 0;
        int updated = 0;
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key)) {
                continue;
            }
            if (!current.contains(key)) {
                current.set(key, defaults.get(key));
                added++;
                continue;
            }
            List<?> old = retired == null ? null : retired.getList(key);
            if (old == null) {
                continue;
            }
            Object value = current.get(key);
            for (Object former : old) {
                if (same(former, value)) {
                    current.set(key, defaults.get(key));
                    updated++;
                    break;
                }
            }
        }
        return new Result(added, updated);
    }

    /** Text or list-of-text equality, ignoring how YAML typed the list. */
    static boolean same(Object a, Object b) {
        if (a instanceof List<?> la && b instanceof List<?> lb) {
            if (la.size() != lb.size()) {
                return false;
            }
            for (int i = 0; i < la.size(); i++) {
                if (!Objects.equals(String.valueOf(la.get(i)), String.valueOf(lb.get(i)))) {
                    return false;
                }
            }
            return true;
        }
        return a != null && b != null && !(a instanceof List) && !(b instanceof List) && a.toString().equals(b.toString());
    }
}
