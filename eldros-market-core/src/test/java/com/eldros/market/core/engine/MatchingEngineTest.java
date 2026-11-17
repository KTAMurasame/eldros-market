package com.eldros.market.core.engine;

import com.eldros.market.api.model.OrderSide;
import com.eldros.market.api.model.OrderStatus;
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

class MatchingEngineTest {

    private static final TemplateId DIAMOND = new TemplateId(1);
    private static final PlayerId A = player(1);
    private static final PlayerId B = player(2);
    private static final PlayerId C = player(3);
    private static final PlayerId D = player(4);

    private final MatchingEngine engine = new MatchingEngine();
    private long nextId = 1;

    private static PlayerId player(int n) {
        return new PlayerId(new UUID(0, n));
    }

    /** Ids and seq are both increasing, so a lower id is always the older order. */
    private Order order(PlayerId owner, OrderSide side, long price, long quantity) {
        return order(owner, side, price, quantity, null);
    }

    private Order order(PlayerId owner, OrderSide side, long price, long quantity, Long expiresAt) {
        long id = nextId++;
        return Order.create(id, owner, side, DIAMOND, Money.ofMinor(price), quantity, id, expiresAt);
    }

    private static List<Fill> fills(MatchPlan plan) {
        return plan.fills();
    }

    // --- price-time priority -------------------------------------------------------------------

    // Break: asks are scanned in insertion order or highest-first instead of cheapest-first.
    @Test
    void buyFillsCheapestAsksFirstAtEachRestingPrice() {
        Order a10 = engine.place(order(B, SELL, 10, 1)).incomingOrder();
        Order a9 = engine.place(order(C, SELL, 9, 1)).incomingOrder();
        Order a11 = engine.place(order(D, SELL, 11, 1)).incomingOrder();

        MatchPlan plan = engine.place(order(A, BUY, 12, 3));

        assertThat(fills(plan)).extracting(Fill::restingOrderId, Fill::quantity, f -> f.price().minor())
                .containsExactly(tuple(a9.id(), 1L, 9L), tuple(a10.id(), 1L, 10L), tuple(a11.id(), 1L, 11L));
    }

    // Break: equal prices are not ordered by seq (newer order jumps the queue).
    @Test
    void equalPricesFillOldestFirst() {
        Order older = engine.place(order(B, SELL, 10, 1)).incomingOrder();
        engine.place(order(C, SELL, 10, 1));

        MatchPlan plan = engine.place(order(A, BUY, 10, 1));

        assertThat(fills(plan)).extracting(Fill::restingOrderId).containsExactly(older.id());
    }

    // Break: the trade happens at the incoming price instead of the resting order's price.
    @Test
    void sellIntoRestingBuyTradesAtTheBuyersPrice() {
        engine.place(order(A, BUY, 12, 100));

        MatchPlan plan = engine.place(order(B, SELL, 10, 30));

        assertThat(fills(plan)).extracting(Fill::quantity, f -> f.price().minor()).containsExactly(tuple(30L, 12L));
    }

    // Break: partial fills drop the remainder or forget to reduce the buy escrow (I1).
    @Test
    void partialFillsLeaveTheRemainderActiveWithMatchingEscrow() {
        Order buy = engine.place(order(A, BUY, 12, 100)).incomingOrder();

        engine.place(order(B, SELL, 10, 30));
        engine.place(order(C, SELL, 11, 50));

        assertThat(buy.remaining()).isEqualTo(20);
        assertThat(buy.status()).isEqualTo(OrderStatus.ACTIVE);
        assertThat(buy.escrow()).isEqualTo(Money.ofMinor(20 * 12)); // I1: remaining x limit price
        assertThat(engine.book(DIAMOND).bids()).containsExactly(buy);
    }

    // Break: a buyer who bids 12 but trades at 9 is charged 12 (price improvement kept by nobody).
    @Test
    void incomingBuyBelowItsLimitRecordsThePriceImprovement() {
        engine.place(order(B, SELL, 9, 4));

        Order buy = order(A, BUY, 12, 4);
        MatchPlan plan = engine.place(buy);

        Fill fill = fills(plan).getFirst();
        assertThat(fill.total()).isEqualTo(Money.ofMinor(36));            // 4 x 9 to the seller
        assertThat(fill.priceImprovement()).isEqualTo(Money.ofMinor(12)); // 4 x (12 - 9) back to the buyer
        assertThat(buy.escrow()).isEqualTo(Money.ZERO);
        assertThat(buy.status()).isEqualTo(OrderStatus.FILLED);
    }

    @Test
    void ordersThatDoNotCrossBothRest() {
        engine.place(order(B, SELL, 13, 1));

        MatchPlan plan = engine.place(order(A, BUY, 12, 1));

        assertThat(fills(plan)).isEmpty();
        assertThat(engine.book(DIAMOND).bestBid()).contains(Money.ofMinor(12));
        assertThat(engine.book(DIAMOND).bestAsk()).contains(Money.ofMinor(13));
    }

    // Break: matching stops after the first level, or keeps going past the incoming limit.
    @Test
    void walksPriceLevelsUntilTheIncomingIsFilledOrTheLimitIsReached() {
        engine.place(order(B, SELL, 10, 3));
        Order at11 = engine.place(order(C, SELL, 11, 3)).incomingOrder();
        engine.place(order(D, SELL, 12, 3));

        MatchPlan plan = engine.place(order(A, BUY, 11, 5));

        assertThat(fills(plan)).extracting(Fill::quantity, f -> f.price().minor()).containsExactly(tuple(3L, 10L), tuple(2L, 11L));
        assertThat(at11.remaining()).isEqualTo(1);
        assertThat(engine.book(DIAMOND).bestAsk()).contains(Money.ofMinor(11));
    }

    @Test
    void filledRestingOrdersLeaveTheBook() {
        Order sell = engine.place(order(B, SELL, 10, 2)).incomingOrder();

        engine.place(order(A, BUY, 10, 2));

        assertThat(sell.status()).isEqualTo(OrderStatus.FILLED);
        assertThat(engine.book(DIAMOND).asks()).isEmpty();
        assertThat(engine.book(DIAMOND).bids()).isEmpty();
    }

    // --- self-trade prevention (D5, docs/03 §6.3) ----------------------------------------------

    // Break: a player's own resting order is matched, or it loses its place in the queue.
    @Test
    void ownRestingOrderIsSkippedButKeepsItsPriority() {
        Order aSell9 = engine.place(order(A, SELL, 9, 1)).incomingOrder();
        Order bSell10 = engine.place(order(B, SELL, 10, 1)).incomingOrder();

        MatchPlan aBuys = engine.place(order(A, BUY, 12, 1));
        assertThat(fills(aBuys)).extracting(Fill::restingOrderId).containsExactly(bSell10.id());

        MatchPlan cBuys = engine.place(order(C, BUY, 12, 1));
        assertThat(fills(cBuys)).extracting(Fill::restingOrderId).containsExactly(aSell9.id());
    }

    @Test
    void ownOrdersInsideAPriceLevelAreSkippedInFifoOrder() {
        Order a1 = engine.place(order(A, SELL, 10, 1)).incomingOrder();
        Order b = engine.place(order(B, SELL, 10, 1)).incomingOrder();
        Order a2 = engine.place(order(A, SELL, 10, 1)).incomingOrder();
        Order c = engine.place(order(C, SELL, 10, 1)).incomingOrder();

        MatchPlan plan = engine.place(order(A, BUY, 10, 2));

        assertThat(fills(plan)).extracting(Fill::restingOrderId).containsExactly(b.id(), c.id());
        assertThat(engine.book(DIAMOND).asks()).containsExactly(a1, a2);
    }

    @Test
    void orderCrossingOnlyTheOwnersOrdersRests() {
        Order aSell = engine.place(order(A, SELL, 9, 1)).incomingOrder();

        MatchPlan plan = engine.place(order(A, BUY, 12, 1));

        assertThat(fills(plan)).isEmpty();
        assertThat(engine.book(DIAMOND).asks()).containsExactly(aSell);
        assertThat(engine.book(DIAMOND).bids()).containsExactly(plan.incomingOrder());
    }

    @Test
    void noFillEverHasTheSameBuyerAndSeller() {
        engine.place(order(A, SELL, 9, 5));
        engine.place(order(B, SELL, 9, 5));

        MatchPlan plan = engine.place(order(A, BUY, 9, 10));

        assertThat(fills(plan)).allSatisfy(f -> assertThat(f.buyer()).isNotEqualTo(f.seller()));
        assertThat(plan.incomingOrder().remaining()).isEqualTo(5);
    }

    // --- plan / apply contract -----------------------------------------------------------------

    // Break: a plan computed before another change is applied anyway and over-fills a resting order.
    @Test
    void applyingAStalePlanIsRefused() {
        engine.place(order(B, SELL, 10, 1));
        MatchPlan first = engine.plan(order(A, BUY, 10, 1));
        MatchPlan second = engine.plan(order(C, BUY, 10, 1));
        engine.apply(first);

        assertThatThrownBy(() -> engine.apply(second)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void planningDoesNotChangeTheBook() {
        Order sell = engine.place(order(B, SELL, 10, 1)).incomingOrder();

        engine.plan(order(A, BUY, 10, 1));

        assertThat(sell.remaining()).isEqualTo(1);
        assertThat(engine.book(DIAMOND).asks()).containsExactly(sell);
        assertThat(engine.book(DIAMOND).bids()).isEmpty();
    }

    // --- cancel and expiry ---------------------------------------------------------------------

    // Break: a partly filled buy refunds the original amount instead of what is still in escrow.
    @Test
    void cancellingAPartlyFilledBuyRefundsTheRemainingEscrow() {
        Order buy = engine.place(order(A, BUY, 12, 10)).incomingOrder();
        engine.place(order(B, SELL, 12, 4));

        CancelPlan cancel = engine.planCancel(buy.id(), OrderStatus.CANCELLED);
        engine.applyCancel(cancel);

        assertThat(cancel.refundMoney()).isEqualTo(Money.ofMinor(6 * 12));
        assertThat(cancel.refundItems()).isZero();
        assertThat(buy.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(engine.book(DIAMOND).bids()).isEmpty();
    }

    @Test
    void cancellingAPartlyFilledSellRefundsTheRemainingItems() {
        Order sell = engine.place(order(A, SELL, 10, 10)).incomingOrder();
        engine.place(order(B, BUY, 10, 3));

        CancelPlan cancel = engine.planCancel(sell.id(), OrderStatus.CANCELLED);

        assertThat(cancel.refundItems()).isEqualTo(7);
        assertThat(cancel.refundMoney()).isEqualTo(Money.ZERO);
    }

    // Break: a cancel planned before a fill refunds the pre-fill escrow (money created out of thin air).
    @Test
    void applyingACancelPlanThatNoLongerMatchesTheOrderIsRefused() {
        Order buy = engine.place(order(A, BUY, 12, 10)).incomingOrder();
        CancelPlan cancel = engine.planCancel(buy.id(), OrderStatus.CANCELLED); // refund would be 120
        engine.place(order(B, SELL, 12, 4));                                    // escrow is now 72

        assertThatThrownBy(() -> engine.applyCancel(cancel)).isInstanceOf(IllegalStateException.class);
        assertThat(buy.status()).isEqualTo(OrderStatus.ACTIVE);
        assertThat(buy.escrow()).isEqualTo(Money.ofMinor(72));
    }

    @Test
    void cancellingAnOrderThatIsNotActiveIsRefused() {
        Order sell = engine.place(order(A, SELL, 10, 1)).incomingOrder();
        engine.place(order(B, BUY, 10, 1));

        assertThatThrownBy(() -> engine.planCancel(sell.id(), OrderStatus.CANCELLED)).isInstanceOf(IllegalStateException.class);
    }

    // Break: off-by-one at the expiry instant, or orders without an expiry are swept.
    @Test
    void expiredOrdersAreThoseWhoseExpiryIsAtOrBeforeNow() {
        Order before = engine.place(order(A, SELL, 10, 1, 999L)).incomingOrder();
        Order exactly = engine.place(order(B, SELL, 11, 1, 1_000L)).incomingOrder();
        engine.place(order(C, SELL, 12, 1, 1_001L));
        engine.place(order(D, SELL, 13, 1, null));

        assertThat(engine.expiredOrders(1_000L, true)).containsExactlyInAnyOrder(before, exactly);
    }

    @Test
    void nothingExpiresWhenExpiryIsDisabled() {
        engine.place(order(A, SELL, 10, 1, 1L));

        assertThat(engine.expiredOrders(1_000L, false)).isEmpty();
    }
}
