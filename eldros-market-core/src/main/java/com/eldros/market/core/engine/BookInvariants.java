package com.eldros.market.core.engine;

import com.eldros.market.api.model.OrderStatus;
import com.eldros.market.core.domain.Order;

import java.util.ArrayList;
import java.util.List;

/**
 * In-memory book checks for tests and {@code /marketadmin verify} (docs/03 §7.7). A book may stay crossed only
 * between orders of the same owner (self-trade prevention, D5); any other crossing is a missed match.
 * Escrow (I1) and conservation (I2/I3) are checked against storage in F2, where they can actually diverge.
 */
public final class BookInvariants {

    private static final int MAX_REPORTED = 100;

    private BookInvariants() {
    }

    public static List<String> check(OrderBook book) {
        List<String> violations = new ArrayList<>();
        List<Order> bids = book.bids();
        List<Order> asks = book.asks();
        for (Order order : bids) {
            requireActive(order, violations);
        }
        for (Order order : asks) {
            requireActive(order, violations);
        }
        for (Order bid : bids) {
            for (Order ask : asks) {
                if (ask.unitPrice().isGreaterThan(bid.unitPrice()) || violations.size() >= MAX_REPORTED) {
                    break; // asks ascend: no later ask crosses this bid
                }
                if (!ask.owner().equals(bid.owner())) {
                    violations.add("crossed: bid " + bid + " and ask " + ask + " belong to different owners");
                }
            }
        }
        return violations;
    }

    private static void requireActive(Order order, List<String> violations) {
        if (order.status() != OrderStatus.ACTIVE || order.remaining() <= 0) {
            violations.add("order in book is not active: " + order);
        }
    }
}
