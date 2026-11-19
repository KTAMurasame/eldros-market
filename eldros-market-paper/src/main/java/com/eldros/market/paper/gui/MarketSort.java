package com.eldros.market.paper.gui;

import com.eldros.market.api.model.TemplateId;
import com.eldros.market.core.domain.Money;
import com.eldros.market.core.engine.BookView.MarketSummary;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** Sort orders of the market list. Ties are broken by item id so the order never jumps between page loads. */
public enum MarketSort {
    /** Most resting orders first. */
    ACTIVE,
    /** Item id A–Z. */
    NAME,
    /** Most items wanted by buy orders first. */
    DEMAND,
    /** Most items offered by sell orders first. */
    SUPPLY,
    /** Lowest sell price first; markets without sell orders last. */
    CHEAPEST,
    /** Highest buy price first; markets without buy orders last. */
    BEST_PAYING;

    public MarketSort next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public MarketSort previous() {
        return values()[(ordinal() + values().length - 1) % values().length];
    }

    public static List<MarketSummary> sort(List<MarketSummary> markets, MarketSort sort, Function<TemplateId, String> itemId) {
        Comparator<MarketSummary> byId = Comparator.comparing(m -> itemId.apply(m.template()), Comparator.nullsLast(Comparator.naturalOrder()));
        Comparator<MarketSummary> main = switch (sort) {
            case ACTIVE -> Comparator.comparingInt(MarketSummary::orderCount).reversed();
            case NAME -> (a, b) -> 0;
            case DEMAND -> Comparator.comparingLong(MarketSummary::bidQuantity).reversed();
            case SUPPLY -> Comparator.comparingLong(MarketSummary::askQuantity).reversed();
            case CHEAPEST -> price(MarketSummary::bestAsk, false);
            case BEST_PAYING -> price(MarketSummary::bestBid, true);
        };
        return markets.stream().sorted(main.thenComparing(byId)).toList();
    }

    private static Comparator<MarketSummary> price(Function<MarketSummary, Optional<Money>> side, boolean highestFirst) {
        Comparator<Money> order = highestFirst ? Comparator.<Money>naturalOrder().reversed() : Comparator.naturalOrder();
        return Comparator.comparing(m -> side.apply(m).orElse(null), Comparator.nullsLast(order));
    }
}
