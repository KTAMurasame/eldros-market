package com.eldros.market.core.service;

import com.eldros.market.core.domain.Money;

/** Config-backed order limits. Rejected at load time if inconsistent, so a bad config fails loudly at startup. */
public record LimitSettings(Money minUnitPrice, Money maxUnitPrice, int maxQuantityPerOrder) {

    public LimitSettings {
        if (minUnitPrice.minor() < 1) {
            throw new IllegalArgumentException("minimum unit price must be at least one minor unit");
        }
        if (maxUnitPrice.compareTo(minUnitPrice) < 0) {
            throw new IllegalArgumentException("maximum unit price is below the minimum");
        }
        if (maxQuantityPerOrder < 1) {
            throw new IllegalArgumentException("maximum quantity per order must be positive");
        }
    }
}
