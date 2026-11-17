package com.eldros.market.core.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyScaleTest {

    // Break: the value sent to Vault is off by a power of ten.
    @ParameterizedTest
    @CsvSource({
            "2, 1234, 12.34",
            "2, 1, 0.01",
            "0, 1234, 1234.0",
            "4, 15, 0.0015",
    })
    void convertsMinorUnitsToProviderAmount(int decimalPlaces, long minor, double expected) {
        assertThat(MoneyScale.of(decimalPlaces).toProviderAmount(Money.ofMinor(minor))).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 5})
    void rejectsUnsupportedDecimalPlaces(int decimalPlaces) {
        assertThatThrownBy(() -> MoneyScale.of(decimalPlaces)).isInstanceOf(IllegalArgumentException.class);
    }

    // F0 B4: EssentialsX reports fractionalDigits() = -1. Our scale must stay the configured one; we only warn.
    @Test
    void providerReportingUnknownDigitsIsFlaggedButDoesNotChangeScale() {
        MoneyScale scale = MoneyScale.of(2);

        assertThat(scale.compareWithProvider(-1)).isEqualTo(MoneyScale.ProviderCheck.PROVIDER_UNKNOWN);
        assertThat(scale.decimalPlaces()).isEqualTo(2);
    }

    @Test
    void providerWithDifferentDigitsIsAMismatch() {
        assertThat(MoneyScale.of(2).compareWithProvider(0)).isEqualTo(MoneyScale.ProviderCheck.MISMATCH);
    }

    @Test
    void providerWithSameDigitsMatches() {
        assertThat(MoneyScale.of(2).compareWithProvider(2)).isEqualTo(MoneyScale.ProviderCheck.MATCH);
    }
}
