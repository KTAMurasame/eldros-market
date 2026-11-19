package com.eldros.market.paper.command;

import com.eldros.market.core.journal.JournalEntry;
import com.eldros.market.core.storage.jdbc.LedgerInvariants;
import com.eldros.market.paper.MarketRuntime;
import com.eldros.market.paper.i18n.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

/** {@code /marketadmin} (alias {@code /pazaradmin}): reload, verify, safe mode, trading freeze and quarantine resolution. */
public final class AdminCommand implements TabExecutor {

    private final MarketRuntime rt;
    private final Messages msg;

    public AdminCommand(MarketRuntime runtime) {
        this.rt = runtime;
        this.msg = runtime.messages();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("eldrosmarket.admin")) {
            msg.send(sender, "general.no-permission");
            return true;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> reload(sender);
            case "verify" -> verify(sender);
            case "safemode" -> safeMode(sender, args);
            case "recovery" -> recovery(sender, args);
            case "freeze", "dondur" -> freeze(sender, args);
            default -> msg.send(sender, "admin.usage");
        }
        return true;
    }

    private void reload(CommandSender sender) {
        try {
            rt.plugin().reloadSettings();
            msg.send(sender, "admin.reloaded");
        } catch (RuntimeException e) {
            msg.send(sender, "admin.reload-failed", Messages.text("error", e.getMessage()));
        }
    }

    private void verify(CommandSender sender) {
        rt.onEngine(() -> rt.tx().inTransaction(LedgerInvariants::check)).whenCompleteAsync((problems, error) -> {
            if (error != null) {
                rt.plugin().getLogger().log(Level.SEVERE, "verify failed", error);
                msg.send(sender, "general.error");
                return;
            }
            if (problems.isEmpty()) {
                msg.send(sender, "admin.verify-ok");
                return;
            }
            msg.send(sender, "admin.verify-fail", Messages.text("count", problems.size()));
            problems.forEach(line -> msg.send(sender, "admin.verify-line", Messages.text("line", line)));
        }, rt.main());
    }

    private void safeMode(CommandSender sender, String[] args) {
        String what = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (what) {
            case "on" -> {
                rt.safeMode().enter("admin " + sender.getName());
                msg.send(sender, "admin.safemode-on");
            }
            case "off" -> rt.leaveSafeMode().whenCompleteAsync((ok, error) -> {
                if (error != null) {
                    rt.plugin().getLogger().log(Level.SEVERE, "Leaving safe mode failed", error);
                    msg.send(sender, "admin.safemode-still");
                } else {
                    msg.send(sender, Boolean.TRUE.equals(ok) ? "admin.safemode-off" : "admin.safemode-still");
                }
            }, rt.main());
            default -> msg.send(sender, "admin.safemode-status", Messages.text("state",
                    rt.safeMode().isActive() ? "ON (" + rt.safeMode().reason() + ")" : "OFF"));
        }
    }

    /** {@code freeze [on|off]}: stops new orders, instant trades and edits; cancelling and the mailbox keep working. */
    private void freeze(CommandSender sender, String[] args) {
        String what = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        boolean on;
        switch (what) {
            case "on", "aç", "ac" -> on = true;
            case "off", "kapat" -> on = false;
            default -> {
                msg.send(sender, rt.isFrozen() ? "admin.freeze-status-on" : "admin.freeze-status-off");
                return;
            }
        }
        try {
            rt.setFrozen(on, sender.getName());
        } catch (java.io.IOException e) {
            rt.plugin().getLogger().log(Level.SEVERE, "Could not store the trading freeze", e);
            msg.send(sender, "general.error");
            return;
        }
        msg.send(sender, on ? "admin.freeze-on" : "admin.freeze-off");
        String key = on ? "general.frozen-broadcast" : "general.unfrozen-broadcast";
        for (var player : Bukkit.getOnlinePlayers()) {
            if (player != sender && player.hasPermission("eldrosmarket.use")) {
                msg.send(player, key);
            }
        }
    }

    private void recovery(CommandSender sender, String[] args) {
        String what = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        if (what.equals("list")) {
            rt.onEngine(() -> rt.tx().inTransaction(c -> rt.journal().byState(c, JournalEntry.State.QUARANTINED)))
                    .whenCompleteAsync((list, error) -> {
                        if (error != null) {
                            msg.send(sender, "general.error");
                            return;
                        }
                        if (list.isEmpty()) {
                            msg.send(sender, "admin.recovery-none");
                            return;
                        }
                        msg.send(sender, "admin.recovery-header");
                        list.forEach(e -> msg.send(sender, "admin.recovery-entry", Messages.text("op", e.opId()),
                                Messages.text("type", e.type().name()), Messages.text("player", playerName(e)),
                                Messages.component("amount", amount(sender, e)),
                                Messages.text("reason", e.quarantineReason() == null ? "?" : e.quarantineReason())));
                    }, rt.main());
            return;
        }
        if (what.equals("resolve") && args.length >= 4) {
            String op = args[2];
            String decision = args[3].toLowerCase(Locale.ROOT);
            if (!decision.equals("applied") && !decision.equals("not-applied")) {
                msg.send(sender, "admin.usage");
                return;
            }
            Long qty = null;
            if (args.length >= 5) {
                try {
                    qty = Long.parseLong(args[4]);
                } catch (NumberFormatException e) {
                    msg.send(sender, "admin.usage");
                    return;
                }
            }
            boolean applied = decision.equals("applied");
            Long q = qty;
            rt.onEngine(() -> {
                rt.recovery().resolve(op, applied, q, sender.getName());
                return null;
            }).whenCompleteAsync((ignored, error) -> {
                if (error != null) {
                    Throwable cause = error.getCause() != null ? error.getCause() : error;
                    msg.send(sender, "admin.resolve-failed", Messages.text("error", String.valueOf(cause.getMessage())));
                } else {
                    msg.send(sender, "admin.resolved", Messages.text("op", op), Messages.text("decision", decision));
                }
            }, rt.main());
            return;
        }
        msg.send(sender, "admin.usage");
    }

    private static String playerName(JournalEntry e) {
        String name = Bukkit.getOfflinePlayer(e.player().value()).getName();
        return name != null ? name : e.player().value().toString();
    }

    private Component amount(CommandSender sender, JournalEntry e) {
        String lang = msg.languageOf(sender);
        if (e.amount() != null) {
            return msg.render(lang, "format.money", msg.money(lang, "amount", e.amount(), rt.settings().scale()));
        }
        long qty = e.qty() == null ? 0 : e.qty();
        Component item = e.template() == null ? Component.text("?") : rt.templates().name(e.template());
        return Component.text(qty + "× ").append(item);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("eldrosmarket.admin")) {
            return List.of();
        }
        List<String> options = switch (args.length) {
            case 1 -> List.of("reload", "verify", "safemode", "recovery", "freeze");
            case 2 -> switch (args[0].toLowerCase(Locale.ROOT)) {
                case "safemode", "freeze" -> List.of("on", "off");
                case "recovery" -> List.of("list", "resolve");
                default -> List.of();
            };
            case 4 -> args[1].equalsIgnoreCase("resolve") ? List.of("applied", "not-applied") : List.of();
            default -> List.<String>of();
        };
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.startsWith(typed)).toList();
    }
}
