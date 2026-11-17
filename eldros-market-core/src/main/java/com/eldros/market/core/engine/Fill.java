package com.eldros.market.core.engine;

import com.eldros.market.api.model.TemplateId;
import com.eldros.market.core.domain.Money;
import com.eldros.market.core.domain.PlayerId;

/**
 * One trade. {@code price} is always the resting order's price (docs/02 F-ORD-5); {@code buyerLimit} is the
 * buy order's limit, so the buyer gets {@link #priceImprovement()} back when the trade is cheaper.
 */
public record Fill(long restingOrderId, long incomingOrderId, long buyOrderId, long sellOrderId,
                   PlayerId buyer, PlayerId seller, TemplateId template,
                   long quantity, Money price, Money buyerLimit) {

    /** Paid to the seller. */
    public Money total() {
        return price.times(quantity);
    }

    /** Refunded to the buyer: {@code quantity * (buyerLimit - price)}. */
    public Money priceImprovement() {
        return buyerLimit.minus(price).times(quantity);
    }
}
