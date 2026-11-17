package com.eldros.market.core.domain;

import java.math.BigDecimal;

/**
 * Configured number of decimal places ({@code economy.decimal-places}). The provider's own
 * {@code fractionalDigits()} is only compared for a warning; it never changes the scale (docs/03 §7.3, F0 B4).
 */
public record MoneyScale(int decimalPlaces) {

    public static final int MAX_DECIMAL_PLACES = 4;

    public enum ProviderCheck { MATCH, MISMATCH, PROVIDER_UNKNOWN }

    public MoneyScale {
        if (decimalPlaces < 0 || decimalPlaces > MAX_DECIMAL_PLACES) {
            throw new IllegalArgumentException("decimal places must be 0.." + MAX_DECIMAL_PLACES + ": " + decimalPlaces);
        }
    }

    public static MoneyScale of(int decimalPlaces) {
        return new MoneyScale(decimalPlaces);
    }

    /** The amount handed to the economy provider. Exact decimal first, then the nearest double. */
    public double toProviderAmount(Money money) {
        return BigDecimal.valueOf(money.minor(), decimalPlaces).doubleValue();
    }

    public ProviderCheck compareWithProvider(int providerFractionalDigits) {
        if (providerFractionalDigits < 0) {
            return ProviderCheck.PROVIDER_UNKNOWN;
        }
        return providerFractionalDigits == decimalPlaces ? ProviderCheck.MATCH : ProviderCheck.MISMATCH;
    }
}
