package com.eldros.market.core.engine;

import com.eldros.market.api.model.OrderSide;
import com.eldros.market.api.model.OrderStatus;
import com.eldros.market.api.model.TemplateId;
import com.eldros.market.core.domain.Money;
import com.eldros.market.core.domain.Order;
import com.eldros.market.core.domain.PlayerId;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Random order/cancel sequences (docs/F1-core-plan.md Â§5.2-5.3). After every step the engine must agree with the
 * naive {@link ReferenceModel} fill-for-fill, keep its book free of cross-owner crossings, never self-trade, and
 * conserve money and items: everything that went into escrow is either still held, paid out, or refunded.
 */
class MatchingPropertiesTest {

    sealed interface Action permits Place, Cancel {
    }

    record Place(int owner, boolean buy, int template, int price, int quantity) implements Action {
    }

    record Cancel(int pick) implements Action {
    }

    private static final List<TemplateId> TEMPLATES = List.of(new TemplateId(1), new TemplateId(2));

    @Provide
    Arbitrary<List<Action>> actions() {
        // Few owners and a narrow price band so self-trade skips, ties and multi-level fills happen often.
        Arbitrary<Action> place = Combinators.combine(
                        Arbitraries.integers().between(1, 4),
                        Arbitraries.of(true, false),
                        Arbitraries.integers().between(0, TEMPLATES.size() - 1),
                        Arbitraries.integers().between(95, 105),
                        Arbitraries.integers().between(1, 20))
                .as(Place::new);
        Arbitrary<Action> cancel = Arbitraries.integers().between(0, 1_000).map(Cancel::new);
        return Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(5, place),
                net.jqwik.api.Tuple.of(1, cancel)).list().ofMinSize(1).ofMaxSize(120);
    }

    @Property(tries = 1_000)
    void engineMatchesTheReferenceModelAndKeepsAllInvariants(@ForAll("actions") List<Action> actions) {
        MatchingEngine engine = new MatchingEngine();
        ReferenceModel reference = new ReferenceModel();
        long nextId = 1;
        long moneyIn = 0, moneyPaid = 0, moneyRefunded = 0;
        long itemsIn = 0, itemsDelivered = 0, itemsRefunded = 0;

        for (Action action : actions) {
            if (action instanceof Place p) {
                long id = nextId++;
                PlayerId owner = new PlayerId(new UUID(0, p.owner()));
                OrderSide side = p.buy() ? OrderSide.BUY : OrderSide.SELL;
                TemplateId template = TEMPLATES.get(p.template());
                Order order = Order.create(id, owner, side, template, Money.ofMinor(p.price()), p.quantity(), id, null);

                MatchPlan plan = engine.place(order);
                List<ReferenceModel.RefFill> expected = reference.place(id, owner, side, template.value(), p.price(), p.quantity(), id);

                assertThat(plan.fills())
                        .extracting(f -> new ReferenceModel.RefFill(f.restingOrderId(), f.incomingOrderId(), f.quantity(), f.price().minor()))
                        .containsExactlyElementsOf(expected);
                assertThat(plan.fills()).allSatisfy(f -> assertThat(f.buyer()).isNotEqualTo(f.seller()));

                if (side == OrderSide.BUY) {
                    moneyIn += (long) p.price() * p.quantity();
                } else {
                    itemsIn += p.quantity();
                }
                for (Fill f : plan.fills()) {
                    moneyPaid += f.total().minor();
                    moneyRefunded += f.priceImprovement().minor();
                    itemsDelivered += f.quantity();
                }
            } else if (action instanceof Cancel c) {
                List<Long> active = reference.activeIds();
                if (active.isEmpty()) {
                    continue;
                }
                long id = active.get(c.pick() % active.size());
                CancelPlan cancel = engine.planCancel(id, OrderStatus.CANCELLED);
                engine.applyCancel(cancel);
                long remaining = reference.cancel(id);

                if (cancel.side() == OrderSide.SELL) {
                    assertThat(cancel.refundItems()).isEqualTo(remaining);
                } else {
                    assertThat(cancel.refundItems()).isZero();
                }
                moneyRefunded += cancel.refundMoney().minor();
                itemsRefunded += cancel.refundItems();
            }

            long moneyHeld = 0, itemsHeld = 0;
            for (TemplateId template : TEMPLATES) {
                OrderBook book = engine.book(template);
                assertThat(BookInvariants.check(book)).isEmpty();
                for (Order bid : book.bids()) {
                    assertThat(bid.escrow()).isEqualTo(bid.unitPrice().times(bid.remaining())); // I1
                    moneyHeld += bid.escrow().minor();
                }
                for (Order ask : book.asks()) {
                    itemsHeld += ask.remaining();
                }
            }
            assertThat(moneyIn).as("money conservation").isEqualTo(moneyHeld + moneyPaid + moneyRefunded);
            assertThat(itemsIn).as("item conservation").isEqualTo(itemsHeld + itemsDelivered + itemsRefunded);
        }
    }
}
