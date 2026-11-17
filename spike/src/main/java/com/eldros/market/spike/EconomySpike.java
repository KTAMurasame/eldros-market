package com.eldros.market.spike;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** S5: Vault economy behaviour, plus a throwaway test provider that can throw on demand. */
final class EconomySpike {

    private static final UUID TEST_ACCOUNT = UUID.fromString("00000000-0000-0000-0000-00000000e1d0");

    private final JavaPlugin plugin;
    private final Report report;
    private Economy testEconomy;

    EconomySpike(JavaPlugin plugin, Report report) {
        this.plugin = plugin;
        this.report = report;
    }

    void run() {
        report.section("S5 economy");
        for (String name : new String[] {"Vault", "VaultUnlocked", "Essentials"}) {
            var p = Bukkit.getPluginManager().getPlugin(name);
            report.line("plugin " + name + " = " + (p == null ? "absent" : p.getPluginMeta().getVersion() + " enabled=" + p.isEnabled()));
        }
        RegisteredServiceProvider<Economy> rsp;
        try {
            rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        } catch (Throwable t) {
            report.line("Economy service lookup ERROR " + t);
            return;
        }
        if (rsp == null) {
            report.line("Economy provider: NONE registered");
            return;
        }
        Economy eco = rsp.getProvider();
        report.line("Economy provider: " + eco.getName() + " from plugin " + rsp.getPlugin().getName() + " priority=" + rsp.getPriority());
        OfflinePlayer acc = Bukkit.getOfflinePlayer(TEST_ACCOUNT);
        call("fractionalDigits", () -> eco.fractionalDigits());
        call("currencyNamePlural", eco::currencyNamePlural);
        call("hasAccount(before)", () -> eco.hasAccount(acc));
        call("createPlayerAccount", () -> eco.createPlayerAccount(acc));
        call("hasAccount(after)", () -> eco.hasAccount(acc));
        call("getBalance", () -> eco.getBalance(acc));
        call("deposit 100.25", () -> describe(eco.depositPlayer(acc, 100.25)));
        call("withdraw 50.10", () -> describe(eco.withdrawPlayer(acc, 50.10)));
        call("getBalance", () -> eco.getBalance(acc));
        call("withdraw 1e9 (insufficient)", () -> describe(eco.withdrawPlayer(acc, 1e9)));
        call("deposit -5 (negative)", () -> describe(eco.depositPlayer(acc, -5)));
        call("withdraw NaN", () -> describe(eco.withdrawPlayer(acc, Double.NaN)));
        call("getBalance", () -> eco.getBalance(acc));

        double before = eco.getBalance(acc);
        long[] dep = new long[200];
        long[] wd = new long[200];
        int failures = 0;
        for (int i = 0; i < 200; i++) {
            long t0 = System.nanoTime();
            EconomyResponse d = eco.depositPlayer(acc, 0.01);
            long t1 = System.nanoTime();
            EconomyResponse w = eco.withdrawPlayer(acc, 0.01);
            long t2 = System.nanoTime();
            dep[i] = t1 - t0;
            wd[i] = t2 - t1;
            if (!d.transactionSuccess() || !w.transactionSuccess()) {
                failures++;
            }
        }
        double after = eco.getBalance(acc);
        report.line("timing deposit " + Stats.summarize(dep));
        report.line("timing withdraw " + Stats.summarize(wd));
        report.line("200x(+0.01,-0.01) failures=" + failures + " balanceBefore=" + before + " balanceAfter=" + after
                + " drift=" + (after - before));
    }

    void testEconomy(String mode) {
        report.section("S5 test-economy mode=" + mode);
        Map<String, Double> balances = new ConcurrentHashMap<>();
        testEconomy = (Economy) Proxy.newProxyInstance(Economy.class.getClassLoader(), new Class<?>[] {Economy.class},
                (proxy, method, args) -> {
                    String key = args != null && args.length > 0 ? String.valueOf(args[0]) : "";
                    switch (method.getName()) {
                        case "getName": return "SpikeTestEconomy";
                        case "isEnabled": case "hasAccount": case "createPlayerAccount": return true;
                        case "fractionalDigits": return 2;
                        case "getBalance": return balances.getOrDefault(key, 1000.0);
                        case "has": return balances.getOrDefault(key, 1000.0) >= (double) args[args.length - 1];
                        case "withdrawPlayer": case "depositPlayer": {
                            double amount = (double) args[args.length - 1];
                            double sign = method.getName().equals("withdrawPlayer") ? -1 : 1;
                            if (mode.equals("throw-before")) {
                                throw new IllegalStateException("spike: provider failed before applying");
                            }
                            double next = balances.getOrDefault(key, 1000.0) + sign * amount;
                            balances.put(key, next);
                            if (mode.equals("throw-after")) {
                                throw new IllegalStateException("spike: provider failed after applying");
                            }
                            return new EconomyResponse(amount, next, EconomyResponse.ResponseType.SUCCESS, null);
                        }
                        default:
                            Class<?> r = method.getReturnType();
                            if (r == boolean.class) return false;
                            if (r == double.class) return 0.0;
                            if (r == int.class) return 0;
                            if (r == EconomyResponse.class) return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "spike");
                            return null;
                    }
                });
        Bukkit.getServicesManager().register(Economy.class, testEconomy, plugin, ServicePriority.Highest);
        try {
            Economy eco = Bukkit.getServicesManager().getRegistration(Economy.class).getProvider();
            OfflinePlayer acc = Bukkit.getOfflinePlayer(TEST_ACCOUNT);
            report.line("resolved provider=" + eco.getName() + " balanceBefore=" + eco.getBalance(acc));
            try {
                EconomyResponse r = eco.withdrawPlayer(acc, 10);
                report.line("withdraw returned " + describe(r));
            } catch (Throwable t) {
                report.line("withdraw THREW " + t.getClass().getSimpleName() + ": " + t.getMessage()
                        + " -> caller cannot tell whether it applied; balanceNow=" + eco.getBalance(acc));
            }
        } finally {
            Bukkit.getServicesManager().unregister(Economy.class, testEconomy);
            report.line("test economy unregistered");
        }
    }

    private static String describe(EconomyResponse r) {
        return "type=" + r.type + " success=" + r.transactionSuccess() + " amount=" + r.amount + " balance=" + r.balance
                + " error=" + r.errorMessage;
    }

    private void call(String label, java.util.concurrent.Callable<Object> c) {
        try {
            report.line(label + " -> " + c.call());
        } catch (Throwable t) {
            report.line(label + " THREW " + t);
        }
    }
}
