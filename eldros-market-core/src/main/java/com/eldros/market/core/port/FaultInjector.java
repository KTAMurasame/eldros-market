package com.eldros.market.core.port;

/**
 * Seam for crash tests (docs/04 §3). Production uses {@link #NONE}; tests throw at a chosen point to simulate a
 * process crash. Points are named after the windows in docs/03 §7.3.
 */
@FunctionalInterface
public interface FaultInjector {

    FaultInjector NONE = point -> { };

    void at(CrashPoint point);

    enum CrashPoint {
        BUY_PENDING_WRITTEN, BUY_PROVIDER_DONE, BUY_EFFECT_LOGGED, BUY_IN_TRANSACTION, BUY_COMMITTED,
        SELL_PENDING_WRITTEN, SELL_REMOVED_UNSAVED, SELL_SAVED, SELL_IN_TRANSACTION, SELL_COMMITTED,
        MONEY_CLAIMING_WRITTEN, MONEY_PROVIDER_DONE, MONEY_EFFECT_LOGGED, MONEY_IN_TRANSACTION, MONEY_COMMITTED,
        ITEM_CLAIMING_WRITTEN, ITEM_ADDED_UNSAVED, ITEM_SAVED, ITEM_IN_TRANSACTION, ITEM_COMMITTED
    }
}
