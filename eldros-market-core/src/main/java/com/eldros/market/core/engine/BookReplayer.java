package com.eldros.market.core.engine;

import com.eldros.market.core.domain.Order;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Startup rebuild (docs/03 §6.4). Active orders loaded from storage are fed through the normal matching path in
 * seq order. If a crash happened after an order was saved but before it was matched, this produces exactly the
 * fills a live run would have produced (older order = resting = its price, self-trades skipped). A book that is
 * not crossed comes back unchanged. The returned plans with fills must be persisted by the caller.
 */
public final class BookReplayer {

    private BookReplayer() {
    }

    public static List<MatchPlan> rebuild(MatchingEngine engine, Collection<Order> activeOrders) {
        if (!engine.isEmpty()) {
            throw new IllegalStateException("replay needs an empty engine");
        }
        List<MatchPlan> plans = new ArrayList<>(activeOrders.size());
        activeOrders.stream()
                .sorted(Comparator.comparingLong(Order::seq))
                .forEach(order -> plans.add(engine.place(order)));
        return plans;
    }
}
