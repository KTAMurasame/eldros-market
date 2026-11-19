package com.eldros.market.paper.gui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CategoriesTest {

    private static Categories sample() {
        return new Categories(List.of(
                new Categories.Category("minerals", "DIAMOND", Map.of("en_US", "Minerals", "tr_TR", "Madenler"),
                        List.of("*_ore", "diamond", "*_ingot", "minecraft:emerald")),
                new Categories.Category("blocks", "BRICKS", Map.of("en_US", "Blocks"), List.of("*_planks", "stone*", "*brick*"))));
    }

    // Break: a later category steals an item an earlier one claims (order in categories.yml must decide).
    @Test
    void theFirstMatchingCategoryWins() {
        Categories c = sample();
        assertThat(c.categoryOf("minecraft:diamond")).isEqualTo("minerals");
        assertThat(c.categoryOf("minecraft:iron_ore")).isEqualTo("minerals");
        assertThat(c.categoryOf("minecraft:stone_bricks")).isEqualTo("blocks");
        assertThat(c.categoryOf("minecraft:oak_planks")).isEqualTo("blocks");
    }

    // Break: "diamond" matches "diamond_sword", or "emerald" matches "emerald_block".
    @Test
    void patternsMatchTheWholeId() {
        Categories c = sample();
        assertThat(c.categoryOf("minecraft:diamond_sword")).isEqualTo(Categories.OTHER);
        assertThat(c.categoryOf("minecraft:emerald")).isEqualTo("minerals");
        assertThat(c.categoryOf("minecraft:emerald_block")).isEqualTo(Categories.OTHER);
    }

    // Break: items from other namespaces (custom items registered by data packs) match vanilla patterns.
    @Test
    void namespacesAreRespected() {
        Categories c = sample();
        assertThat(c.categoryOf("othermod:diamond")).isEqualTo(Categories.OTHER);
        assertThat(c.categoryOf("diamond")).isEqualTo("minerals");
    }

    @Test
    void namesFallBackToEnglishThenTheId() {
        Categories c = sample();
        assertThat(c.name("minerals", "tr_TR")).isEqualTo("Madenler");
        assertThat(c.name("blocks", "tr_TR")).isEqualTo("Blocks");
        assertThat(c.ids()).containsExactly("minerals", "blocks");
    }

    @Test
    void reservedOrDuplicateIdsAreRejected() {
        assertThatThrownBy(() -> new Categories(List.of(new Categories.Category("all", "CHEST", Map.of(), List.of("x")))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("all");
        assertThatThrownBy(() -> new Categories(List.of(
                new Categories.Category("a", "CHEST", Map.of(), List.of("x")),
                new Categories.Category("a", "CHEST", Map.of(), List.of("y")))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'a'");
    }
}
