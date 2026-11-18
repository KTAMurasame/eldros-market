package com.eldros.market.paper.gui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ItemSearchTest {

    private static final List<String> IDS = List.of("diamond", "diamond_sword", "apple", "golden_apple", "clay",
            "clay_ball", "iron_sword", "light_blue_wool", "blue_wool", "light_gray_wool", "red_wool", "oak_log",
            "dark_oak_log", "birch_log");

    private static final Map<String, List<String>> TR = Map.of(
            ItemSearch.normalize("elmas"), List.of("diamond"),
            ItemSearch.normalize("elma"), List.of("apple"),
            ItemSearch.normalize("altın elma"), List.of("golden_apple"),
            ItemSearch.normalize("kil"), List.of("clay"),
            ItemSearch.normalize("kılıç"), List.of("sword"),
            ItemSearch.normalize("açık mavi"), List.of("light_blue"),
            ItemSearch.normalize("mavi"), List.of("blue"),
            ItemSearch.normalize("yün"), List.of("wool"),
            ItemSearch.normalize("meşe"), List.of("oak"),
            ItemSearch.normalize("kütük"), List.of("log"));

    private static List<String> find(String query) {
        ItemSearch s = ItemSearch.of(query, TR);
        return IDS.stream().filter(id -> s.matches(id, null)).toList();
    }

    @Test
    void everyWordMustMatch() {
        assertThat(find("elmas kılıç")).containsExactly("diamond_sword");
        assertThat(find("açık mavi yün")).containsExactly("light_blue_wool");
        assertThat(find("meşe kütük")).containsExactly("oak_log", "dark_oak_log");
    }

    // Break: "elma" also returns diamonds because "elmas" starts with it.
    @Test
    void aWholeWordDoesNotPullInLongerAliases() {
        assertThat(find("elma")).containsExactly("apple", "golden_apple");
        assertThat(find("kil")).containsExactly("clay", "clay_ball");
        assertThat(find("elm")).contains("diamond", "apple");
    }

    @Test
    void turkishLettersCaseAndAsciiSpellingsAreEqual() {
        assertThat(find("KILIÇ")).containsExactly("diamond_sword", "iron_sword");
        assertThat(find("kilic")).containsExactly("diamond_sword", "iron_sword");
        assertThat(find("AÇIK MAVİ YÜN")).containsExactly("light_blue_wool");
    }

    @Test
    void englishIdsStillWorkAndDoNotDependOnTheJvmLocale() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR")); // "I".toLowerCase() would give "ı" here
            assertThat(find("DIAMOND SWORD")).containsExactly("diamond_sword");
            assertThat(find("oak_log")).containsExactly("oak_log", "dark_oak_log");
        } finally {
            Locale.setDefault(before);
        }
    }

    @Test
    void aCustomNameCanMatchToo() {
        ItemSearch s = ItemSearch.of("excalibur", TR);
        assertThat(s.matches("diamond_sword", "Excalibur")).isTrue();
        assertThat(s.matches("diamond_sword", null)).isFalse();
    }
}
