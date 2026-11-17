package com.eldros.market.core.engine;

import com.eldros.market.api.model.OrderSide;
import com.eldros.market.api.model.TemplateId;
import com.eldros.market.core.domain.Money;
import com.eldros.market.core.domain.Order;
import com.eldros.market.core.domain.PlayerId;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.eldros.market.api.model.OrderSide.BUY;
import static com.eldros.market.api.model.OrderSide.SELL;
import static org.assertj.core.api.Assertions.assertThat;

/** Books are built with the package-private {@code add}, i.e. bypassing matching, to create broken states. */
class BookInvariantsTest {

    private static final TemplateId DIAMOND = new TemplateId(1);
    private static final PlayerId A = new PlayerId(new UUID(0, 1));
    private static final PlayerId B = new PlayerId(new UUID(0, 2));

    private static Order order(long id, PlayerId owner, OrderSide side, long price, long quantity) {
        return Order.create(id, owner, side, DIAMOND, Money.ofMinor(price), quantity, id, null);
    }

    // Break: a bid and an ask of different players overlap and nobody notices (a missed match).
    @Test
    void reportsCrossedOrdersOfDifferentOwners() {
        OrderBook book = new OrderBook();
        book.add(order(1, A, BUY, 12, 1));
        book.add(order(2, B, SELL, 10, 1));

        assertThat(BookInvariants.check(book)).singleElement().asString().contains("crossed");
    }

    @Test
    void crossingBetweenOrdersOfTheSameOwnerIsAllowed() {
        OrderBook book = new OrderBook();
        book.add(order(1, A, BUY, 12, 1));
        book.add(order(2, A, SELL, 10, 1));

        assertThat(BookInvariants.check(book)).isEmpty();
    }

    // Break: a crossed pair hides behind a same-owner pair at the top of the book.
    @Test
    void findsADifferentOwnerCrossBelowTheBestLevels() {
        OrderBook book = new OrderBook();
        book.add(order(1, A, BUY, 12, 1));
        book.add(order(2, A, SELL, 9, 1));
        book.add(order(3, B, SELL, 11, 1));

        assertThat(BookInvariants.check(book)).singleElement().asString().contains("crossed");
    }

    // Break: a filled order is left in the book and can be matched again.
    @Test
    void reportsOrdersInTheBookThatAreNotActive() {
        Order filled = order(1, A, SELL, 10, 1);
        filled.applyFill(1);
        OrderBook book = new OrderBook();
        book.add(filled);

        assertThat(BookInvariants.check(book)).singleElement().asString().contains("not active");
    }

    @Test
    void aNormalBookHasNoViolations() {
        OrderBook book = new OrderBook();
        book.add(order(1, A, BUY, 9, 3));
        book.add(order(2, B, SELL, 10, 2));

        assertThat(BookInvariants.check(book)).isEmpty();
    }
}
