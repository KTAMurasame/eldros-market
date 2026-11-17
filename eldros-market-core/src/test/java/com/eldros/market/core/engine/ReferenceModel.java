package com.eldros.market.core.engine;

import com.eldros.market.api.model.OrderSide;
import com.eldros.market.core.domain.PlayerId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Deliberately naive matcher used only as an oracle for differential testing (docs/F1-core-plan.md §5.3).
 * Every step scans a flat list for the single best eligible resting order: opposite side, same market, different
 * owner, crossing price; best = cheapest ask / highest bid, then lowest seq. Obviously correct, O(n^2), no indexes.
 */
final class ReferenceModel {

    record RefFill(long restingId, long incomingId, long quantity, long price) {
    }

    private static final class Resting {
        final long id;
        final PlayerId owner;
        final OrderSide side;
        final long template;
        final long price;
        final long seq;
        long remaining;

        Resting(long id, PlayerId owner, OrderSide side, long template, long price, long remaining, long seq) {
            this.id = id;
            this.owner = owner;
            this.side = side;
            this.template = template;
            this.price = price;
            this.remaining = remaining;
            this.seq = seq;
        }
    }

    private final List<Resting> resting = new ArrayList<>();

    List<RefFill> place(long id, PlayerId owner, OrderSide side, long template, long price, long quantity, long seq) {
        List<RefFill> fills = new ArrayList<>();
        long left = quantity;
        while (left > 0) {
            Optional<Resting> best = resting.stream()
                    .filter(r -> r.template == template && r.side != side && !r.owner.equals(owner))
                    .filter(r -> side == OrderSide.BUY ? r.price <= price : r.price >= price)
                    .min(side == OrderSide.BUY
                            ? Comparator.<Resting>comparingLong(r -> r.price).thenComparingLong(r -> r.seq)
                            : Comparator.<Resting>comparingLong(r -> -r.price).thenComparingLong(r -> r.seq));
            if (best.isEmpty()) {
                break;
            }
            Resting r = best.get();
            long q = Math.min(left, r.remaining);
            fills.add(new RefFill(r.id, id, q, r.price));
            r.remaining -= q;
            left -= q;
            if (r.remaining == 0) {
                resting.remove(r);
            }
        }
        if (left > 0) {
            resting.add(new Resting(id, owner, side, template, price, left, seq));
        }
        return fills;
    }

    /** Removes an order and returns its remaining quantity. */
    long cancel(long id) {
        Resting r = resting.stream().filter(x -> x.id == id).findFirst().orElseThrow();
        resting.remove(r);
        return r.remaining;
    }

    List<Long> activeIds() {
        return resting.stream().map(r -> r.id).sorted().toList();
    }
}
