package com.eldros.market.core.engine;

import com.eldros.market.api.model.OrderSide;
import com.eldros.market.api.model.OrderStatus;
import com.eldros.market.api.model.TemplateId;
import com.eldros.market.core.domain.Money;
import com.eldros.market.core.domain.Order;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

/**
 * Price-time priority matching with self-trade prevention (docs/03 §6). An incoming order trades against the
 * best opposite levels at the resting price; a resting order of the same owner is skipped but keeps its place.
 * Single-threaded by contract: all calls come from the engine thread (docs/03 §4).
 */
public final class MatchingEngine {

    private final Map<TemplateId, OrderBook> books = new HashMap<>();
    private final Map<Long, Order> active = new HashMap<>();

    /** Computes the fills for {@code incoming} without changing anything. */
    public MatchPlan plan(Order incoming) {
        if (incoming.status() != OrderStatus.ACTIVE || active.containsKey(incoming.id())) {
            throw new IllegalStateException("order is not a new active order: " + incoming);
        }
        OrderBook book = books.get(incoming.template());
        List<Fill> fills = new ArrayList<>();
        List<Long> restingBefore = new ArrayList<>();
        long left = incoming.remaining();
        if (book != null) {
            levels:
            for (Map.Entry<Money, NavigableMap<Long, Order>> level : book.levels(incoming.side().opposite()).entrySet()) {
                if (!crosses(incoming, level.getKey())) {
                    break;
                }
                for (Order resting : level.getValue().values()) {
                    if (resting.owner().equals(incoming.owner())) {
                        continue; // self-trade prevention: skip, priority untouched (D5)
                    }
                    long quantity = Math.min(left, resting.remaining());
                    fills.add(fill(incoming, resting, quantity, level.getKey()));
                    restingBefore.add(resting.remaining());
                    left -= quantity;
                    if (left == 0) {
                        break levels;
                    }
                }
            }
        }
        return new MatchPlan(incoming, List.copyOf(fills), incoming.remaining(), List.copyOf(restingBefore));
    }

    /** Applies a plan from {@link #plan}. Validates everything first, so a stale plan changes nothing. */
    public void apply(MatchPlan plan) {
        Order incoming = plan.incomingOrder();
        if (active.containsKey(incoming.id()) || incoming.remaining() != plan.incomingRemainingBefore()) {
            throw new IllegalStateException("stale plan for incoming " + incoming);
        }
        List<Order> resting = new ArrayList<>(plan.fills().size());
        for (int i = 0; i < plan.fills().size(); i++) {
            Order order = active.get(plan.fills().get(i).restingOrderId());
            if (order == null || order.remaining() != plan.restingRemainingBefore().get(i)) {
                throw new IllegalStateException("stale plan: resting order " + plan.fills().get(i).restingOrderId() + " changed");
            }
            resting.add(order);
        }
        OrderBook book = books.computeIfAbsent(incoming.template(), t -> new OrderBook());
        for (int i = 0; i < resting.size(); i++) {
            long quantity = plan.fills().get(i).quantity();
            Order order = resting.get(i);
            order.applyFill(quantity);
            incoming.applyFill(quantity);
            if (order.status() == OrderStatus.FILLED) {
                book.remove(order);
                active.remove(order.id());
            }
        }
        if (incoming.status() == OrderStatus.ACTIVE) {
            book.add(incoming);
            active.put(incoming.id(), incoming);
        }
    }

    /** {@link #plan} then {@link #apply}; for callers that need no persistence step in between. */
    public MatchPlan place(Order incoming) {
        MatchPlan plan = plan(incoming);
        apply(plan);
        return plan;
    }

    boolean isEmpty() {
        return active.isEmpty();
    }

    public OrderBook book(TemplateId template) {
        OrderBook book = books.get(template);
        return book != null ? book : new OrderBook();
    }

    /** Plans ending an active order (player cancel, expiry). Refund = buy escrow or remaining sell items. */
    public CancelPlan planCancel(long orderId, OrderStatus finalStatus) {
        if (finalStatus != OrderStatus.CANCELLED && finalStatus != OrderStatus.EXPIRED) {
            throw new IllegalArgumentException("not a cancel status: " + finalStatus);
        }
        Order order = active.get(orderId);
        if (order == null) {
            throw new IllegalStateException("order " + orderId + " is not active");
        }
        long items = order.side() == OrderSide.SELL ? order.remaining() : 0;
        return new CancelPlan(order.id(), order.owner(), order.side(), order.template(), order.escrow(), items, finalStatus);
    }

    public void applyCancel(CancelPlan plan) {
        Order order = active.get(plan.orderId());
        long items = order == null ? -1 : (order.side() == OrderSide.SELL ? order.remaining() : 0);
        if (order == null || !order.escrow().equals(plan.refundMoney()) || items != plan.refundItems()) {
            throw new IllegalStateException("stale cancel plan for order " + plan.orderId());
        }
        books.get(order.template()).remove(order);
        active.remove(order.id());
        order.close(plan.finalStatus());
    }

    /** Active orders whose expiry is at or before {@code nowMillis}; none when expiry is disabled (D3). */
    public List<Order> expiredOrders(long nowMillis, boolean expiryEnabled) {
        if (!expiryEnabled) {
            return List.of();
        }
        return active.values().stream()
                .filter(o -> o.expiresAt() != null && o.expiresAt() <= nowMillis)
                .toList();
    }

    private static boolean crosses(Order incoming, Money restingPrice) {
        return incoming.side() == OrderSide.BUY
                ? restingPrice.compareTo(incoming.unitPrice()) <= 0
                : restingPrice.compareTo(incoming.unitPrice()) >= 0;
    }

    private static Fill fill(Order incoming, Order resting, long quantity, Money price) {
        Order buy = incoming.side() == OrderSide.BUY ? incoming : resting;
        Order sell = buy == incoming ? resting : incoming;
        return new Fill(resting.id(), incoming.id(), buy.id(), sell.id(), buy.owner(), sell.owner(),
                incoming.template(), quantity, price, buy.unitPrice());
    }
}
