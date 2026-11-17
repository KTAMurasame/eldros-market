package com.eldros.market.api.model;

public enum OrderSide {
    BUY,
    SELL;

    public OrderSide opposite() {
        return this == BUY ? SELL : BUY;
    }
}
