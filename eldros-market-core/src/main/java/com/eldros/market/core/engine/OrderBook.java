package com.eldros.market.core.engine;

import com.eldros.market.api.model.OrderSide;
import com.eldros.market.core.domain.Money;
import com.eldros.market.core.domain.Order;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Resting orders of one market. Each side maps price level to the orders at that price keyed by seq, so
 * iteration is always best price first, then oldest first. Not thread-safe (engine thread only).
 */
public final class OrderBook {

    private final NavigableMap<Money, NavigableMap<Long, Order>> bids = new TreeMap<>(Comparator.reverseOrder());
    private final NavigableMap<Money, NavigableMap<Long, Order>> asks = new TreeMap<>();

    void add(Order order) {
        levels(order.side()).computeIfAbsent(order.unitPrice(), p -> new TreeMap<>()).put(order.seq(), order);
    }

    void remove(Order order) {
        NavigableMap<Money, NavigableMap<Long, Order>> side = levels(order.side());
        NavigableMap<Long, Order> level = side.get(order.unitPrice());
        if (level != null && level.remove(order.seq()) != null && level.isEmpty()) {
            side.remove(order.unitPrice());
        }
    }

    /** Price levels of one side in priority order. Read-only view. */
    NavigableMap<Money, NavigableMap<Long, Order>> levels(OrderSide side) {
        return side == OrderSide.BUY ? bids : asks;
    }

    public List<Order> bids() {
        return flatten(bids);
    }

    public List<Order> asks() {
        return flatten(asks);
    }

    public Optional<Money> bestBid() {
        return bids.isEmpty() ? Optional.empty() : Optional.of(bids.firstKey());
    }

    public Optional<Money> bestAsk() {
        return asks.isEmpty() ? Optional.empty() : Optional.of(asks.firstKey());
    }

    private static List<Order> flatten(NavigableMap<Money, NavigableMap<Long, Order>> side) {
        List<Order> out = new ArrayList<>();
        side.values().forEach(level -> out.addAll(level.values()));
        return Collections.unmodifiableList(out);
    }
}
