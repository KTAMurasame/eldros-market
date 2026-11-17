package com.eldros.market.core.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    // Break: amounts silently wrap around instead of failing (no multiplyExact).
    @Test
    void timesThrowsOnOverflowInsteadOfWrapping() {
        Money price = Money.ofMinor(Long.MAX_VALUE / 2 + 1);

        assertThatThrownBy(() -> price.times(2)).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void timesMultipliesMinorUnits() {
        assertThat(Money.ofMinor(1_250).times(3)).isEqualTo(Money.ofMinor(3_750));
    }

    // Break: a negative amount reaches escrow or Vault.
    @Test
    void negativeAmountIsRejected() {
        assertThatThrownBy(() -> Money.ofMinor(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void timesRejectsNegativeQuantity() {
        assertThatThrownBy(() -> Money.ofMinor(100).times(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    // Break: refunds/fees drive a balance below zero without anyone noticing.
    @Test
    void minusBelowZeroIsRejected() {
        assertThatThrownBy(() -> Money.ofMinor(5).minus(Money.ofMinor(6))).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void minusSubtracts() {
        assertThat(Money.ofMinor(1_000).minus(Money.ofMinor(1))).isEqualTo(Money.ofMinor(999));
    }

    @Test
    void plusThrowsOnOverflow() {
        assertThatThrownBy(() -> Money.ofMinor(Long.MAX_VALUE).plus(Money.ofMinor(1))).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void comparesByMinorUnits() {
        assertThat(Money.ofMinor(9).compareTo(Money.ofMinor(10))).isNegative();
        assertThat(Money.ofMinor(10).isGreaterThan(Money.ofMinor(9))).isTrue();
        assertThat(Money.ofMinor(10).isGreaterThan(Money.ofMinor(10))).isFalse();
    }
}
