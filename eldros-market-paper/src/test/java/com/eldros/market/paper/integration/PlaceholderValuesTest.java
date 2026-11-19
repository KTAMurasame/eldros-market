package com.eldros.market.paper.integration;

import com.eldros.market.core.domain.Money;
import com.eldros.market.core.domain.MoneyScale;
import com.eldros.market.paper.integration.PlaceholderValues.ItemQuote;
import com.eldros.market.paper.integration.PlaceholderValues.PlayerStats;
import com.eldros.market.paper.integration.PlaceholderValues.Snapshot;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PlaceholderValuesTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    private static final Snapshot SNAP = new Snapshot(
            Map.of("minecraft:diamond", new ItemQuote(Optional.of(Money.ofMinor(5_000)), Optional.of(Money.ofMinor(123_456)), 12, 3),
                    "minecraft:iron_ingot", new ItemQuote(Optional.empty(), Optional.of(Money.ofMinor(400)), 0, 64)),
            Map.of(ALICE, new PlayerStats(2, 5, 10)), true);

    // A stand-in for the player's language format ("1.234,56" in Turkish), so the test sees which one was used.
    private static final PlaceholderValues.Format FMT = m -> "fmt(" + m.minor() + ")";
    private static final MoneyScale SCALE = MoneyScale.of(2);

    private static String r(UUID player, String params) {
        return PlaceholderValues.resolve(SNAP, player, params, FMT, SCALE, "-");
    }

    // Break: buy/sell swapped (best buy must be the highest bid, best sell the lowest ask).
    @Test
    void bestPricesUseThePlayersFormat() {
        assertThat(r(ALICE, "best_buy_diamond")).isEqualTo("fmt(5000)");
        assertThat(r(ALICE, "best_sell_diamond")).isEqualTo("fmt(123456)");
    }

    // Break: raw values carry grouping separators and break scoreboard math plugins.
    @Test
    void rawPricesArePlainDecimals() {
        assertThat(r(ALICE, "best_buy_diamond_raw")).isEqualTo("50.00");
        assertThat(r(ALICE, "best_sell_diamond_raw")).isEqualTo("1234.56");
        assertThat(PlaceholderValues.resolve(SNAP, ALICE, "best_sell_diamond_raw", FMT, MoneyScale.of(0), "-")).isEqualTo("123456");
    }

    @Test
    void namespacedAndBareItemIdsBothWork() {
        assertThat(r(ALICE, "best_sell_minecraft:iron_ingot")).isEqualTo("fmt(400)");
        assertThat(r(ALICE, "best_sell_IRON_INGOT")).isEqualTo("fmt(400)");
    }

    // Break: an empty side or an untraded item prints "null", "0" (a fake price) or the raw placeholder.
    @Test
    void missingPricesShowTheConfiguredText() {
        assertThat(r(ALICE, "best_buy_iron_ingot")).isEqualTo("-");
        assertThat(r(ALICE, "best_buy_iron_ingot_raw")).isEqualTo("-");
        assertThat(r(ALICE, "best_buy_emerald")).isEqualTo("-");
    }

    @Test
    void demandAndSupplyAreOpenQuantities() {
        assertThat(r(ALICE, "demand_diamond")).isEqualTo("12");
        assertThat(r(ALICE, "supply_diamond")).isEqualTo("3");
        assertThat(r(ALICE, "demand_emerald")).isEqualTo("0");
    }

    // Break: one player's mailbox shown to another, or a player not yet counted shown as 0 instead of "unknown".
    @Test
    void playerValuesBelongToThatPlayer() {
        assertThat(r(ALICE, "mailbox")).isEqualTo("2");
        assertThat(r(ALICE, "orders")).isEqualTo("5");
        assertThat(r(ALICE, "orders_max")).isEqualTo("10");
        assertThat(r(BOB, "mailbox")).isEqualTo("-");
        assertThat(r(null, "orders")).isEqualTo("-");
    }

    @Test
    void frozenIsABoolean() {
        assertThat(r(null, "frozen")).isEqualTo("true");
    }

    // Break: a typo resolves to something instead of null (PlaceholderAPI then leaves the text visibly unresolved).
    @Test
    void unknownPlaceholdersAreNull() {
        assertThat(r(ALICE, "best_price_diamond")).isNull();
        assertThat(r(ALICE, "best_buy_")).isNull();
        assertThat(r(ALICE, "")).isNull();
    }
}
