package com.eldros.market.core.port;

import com.eldros.market.core.domain.Money;
import com.eldros.market.core.domain.PlayerId;

/**
 * The economy provider (Vault in the paper module). Main thread only. Only {@link EconomyResult#success()} is
 * trusted; providers report imprecise balances (F0 B5). A thrown exception means the outcome is unknown (03 §7.3).
 */
public interface EconomyGateway {

    EconomyResult withdraw(PlayerId player, Money amount);

    EconomyResult deposit(PlayerId player, Money amount);

    record EconomyResult(boolean success, String error) {

        public static EconomyResult ok() {
            return new EconomyResult(true, null);
        }

        public static EconomyResult failed(String error) {
            return new EconomyResult(false, error);
        }
    }
}
