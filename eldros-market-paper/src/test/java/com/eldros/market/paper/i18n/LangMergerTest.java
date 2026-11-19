package com.eldros.market.paper.i18n;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LangMergerTest {

    private static YamlConfiguration yaml(String text) {
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.loadFromString(text);
        } catch (InvalidConfigurationException e) {
            throw new IllegalStateException(e);
        }
        return y;
    }

    private static final YamlConfiguration DEFAULTS = yaml("""
            mailbox:
              waiting: "Mailbox entries waiting: <count>."
              header: "Your mailbox"
              fresh: "new key"
            help:
              - "line 1"
              - "line 2 new"
            """);

    private static final YamlConfiguration RETIRED = yaml("""
            mailbox:
              waiting:
                - "You have <count> item(s) waiting."
            help:
              - ["line 1"]
            """);

    // Break: an update never reaches servers that kept the old default text (the original problem).
    @Test
    void anUntouchedOldDefaultIsReplaced() {
        YamlConfiguration current = yaml("""
                mailbox:
                  waiting: "You have <count> item(s) waiting."
                  header: "Your mailbox"
                help:
                  - "line 1"
                """);
        LangMerger.Result r = LangMerger.merge(DEFAULTS, current, RETIRED);
        assertThat(current.getString("mailbox.waiting")).isEqualTo("Mailbox entries waiting: <count>.");
        assertThat(current.getStringList("help")).containsExactly("line 1", "line 2 new");
        assertThat(current.getString("mailbox.fresh")).isEqualTo("new key");
        assertThat(r.added()).isEqualTo(1);
        assertThat(r.updated()).isEqualTo(2);
    }

    // Break: an admin's own wording is overwritten by an update.
    @Test
    void customisedTextIsKept() {
        YamlConfiguration current = yaml("""
                mailbox:
                  waiting: "<red>Check your mail (<count>)</red>"
                  header: "My own header"
                help:
                  - "my help"
                """);
        LangMerger.Result r = LangMerger.merge(DEFAULTS, current, RETIRED);
        assertThat(current.getString("mailbox.waiting")).isEqualTo("<red>Check your mail (<count>)</red>");
        assertThat(current.getString("mailbox.header")).isEqualTo("My own header");
        assertThat(current.getStringList("help")).containsExactly("my help");
        assertThat(r.updated()).isZero();
    }

    @Test
    void nothingToDoChangesNothing() {
        YamlConfiguration current = yaml("""
                mailbox:
                  waiting: "Mailbox entries waiting: <count>."
                  header: "Your mailbox"
                  fresh: "new key"
                help:
                  - "line 1"
                  - "line 2 new"
                """);
        assertThat(LangMerger.merge(DEFAULTS, current, RETIRED)).isEqualTo(new LangMerger.Result(0, 0));
    }

    // Break: a retired value equals today's default (no-op) or names a key that no longer exists (never applied).
    @Test
    void bundledRetiredValuesPointAtExistingChangedKeys() throws Exception {
        YamlConfiguration retired = load("/lang/retired.yml");
        for (String lang : List.of("en_US", "tr_TR")) {
            YamlConfiguration current = load("/lang/" + lang + ".yml");
            var section = retired.getConfigurationSection(lang);
            assertThat(section).as(lang).isNotNull();
            for (String key : section.getKeys(true)) {
                if (section.isConfigurationSection(key)) {
                    continue;
                }
                assertThat(current.contains(key)).as(lang + " " + key).isTrue();
                for (Object old : section.getList(key)) {
                    assertThat(LangMerger.same(old, current.get(key))).as(lang + " " + key + " still equals " + old).isFalse();
                }
            }
        }
    }

    private static YamlConfiguration load(String path) throws Exception {
        try (var in = LangMergerTest.class.getResourceAsStream(path)) {
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }
}
