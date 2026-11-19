package com.eldros.market.core.engine;

import com.eldros.market.api.model.OrderSide;
import com.eldros.market.api.model.TemplateId;
import com.eldros.market.core.domain.Money;

import java.util.Optional;

/** Read-only snapshots of the books for menus. Plain values, safe to hand from the engine thread to the main thread. */
public final class BookView {

    private BookView() {
    }

    /** One market (template) with resting orders. Quantities are remaining items summed over the side. */
    public record MarketSummary(TemplateId template, Optional<Money> bestBid, Optional<Money> bestAsk,
                                long bidQuantity, long askQuantity, int orderCount) {
    }

    /** One price level of one side; {@code ownQuantity} is the viewer's share of {@code quantity}. */
    public record DepthLevel(OrderSide side, Money price, long quantity, int orders, long ownQuantity) {
    }

    /**
     * What an order would do right now: {@code fillable} items trade immediately at {@code cost} in total
     * (resting prices, own orders skipped), and {@code crossesOwn} tells that an own opposite order sits in range.
     * {@code worstPrice} is the least favourable resting price that would be traded (empty when nothing fills); an
     * instant order uses it as its limit so a moving book can never give the player a worse price than shown.
     */
    public record Preview(long fillable, Money cost, boolean crossesOwn, Optional<Money> worstPrice) {
    }

    static boolean crosses(OrderSide incomingSide, Money limit, Money restingPrice) {
        return incomingSide == OrderSide.BUY ? restingPrice.compareTo(limit) <= 0 : restingPrice.compareTo(limit) >= 0;
    }

}
