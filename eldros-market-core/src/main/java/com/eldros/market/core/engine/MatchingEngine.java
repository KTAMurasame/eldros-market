package com.eldros.market.core.engine;

import com.eldros.market.api.model.OrderSide;
import com.eldros.market.api.model.OrderStatus;
import com.eldros.market.api.model.TemplateId;
import com.eldros.market.core.domain.Money;
import com.eldros.market.core.domain.Order;
import com.eldros.market.core.domain.PlayerId;

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

    /** The active order with this id, if any. Engine thread only; the order must not be changed by the caller. */
    public java.util.Optional<Order> activeOrder(long id) {
        return java.util.Optional.ofNullable(active.get(id));
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
        return new CancelPlan(order.id(), order.owner(), order.side(), order.template(), order.escrow(), items,
                order.remaining(), finalStatus);
    }

    public void applyCancel(CancelPlan plan) {
        Order order = active.get(plan.orderId());
        long items = order == null ? -1 : (order.side() == OrderSide.SELL ? order.remaining() : 0);
        if (order == null || !order.escrow().equals(plan.refundMoney()) || items != plan.refundItems()
                || order.remaining() != plan.remaining()) {
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

    // --- read-only views for menus (engine thread; results are immutable values) -------------------------

    /** Every market with at least one resting order, ordered by template id. */
    public List<BookView.MarketSummary> summaries() {
        List<BookView.MarketSummary> out = new ArrayList<>();
        books.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(java.util.Comparator.comparingLong(TemplateId::value)))
                .forEach(e -> {
                    OrderBook book = e.getValue();
                    List<Order> bids = book.bids();
                    List<Order> asks = book.asks();
                    if (bids.isEmpty() && asks.isEmpty()) {
                        return;
                    }
                    out.add(new BookView.MarketSummary(e.getKey(), book.bestBid(), book.bestAsk(),
                            bids.stream().mapToLong(Order::remaining).sum(), asks.stream().mapToLong(Order::remaining).sum(),
                            bids.size() + asks.size()));
                });
        return out;
    }

    /** The best {@code maxLevels} price levels of one side, best first; {@code viewer} may be null. */
    public List<BookView.DepthLevel> depth(TemplateId template, OrderSide side, int maxLevels, PlayerId viewer) {
        OrderBook book = books.get(template);
        if (book == null || maxLevels <= 0) {
            return List.of();
        }
        List<BookView.DepthLevel> out = new ArrayList<>();
        for (Map.Entry<Money, NavigableMap<Long, Order>> level : book.levels(side).entrySet()) {
            long quantity = 0;
            long own = 0;
            for (Order o : level.getValue().values()) {
                quantity += o.remaining();
                if (o.owner().equals(viewer)) {
                    own += o.remaining();
                }
            }
            out.add(new BookView.DepthLevel(side, level.getKey(), quantity, level.getValue().size(), own));
            if (out.size() == maxLevels) {
                break;
            }
        }
        return out;
    }

    /**
     * What {@link #plan} would fill for such an order now, without creating one: same walk, same self-trade
     * skip, same resting prices. Used to show an estimate before the player confirms.
     */
    public BookView.Preview preview(PlayerId owner, OrderSide side, TemplateId template, Money limit, long quantity) {
        OrderBook book = books.get(template);
        long left = quantity;
        Money cost = Money.ZERO;
        Money worst = null;
        boolean crossesOwn = false;
        if (book != null) {
            for (Map.Entry<Money, NavigableMap<Long, Order>> level : book.levels(side.opposite()).entrySet()) {
                if (!BookView.crosses(side, limit, level.getKey())) {
                    break;
                }
                for (Order resting : level.getValue().values()) {
                    if (resting.owner().equals(owner)) {
                        crossesOwn = true;
                        continue;
                    }
                    if (left == 0) {
                        continue; // keep scanning only to detect an own crossing order
                    }
                    long q = Math.min(left, resting.remaining());
                    cost = cost.plus(level.getKey().times(q));
                    worst = level.getKey(); // levels are walked best first, so the last traded one is the worst
                    left -= q;
                }
            }
        }
        return new BookView.Preview(quantity - left, cost, crossesOwn, java.util.Optional.ofNullable(worst));
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
