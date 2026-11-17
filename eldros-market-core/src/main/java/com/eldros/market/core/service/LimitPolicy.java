package com.eldros.market.core.service;

import com.eldros.market.core.domain.Money;

/**
 * Order-level limits (docs/02 F-ORD-9). The item blacklist is not here: only the paper module knows item
 * types, so it lives in {@code ItemFilter} (docs/03 §3).
 */
public final class LimitPolicy {

    private final LimitSettings settings;

    public LimitPolicy(LimitSettings settings) {
        this.settings = settings;
    }

    public ValidationResult validate(Money unitPrice, long quantity, int activeOrders, int activeOrderLimit) {
        if (quantity <= 0) {
            return ValidationResult.rejected(ValidationCode.QUANTITY_INVALID);
        }
        if (quantity > settings.maxQuantityPerOrder()) {
            return ValidationResult.rejected(ValidationCode.QUANTITY_TOO_LARGE);
        }
        if (unitPrice.compareTo(settings.minUnitPrice()) < 0) {
            return ValidationResult.rejected(ValidationCode.PRICE_TOO_LOW);
        }
        if (unitPrice.isGreaterThan(settings.maxUnitPrice())) {
            return ValidationResult.rejected(ValidationCode.PRICE_TOO_HIGH);
        }
        try {
            unitPrice.times(quantity);
        } catch (ArithmeticException e) {
            return ValidationResult.rejected(ValidationCode.TOTAL_OVERFLOW);
        }
        if (activeOrders >= activeOrderLimit) {
            return ValidationResult.rejected(ValidationCode.TOO_MANY_ORDERS);
        }
        return ValidationResult.OK;
    }
}
