package com.eldros.market.core.engine;

import com.eldros.market.api.model.OrderSide;
import com.eldros.market.api.model.TemplateId;
import com.eldros.market.core.domain.Money;
import com.eldros.market.core.domain.Order;
import com.eldros.market.core.domain.PlayerId;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.UUID;

import static com.eldros.market.api.model.OrderSide.BUY;
import static com.eldros.market.api.model.OrderSide.SELL;
import static org.assertj.core.api.Assertions.assertThat;

class BookViewTest {

    private static final TemplateId DIAMOND = new TemplateId(1);
    private static final TemplateId IRON = new TemplateId(2);
    private static final PlayerId A = player(1);
    private static final PlayerId B = player(2);
    private static final PlayerId C = player(3);

    private final MatchingEngine engine = new MatchingEngine();
    private long nextId = 1;

    private static PlayerId player(int n) {
        return new PlayerId(new UUID(0, n));
    }

    private Order order(PlayerId owner, OrderSide side, TemplateId t, long price, long quantity) {
        long id = nextId++;
        return Order.create(id, owner, side, t, Money.ofMinor(price), quantity, id, null);
    }

    private void rest(PlayerId owner, OrderSide side, TemplateId t, long price, long quantity) {
        engine.place(order(owner, side, t, price, quantity));
    }

    @Test
    void summariesListOnlyMarketsWithRestingOrders() {
        rest(A, SELL, DIAMOND, 50, 10);
        rest(B, SELL, DIAMOND, 45, 2);
        rest(C, BUY, DIAMOND, 40, 7);
        rest(A, SELL, IRON, 5, 3);
        engine.place(order(B, BUY, IRON, 5, 3)); // fills IRON completely

        assertThat(engine.summaries()).singleElement().satisfies(s -> {
            assertThat(s.template()).isEqualTo(DIAMOND);
            assertThat(s.bestAsk()).contains(Money.ofMinor(45));
            assertThat(s.bestBid()).contains(Money.ofMinor(40));
            assertThat(s.askQuantity()).isEqualTo(12);
            assertThat(s.bidQuantity()).isEqualTo(7);
            assertThat(s.orderCount()).isEqualTo(3);
        });
    }

    @Test
    void depthAggregatesLevelsBestFirstAndMarksViewerShare() {
        rest(A, SELL, DIAMOND, 50, 10);
        rest(B, SELL, DIAMOND, 50, 4);
        rest(B, SELL, DIAMOND, 45, 2);
        rest(C, SELL, DIAMOND, 60, 1);

        assertThat(engine.depth(DIAMOND, SELL, 2, A))
                .extracting(l -> l.price().minor(), BookView.DepthLevel::quantity, BookView.DepthLevel::orders,
                        BookView.DepthLevel::ownQuantity)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(45L, 2L, 1, 0L),
                        org.assertj.core.groups.Tuple.tuple(50L, 14L, 2, 10L));
        assertThat(engine.depth(IRON, BUY, 5, A)).isEmpty();
    }

    @Test
    void previewSkipsOwnOrdersAndReportsTheCrossing() {
        rest(A, SELL, DIAMOND, 40, 5);
        rest(B, SELL, DIAMOND, 50, 3);
        rest(C, SELL, DIAMOND, 70, 3);

        BookView.Preview p = engine.preview(A, BUY, DIAMOND, Money.ofMinor(60), 10);

        assertThat(p.fillable()).isEqualTo(3);
        assertThat(p.cost()).isEqualTo(Money.ofMinor(150));
        assertThat(p.crossesOwn()).isTrue();
        assertThat(p.worstPrice()).contains(Money.ofMinor(50));
        assertThat(engine.preview(B, BUY, DIAMOND, Money.ofMinor(39), 1).fillable()).isZero();
        assertThat(engine.preview(B, BUY, DIAMOND, Money.ofMinor(39), 1).worstPrice()).isEmpty();
    }

    // Break: the worst price is taken from a level the order never reaches, so an instant order is limited too far.
    @Test
    void worstPriceIsTheLastLevelActuallyTraded() {
        rest(B, BUY, DIAMOND, 50, 2);
        rest(B, BUY, DIAMOND, 45, 4);
        rest(B, BUY, DIAMOND, 30, 9);

        assertThat(engine.preview(A, SELL, DIAMOND, Money.ofMinor(1), 3).worstPrice()).contains(Money.ofMinor(45));
        assertThat(engine.preview(A, SELL, DIAMOND, Money.ofMinor(1), 2).worstPrice()).contains(Money.ofMinor(50));
        assertThat(engine.preview(A, SELL, DIAMOND, Money.ofMinor(1), 100).fillable()).isEqualTo(15);
    }

    // Break: the preview walks the book differently from plan() (price order, self-trade skip, resting price).
    @Test
    void previewAgreesWithPlanOnRandomBooks() {
        Random random = new Random(42);
        PlayerId[] players = {A, B, C};
        for (int round = 0; round < 2_000; round++) {
            PlayerId owner = players[random.nextInt(3)];
            OrderSide side = random.nextBoolean() ? BUY : SELL;
            long price = 1 + random.nextInt(20);
            long quantity = 1 + random.nextInt(10);
            Order incoming = order(owner, side, DIAMOND, price, quantity);

            BookView.Preview preview = engine.preview(owner, side, DIAMOND, Money.ofMinor(price), quantity);
            MatchPlan plan = engine.place(incoming);

            long planned = plan.fills().stream().mapToLong(Fill::quantity).sum();
            Money cost = plan.fills().stream().map(f -> f.price().times(f.quantity())).reduce(Money.ZERO, Money::plus);
            assertThat(preview.fillable()).as("round %d", round).isEqualTo(planned);
            assertThat(preview.cost()).as("round %d", round).isEqualTo(cost);
        }
    }
}
