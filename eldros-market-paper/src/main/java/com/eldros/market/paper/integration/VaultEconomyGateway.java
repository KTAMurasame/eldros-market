package com.eldros.market.paper.integration;

import com.eldros.market.core.domain.Money;
import com.eldros.market.core.domain.MoneyScale;
import com.eldros.market.core.domain.PlayerId;
import com.eldros.market.core.port.EconomyGateway;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;

/**
 * Vault economy (docs/03 §7.3, Vault boundary rules). Main thread. The amount is converted from minor units with the
 * configured scale; only {@code transactionSuccess()} is trusted (F0 B5). Exceptions are not caught here: the caller
 * treats them as an unknown outcome and quarantines (F0 B6).
 */
public final class VaultEconomyGateway implements EconomyGateway {

    private final Economy economy;
    private final MoneyScale scale;

    public VaultEconomyGateway(Economy economy, MoneyScale scale) {
        this.economy = economy;
        this.scale = scale;
    }

    @Override
    public EconomyResult withdraw(PlayerId player, Money amount) {
        EconomyResponse r = economy.withdrawPlayer(Bukkit.getOfflinePlayer(player.value()), scale.toProviderAmount(amount));
        return r.transactionSuccess() ? EconomyResult.ok() : EconomyResult.failed(r.errorMessage);
    }

    @Override
    public EconomyResult deposit(PlayerId player, Money amount) {
        EconomyResponse r = economy.depositPlayer(Bukkit.getOfflinePlayer(player.value()), scale.toProviderAmount(amount));
        return r.transactionSuccess() ? EconomyResult.ok() : EconomyResult.failed(r.errorMessage);
    }

    public String providerName() {
        return economy.getName();
    }

    public int providerFractionalDigits() {
        return economy.fractionalDigits();
    }
}
