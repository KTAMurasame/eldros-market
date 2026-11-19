package com.eldros.market.core.service;

import com.eldros.market.core.domain.Money;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PriceRulesTest {

    private static final LimitSettings BASE = new LimitSettings(Money.ofMinor(1), Money.ofMinor(100_000_000), 2304);

    private static PriceRules.Range range(Long min, Long max) {
        return new PriceRules.Range(Optional.ofNullable(min).map(Money::ofMinor), Optional.ofNullable(max).map(Money::ofMinor));
    }

    // Break: an item without a rule gets someone else's limits or loses the global ones.
    @Test
    void itemsWithoutARuleUseTheGlobalLimits() {
        PriceRules rules = new PriceRules(BASE, Map.of("minecraft:diamond", range(1_000L, 50_000L)));
        assertThat(rules.forItem("minecraft:dirt")).isEqualTo(BASE);
    }

    // Break: the override replaces the quantity limit too, or only one of min/max is applied.
    @Test
    void aRuleReplacesOnlyThePricesItSets() {
        PriceRules rules = new PriceRules(BASE, Map.of(
                "minecraft:diamond", range(1_000L, 50_000L),
                "minecraft:dirt", range(null, 10L),
                "minecraft:netherite_block", range(500_000L, null)));
        assertThat(rules.forItem("minecraft:diamond"))
                .isEqualTo(new LimitSettings(Money.ofMinor(1_000), Money.ofMinor(50_000), 2304));
        assertThat(rules.forItem("minecraft:dirt"))
                .isEqualTo(new LimitSettings(Money.ofMinor(1), Money.ofMinor(10), 2304));
        assertThat(rules.forItem("minecraft:netherite_block"))
                .isEqualTo(new LimitSettings(Money.ofMinor(500_000), Money.ofMinor(100_000_000), 2304));
    }

    // Break: "diamond" or "Minecraft:DIAMOND" in config.yml silently matches nothing.
    @Test
    void keysAreNormalisedToLowerCaseNamespacedIds() {
        PriceRules rules = new PriceRules(BASE, Map.of("Diamond", range(1_000L, null)));
        assertThat(rules.forItem("minecraft:diamond").minUnitPrice()).isEqualTo(Money.ofMinor(1_000));
        assertThat(rules.forItem("MINECRAFT:DIAMOND").minUnitPrice()).isEqualTo(Money.ofMinor(1_000));
        assertThat(rules.overrides()).containsOnlyKeys("minecraft:diamond");
    }

    // Break: a rule may sit above the global maximum (expensive items) — it must not be capped back.
    @Test
    void aRuleMayGoBeyondTheGlobalRange() {
        PriceRules rules = new PriceRules(BASE, Map.of("minecraft:beacon", range(null, 900_000_000L)));
        assertThat(rules.forItem("minecraft:beacon").maxUnitPrice()).isEqualTo(Money.ofMinor(900_000_000));
    }

    // Break: an impossible range is accepted and every order for the item is refused without explanation.
    @Test
    void anImpossibleRangeFailsLoudlyNamingTheItem() {
        assertThatThrownBy(() -> new PriceRules(BASE, Map.of("minecraft:diamond", range(5_000L, 1_000L))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("minecraft:diamond");
        assertThatThrownBy(() -> new PriceRules(BASE, Map.of("minecraft:dirt", range(200_000_000L, null))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("minecraft:dirt");
        assertThatThrownBy(() -> new PriceRules(BASE, Map.of("minecraft:dirt", range(0L, null))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("minecraft:dirt");
    }

    @Test
    void malformedKeysAreRejected() {
        assertThatThrownBy(() -> new PriceRules(BASE, Map.of("a:b:c", range(1L, null))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("a:b:c");
        assertThatThrownBy(() -> new PriceRules(BASE, Map.of(" ", range(1L, null))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void twoSpellingsOfTheSameItemAreRejected() {
        assertThatThrownBy(() -> new PriceRules(BASE, Map.of("diamond", range(1L, null), "minecraft:diamond", range(2L, null))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("minecraft:diamond");
    }
}
