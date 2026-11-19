package com.eldros.market.core.service;

import com.eldros.market.core.domain.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeesTest {

    // Break: tax is rounded up (the seller loses a fraction they should keep) or computed with the wrong scale.
    @ParameterizedTest
    @CsvSource({
            "10000, 250, 250",   // 2.5% of 100.00 = 2.50
            "999, 250, 24",      // 2.5% of 9.99 = 0.24975 -> 0.24, never rounded up
            "5000, 0, 0",
            "5000, 10000, 5000",
            "1, 9999, 0",
    })
    void taxIsBasisPointsOfTheGrossRoundedDown(long grossMinor, int bps, long expectedMinor) {
        assertThat(Fees.tax(Money.ofMinor(grossMinor), bps)).isEqualTo(Money.ofMinor(expectedMinor));
    }

    // Break: a huge sale overflows the multiplication and produces a negative or tiny tax.
    @Test
    void taxDoesNotOverflowOnHugeAmounts() {
        assertThat(Fees.tax(Money.ofMinor(Long.MAX_VALUE), 10_000)).isEqualTo(Money.ofMinor(Long.MAX_VALUE));
        assertThat(Fees.tax(Money.ofMinor(Long.MAX_VALUE), 5_000)).isEqualTo(Money.ofMinor(Long.MAX_VALUE / 2));
    }

    @Test
    void ratesOutsideZeroToHundredPercentAreRejected() {
        assertThatThrownBy(() -> Fees.tax(Money.ofMinor(100), -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Fees.tax(Money.ofMinor(100), 10_001)).isInstanceOf(IllegalArgumentException.class);
    }

    // Break: the cap is ignored, or "no cap" (0) caps the fee at zero.
    @ParameterizedTest
    @CsvSource({
            "10000, 100, 100, 0, 200",     // 1.00 flat + 1% of 100.00
            "10000, 0, 500, 0, 500",       // 5% only
            "10000, 100, 500, 300, 300",   // capped at 3.00
            "10000, 0, 0, 0, 0",           // fees off
            "999, 0, 100, 0, 9",           // 1% of 9.99 = 0.0999 -> 0.09
    })
    void creationFeeIsFlatPlusPercentUpToTheCap(long valueMinor, long flatMinor, int bps, long capMinor, long expectedMinor) {
        assertThat(Fees.creationFee(Money.ofMinor(valueMinor), Money.ofMinor(flatMinor), bps, Money.ofMinor(capMinor)))
                .isEqualTo(Money.ofMinor(expectedMinor));
    }

    @Test
    void parsesPercentagesIntoBasisPoints() {
        assertThat(Fees.basisPoints("2.5")).isEqualTo(250);
        assertThat(Fees.basisPoints("0")).isZero();
        assertThat(Fees.basisPoints("100")).isEqualTo(10_000);
        assertThat(Fees.basisPoints("0.01")).isEqualTo(1);
        assertThatThrownBy(() -> Fees.basisPoints("0.001")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Fees.basisPoints("101")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Fees.basisPoints("-1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Fees.basisPoints("abc")).isInstanceOf(IllegalArgumentException.class);
    }
}
