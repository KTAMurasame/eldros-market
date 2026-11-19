package com.eldros.market.paper.gui;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** The categories.yml shipped in the jar loads and sorts common items where players expect them. */
class BundledCategoriesTest {

    private static final Categories BUNDLED = load();

    private static Categories load() {
        try (var in = BundledCategoriesTest.class.getResourceAsStream("/categories.yml")) {
            return MenuConfigs.readCategories(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // Break: an earlier, broader pattern steals items ("stone_*" catching stone tools, "*torch" catching redstone torches).
    @ParameterizedTest
    @CsvSource({
            "diamond, minerals", "iron_ore, minerals", "deepslate_iron_ore, minerals", "raw_gold, minerals",
            "redstone, minerals", "netherite_ingot, minerals",
            "wheat_seeds, farming", "golden_apple, farming", "cooked_beef, farming", "oak_sapling, farming",
            "bone, mob-drops", "string, mob-drops", "ender_pearl, mob-drops",
            "stone_pickaxe, tools", "diamond_axe, tools", "water_bucket, tools",
            "diamond_sword, combat", "netherite_chestplate, combat", "bow, combat",
            "redstone_torch, redstone", "piston, redstone", "powered_rail, redstone", "oak_door, redstone",
            "white_bed, decoration", "torch, decoration", "poppy, decoration", "chest, decoration",
            "oak_planks, blocks", "cobblestone, blocks", "stone_bricks, blocks", "white_wool, blocks", "glass_pane, blocks",
            "sandstone, blocks", "oak_slab, blocks",
            "elytra, tools", "name_tag, tools", "crafting_table, decoration", "beacon, decoration",
            "short_grass, nature", "crimson_roots, nature", "moss_block, nature", "cracked_deepslate_tiles, blocks",
            "bamboo_mosaic, blocks", "stripped_bamboo_block, blocks", "bamboo, farming", "vine, nature", "beetroot_soup, farming", "dragon_egg, other"
    })
    void commonItemsLandInTheExpectedCategory(String item, String category) {
        assertThat(BUNDLED.categoryOf(item)).isEqualTo(category);
    }
}
