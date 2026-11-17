package com.eldros.market.core.domain;

import com.eldros.market.api.model.OrderSide;
import com.eldros.market.api.model.OrderStatus;
import com.eldros.market.api.model.TemplateId;

/**
 * A buy or sell order as the engine sees it. Mutable and <b>not thread-safe</b>: only the single engine
 * thread may change it (docs/03 §4). Buy orders hold money in escrow; invariant I1 is
 * {@code escrow == remaining * unitPrice} while ACTIVE. Sell escrow is the remaining item count.
 */
public final class Order {

    private final long id;
    private final PlayerId owner;
    private final OrderSide side;
    private final TemplateId template;
    private final Money unitPrice;
    private final long quantity;
    private final long seq;
    private final Long expiresAt;

    private long filled;
    private Money escrow;
    private OrderStatus status;

    private Order(long id, PlayerId owner, OrderSide side, TemplateId template, Money unitPrice, long quantity,
                  long seq, Long expiresAt) {
        this.id = id;
        this.owner = owner;
        this.side = side;
        this.template = template;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
        this.seq = seq;
        this.expiresAt = expiresAt;
        this.escrow = side == OrderSide.BUY ? unitPrice.times(quantity) : Money.ZERO;
        this.status = OrderStatus.ACTIVE;
    }

    /** A new ACTIVE order. {@code seq} is the time priority (lower = older); {@code expiresAt} may be null. */
    public static Order create(long id, PlayerId owner, OrderSide side, TemplateId template, Money unitPrice,
                               long quantity, long seq, Long expiresAt) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive: " + quantity);
        }
        return new Order(id, owner, side, template, unitPrice, quantity, seq, expiresAt);
    }

    /** Records a fill. A buy order's escrow always drops by {@code quantity * own limit price} (I1). */
    public void applyFill(long fillQuantity) {
        if (status != OrderStatus.ACTIVE || fillQuantity <= 0 || fillQuantity > remaining()) {
            throw new IllegalStateException("cannot fill " + fillQuantity + " on order " + id + " (" + status + ", remaining " + remaining() + ")");
        }
        filled += fillQuantity;
        if (side == OrderSide.BUY) {
            escrow = escrow.minus(unitPrice.times(fillQuantity));
        }
        if (remaining() == 0) {
            status = OrderStatus.FILLED;
        }
    }

    /** Ends an ACTIVE order whose escrow has been planned for refund. */
    public void close(OrderStatus finalStatus) {
        if (status != OrderStatus.ACTIVE) {
            throw new IllegalStateException("order " + id + " is not active: " + status);
        }
        status = finalStatus;
        escrow = Money.ZERO;
    }

    public long id() { return id; }
    public PlayerId owner() { return owner; }
    public OrderSide side() { return side; }
    public TemplateId template() { return template; }
    public Money unitPrice() { return unitPrice; }
    public long quantity() { return quantity; }
    public long filled() { return filled; }
    public long remaining() { return quantity - filled; }
    public long seq() { return seq; }
    public OrderStatus status() { return status; }
    public Money escrow() { return escrow; }
    public Long expiresAt() { return expiresAt; }

    @Override
    public String toString() {
        return "Order#" + id + "[" + side + " " + remaining() + "/" + quantity + " @" + unitPrice.minor() + " seq " + seq + " " + status + "]";
    }
}
