package com.eldros.market.paper.gui;

import com.eldros.market.api.model.TemplateId;
import com.eldros.market.core.domain.Money;
import com.eldros.market.core.engine.BookView.MarketSummary;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MarketSortTest {

    private static MarketSummary s(long id, Long bid, Long ask, long bidQty, long askQty, int orders) {
        return new MarketSummary(new TemplateId(id), Optional.ofNullable(bid).map(Money::ofMinor),
                Optional.ofNullable(ask).map(Money::ofMinor), bidQty, askQty, orders);
    }

    private static final MarketSummary DIAMOND = s(1, 5_000L, 6_000L, 10, 2, 4);
    private static final MarketSummary APPLE = s(2, null, 100L, 0, 64, 1);
    private static final MarketSummary IRON = s(3, 400L, null, 30, 0, 7);
    private static final Map<Long, String> IDS = Map.of(1L, "diamond", 2L, "apple", 3L, "iron_ingot");

    private static List<Long> sorted(MarketSort sort) {
        return MarketSort.sort(List.of(DIAMOND, APPLE, IRON), sort, t -> IDS.get(t.value())).stream()
                .map(m -> m.template().value()).toList();
    }

    @Test
    void mostActiveFirstByOrderCount() {
        assertThat(sorted(MarketSort.ACTIVE)).containsExactly(3L, 1L, 2L);
    }

    @Test
    void nameIsAlphabeticalById() {
        assertThat(sorted(MarketSort.NAME)).containsExactly(2L, 1L, 3L);
    }

    @Test
    void demandAndSupplyAreLargestFirst() {
        assertThat(sorted(MarketSort.DEMAND)).containsExactly(3L, 1L, 2L);
        assertThat(sorted(MarketSort.SUPPLY)).containsExactly(2L, 1L, 3L);
    }

    // Break: a market with no sell orders sorts as "free" (price 0) at the top of "cheapest".
    @Test
    void priceSortsPutMissingPricesLast() {
        assertThat(sorted(MarketSort.CHEAPEST)).containsExactly(2L, 1L, 3L);
        assertThat(sorted(MarketSort.BEST_PAYING)).containsExactly(1L, 3L, 2L);
    }

    @Test
    void cyclingWrapsBothWays() {
        assertThat(MarketSort.ACTIVE.next()).isEqualTo(MarketSort.NAME);
        assertThat(MarketSort.values()[MarketSort.values().length - 1].next()).isEqualTo(MarketSort.ACTIVE);
        assertThat(MarketSort.ACTIVE.previous()).isEqualTo(MarketSort.values()[MarketSort.values().length - 1]);
    }
}
