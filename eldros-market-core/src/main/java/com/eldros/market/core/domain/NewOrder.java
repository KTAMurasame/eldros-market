package com.eldros.market.core.domain;

import com.eldros.market.api.model.OrderSide;
import com.eldros.market.api.model.TemplateId;

/**
 * A validated request for a new order, before storage assigns its id and seq. {@code expiresAt} may be null.
 * An {@code immediateOrCancel} order trades what it can right away (never beyond its limit price) and never rests:
 * its unfilled part is closed and refunded in the same transaction. {@code sellerTaxBps} is the sales tax this order
 * pays when it sells (basis points; ignored for buy orders); {@code creationFee} is taken together with a buy order's
 * escrow and leaves the economy.
 */
public record NewOrder(PlayerId owner, OrderSide side, TemplateId template, Money unitPrice, long quantity, Long expiresAt,
                       boolean immediateOrCancel, int sellerTaxBps, Money creationFee) {

    public NewOrder {
        if (sellerTaxBps < 0 || sellerTaxBps > 10_000) {
            throw new IllegalArgumentException("sellerTaxBps must be 0..10000: " + sellerTaxBps);
        }
        if (creationFee == null || creationFee.minor() < 0) {
            throw new IllegalArgumentException("creationFee must be >= 0");
        }
    }

    /** A normal (resting) order without fees. */
    public NewOrder(PlayerId owner, OrderSide side, TemplateId template, Money unitPrice, long quantity, Long expiresAt) {
        this(owner, side, template, unitPrice, quantity, expiresAt, false, 0, Money.ZERO);
    }

    public NewOrder(PlayerId owner, OrderSide side, TemplateId template, Money unitPrice, long quantity, Long expiresAt,
                    boolean immediateOrCancel) {
        this(owner, side, template, unitPrice, quantity, expiresAt, immediateOrCancel, 0, Money.ZERO);
    }

    /** An instant order: fills now at prices no worse than {@code limit}, the rest is returned. */
    public static NewOrder immediate(PlayerId owner, OrderSide side, TemplateId template, Money limit, long quantity) {
        return new NewOrder(owner, side, template, limit, quantity, null, true);
    }

    public NewOrder withFees(int taxBps, Money fee) {
        return new NewOrder(owner, side, template, unitPrice, quantity, expiresAt, immediateOrCancel, taxBps, fee);
    }
}
