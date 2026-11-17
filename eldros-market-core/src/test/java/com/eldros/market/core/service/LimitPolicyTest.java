package com.eldros.market.core.service;

import com.eldros.market.core.domain.Money;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LimitPolicyTest {

    // min 1.00, max 1,000.00 (scale 2), max 2,304 items per order (36 stacks of 64).
    private final LimitPolicy policy = new LimitPolicy(new LimitSettings(Money.ofMinor(100), Money.ofMinor(100_000), 2_304));

    private ValidationResult check(long priceMinor, long quantity, int active, int limit) {
        return policy.validate(Money.ofMinor(priceMinor), quantity, active, limit);
    }

    @Test
    void acceptsAnOrderInsideAllLimits() {
        assertThat(check(100, 1, 0, 5).isOk()).isTrue();
        assertThat(check(100_000, 2_304, 4, 5).isOk()).isTrue();
    }

    // Break: an order with no items creates an empty escrow / a division by zero downstream.
    @Test
    void rejectsZeroOrNegativeQuantity() {
        assertThat(check(100, 0, 0, 5).code()).isEqualTo(ValidationCode.QUANTITY_INVALID);
        assertThat(check(100, -1, 0, 5).code()).isEqualTo(ValidationCode.QUANTITY_INVALID);
    }

    @Test
    void rejectsQuantityAboveTheMaximum() {
        assertThat(check(100, 2_305, 0, 5).code()).isEqualTo(ValidationCode.QUANTITY_TOO_LARGE);
    }

    @Test
    void rejectsPriceBelowMinimumAndAcceptsTheMinimumItself() {
        assertThat(check(99, 1, 0, 5).code()).isEqualTo(ValidationCode.PRICE_TOO_LOW);
        assertThat(check(0, 1, 0, 5).code()).isEqualTo(ValidationCode.PRICE_TOO_LOW);
    }

    @Test
    void rejectsPriceAboveMaximum() {
        assertThat(check(100_001, 1, 0, 5).code()).isEqualTo(ValidationCode.PRICE_TOO_HIGH);
    }

    // Break: price * quantity overflows and a buy order reserves a tiny (or negative) amount.
    @Test
    void rejectsOrdersWhoseTotalDoesNotFitInMinorUnits() {
        LimitPolicy wide = new LimitPolicy(new LimitSettings(Money.ofMinor(1), Money.ofMinor(Long.MAX_VALUE), Integer.MAX_VALUE));

        assertThat(wide.validate(Money.ofMinor(Long.MAX_VALUE / 2 + 1), 2, 0, 5).code()).isEqualTo(ValidationCode.TOTAL_OVERFLOW);
    }

    // Break: off-by-one lets a player hold limit + 1 active orders.
    @Test
    void rejectsWhenThePlayerAlreadyHasTheMaximumNumberOfActiveOrders() {
        assertThat(check(100, 1, 5, 5).code()).isEqualTo(ValidationCode.TOO_MANY_ORDERS);
        assertThat(check(100, 1, 4, 5).isOk()).isTrue();
    }

    @Test
    void settingsRejectInconsistentConfiguration() {
        assertThatThrownBy(() -> new LimitSettings(Money.ZERO, Money.ofMinor(10), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LimitSettings(Money.ofMinor(10), Money.ofMinor(9), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LimitSettings(Money.ofMinor(1), Money.ofMinor(9), 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
