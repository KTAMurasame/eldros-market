package com.eldros.market.api.model;

/** See docs/03 §5. FROZEN: bound to an item template that became invalid after a version upgrade. */
public enum OrderStatus {
    ACTIVE,
    FILLED,
    CANCELLED,
    EXPIRED,
    FROZEN
}
