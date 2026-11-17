package com.eldros.market.core.domain;

import com.eldros.market.api.model.OrderSide;
import com.eldros.market.api.model.OrderStatus;
import com.eldros.market.api.model.TemplateId;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    private static Order buy(long quantity) {
        return Order.create(1, new PlayerId(new UUID(0, 1)), OrderSide.BUY, new TemplateId(1), Money.ofMinor(12), quantity, 1, null);
    }

    // Break: filling more than what remains delivers items nobody escrowed (dupe) and drives escrow negative.
    @Test
    void fillingMoreThanRemainsIsRefusedAndChangesNothing() {
        Order order = buy(5);
        order.applyFill(3);

        assertThatThrownBy(() -> order.applyFill(3)).isInstanceOf(IllegalStateException.class);
        assertThat(order.remaining()).isEqualTo(2);
        assertThat(order.escrow()).isEqualTo(Money.ofMinor(24));
    }

    @Test
    void aClosedOrderCannotBeFilled() {
        Order order = buy(5);
        order.close(OrderStatus.CANCELLED);

        assertThatThrownBy(() -> order.applyFill(1)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anOrderNeedsAPositiveQuantity() {
        assertThatThrownBy(() -> buy(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
