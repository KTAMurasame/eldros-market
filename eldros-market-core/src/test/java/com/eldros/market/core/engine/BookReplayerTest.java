package com.eldros.market.core.engine;

import com.eldros.market.api.model.OrderSide;
import com.eldros.market.api.model.TemplateId;
import com.eldros.market.core.domain.Money;
import com.eldros.market.core.domain.Order;
import com.eldros.market.core.domain.PlayerId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static com.eldros.market.api.model.OrderSide.BUY;
import static com.eldros.market.api.model.OrderSide.SELL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class BookReplayerTest {

    private static final TemplateId DIAMOND = new TemplateId(1);
    private static final PlayerId A = new PlayerId(new UUID(0, 1));
    private static final PlayerId B = new PlayerId(new UUID(0, 2));

    private static Order order(long id, PlayerId owner, OrderSide side, long price, long quantity) {
        return Order.create(id, owner, side, DIAMOND, Money.ofMinor(price), quantity, id, null);
    }

    // Break: after a crash between "order saved" and "order matched", crossed orders of different owners stay
    // crossed forever, or are matched at the wrong (newer) price, or self-trades appear (docs/03 §6.4).
    @Test
    void rebuildsInSeqOrderAndMatchesOnlyDifferentOwnersAtTheOlderPrice() {
        Order aSell9 = order(1, A, SELL, 9, 1);
        Order aBuy12 = order(2, A, BUY, 12, 1);
        Order bBuy10 = order(3, B, BUY, 10, 1);
        MatchingEngine engine = new MatchingEngine();

        List<MatchPlan> plans = BookReplayer.rebuild(engine, List.of(bBuy10, aBuy12, aSell9)); // deliberately unsorted

        assertThat(plans).flatExtracting(MatchPlan::fills)
                .extracting(Fill::restingOrderId, Fill::incomingOrderId, Fill::quantity, f -> f.price().minor())
                .containsExactly(tuple(1L, 3L, 1L, 9L));
        assertThat(engine.book(DIAMOND).bids()).containsExactly(aBuy12);
        assertThat(engine.book(DIAMOND).asks()).isEmpty();
    }

    @Test
    void aBookThatIsNotCrossedIsRestoredWithoutAnyFill() {
        Order sell11 = order(1, A, SELL, 11, 2);
        Order buy10 = order(2, B, BUY, 10, 3);
        Order sell12 = order(3, B, SELL, 12, 1);
        MatchingEngine engine = new MatchingEngine();

        List<MatchPlan> plans = BookReplayer.rebuild(engine, List.of(sell12, buy10, sell11));

        assertThat(plans).flatExtracting(MatchPlan::fills).isEmpty();
        assertThat(engine.book(DIAMOND).asks()).containsExactly(sell11, sell12);
        assertThat(engine.book(DIAMOND).bids()).containsExactly(buy10);
    }

    // Break: replaying on top of a live book duplicates every order.
    @Test
    void refusesToReplayIntoAnEngineThatAlreadyHasOrders() {
        MatchingEngine engine = new MatchingEngine();
        engine.place(order(1, A, SELL, 10, 1));

        assertThatThrownBy(() -> BookReplayer.rebuild(engine, List.of(order(2, B, BUY, 9, 1))))
                .isInstanceOf(IllegalStateException.class);
    }
}
