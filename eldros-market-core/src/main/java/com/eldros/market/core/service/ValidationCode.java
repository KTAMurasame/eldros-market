package com.eldros.market.core.service;

/** Machine-readable rejection reasons. Core never renders text; the paper module maps these to language keys. */
public enum ValidationCode {
    EMPTY,
    NOT_A_NUMBER,
    NEGATIVE,
    TOO_MANY_DECIMALS,
    TOO_LARGE,
    QUANTITY_INVALID,
    QUANTITY_TOO_LARGE,
    PRICE_TOO_LOW,
    PRICE_TOO_HIGH,
    TOTAL_OVERFLOW,
    TOO_MANY_ORDERS
}
