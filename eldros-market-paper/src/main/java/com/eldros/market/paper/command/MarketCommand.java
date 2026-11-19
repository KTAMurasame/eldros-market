package com.eldros.market.paper.command;

import com.eldros.market.api.model.OrderSide;
import com.eldros.market.core.domain.Money;
import com.eldros.market.core.service.AmountParser;
import com.eldros.market.core.storage.jdbc.JdbcJournal.MailboxRow;
import com.eldros.market.core.storage.jdbc.JdbcLedger.OrderRow;
import com.eldros.market.paper.MarketRuntime;
import com.eldros.market.paper.action.MarketActions;
import com.eldros.market.paper.gui.Menus;
import com.eldros.market.paper.i18n.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * {@code /market} (aliases {@code /pazar}, {@code /em}) with English and Turkish subcommand names. Without
 * arguments it opens the menu. All state changes go through {@link MarketActions}, shared with the menus.
 */
public final class MarketCommand implements TabExecutor {

    private enum Sub { HELP, SELL, BUY, ORDERS, CANCEL, MAILBOX, CLAIM, LANG, ALERTS }

    private static final Map<String, Sub> SUBS = Map.ofEntries(
            Map.entry("help", Sub.HELP), Map.entry("yardım", Sub.HELP), Map.entry("yardim", Sub.HELP),
            Map.entry("sell", Sub.SELL), Map.entry("sat", Sub.SELL),
            Map.entry("buy", Sub.BUY), Map.entry("al", Sub.BUY),
            Map.entry("orders", Sub.ORDERS), Map.entry("emirler", Sub.ORDERS),
            Map.entry("cancel", Sub.CANCEL), Map.entry("iptal", Sub.CANCEL),
            Map.entry("mailbox", Sub.MAILBOX), Map.entry("posta", Sub.MAILBOX),
            Map.entry("claim", Sub.CLAIM), Map.entry("topla", Sub.CLAIM),
            Map.entry("lang", Sub.LANG), Map.entry("dil", Sub.LANG),
            Map.entry("alerts", Sub.ALERTS), Map.entry("bildirim", Sub.ALERTS));

    private static final List<String> EN = List.of("help", "sell", "buy", "orders", "cancel", "mailbox", "claim", "lang", "alerts");
    private static final List<String> TR = List.of("yardım", "sat", "al", "emirler", "iptal", "posta", "topla", "dil", "bildirim");

    private final MarketRuntime rt;
    private final Messages msg;
    private final MarketActions actions;
    private final Menus menus;

    public MarketCommand(MarketActions actions, Menus menus) {
        this.actions = actions;
        this.rt = actions.runtime();
        this.msg = rt.messages();
        this.menus = menus;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) {
                menus.openMain(player);
            } else {
                msg.sendList(sender, "help");
            }
            return true;
        }
        Sub sub = SUBS.get(args[0].toLowerCase(Locale.ROOT));
        if (sub == null) {
            msg.send(sender, "general.unknown-subcommand");
            return true;
        }
        if (sub == Sub.HELP) {
            msg.sendList(sender, "help");
            return true;
        }
        if (!(sender instanceof Player player)) {
            msg.send(sender, "general.player-only");
            return true;
        }
        switch (sub) {
            case LANG -> actions.setLanguage(player, args.length >= 2 ? args[1] : null);
            case SELL -> sell(player, args);
            case BUY -> buy(player, args);
            case ORDERS -> orders(player);
            case CANCEL -> cancel(player, args);
            case MAILBOX -> mailbox(player);
            case CLAIM -> claim(player, args);
            case ALERTS -> msg.send(player, rt.toggleAlerts(player) ? "alerts.muted" : "alerts.unmuted");
            default -> msg.send(player, "general.unknown-subcommand");
        }
        return true;
    }

    // --- sell / buy ----------------------------------------------------------------------------------

    private record Amounts(long qty, Money price) {
    }

    private Optional<Amounts> amounts(Player p, String[] args) {
        if (args.length < 3) {
            msg.sendList(p, "help");
            return Optional.empty();
        }
        AmountParser.Result<Long> q = AmountParser.parseQuantity(args[1]);
        if (!q.isOk()) {
            actions.validation(p, q.error());
            return Optional.empty();
        }
        AmountParser.Result<Money> m = AmountParser.parseMoney(args[2], rt.settings().scale());
        if (!m.isOk()) {
            actions.validation(p, m.error());
            return Optional.empty();
        }
        return Optional.of(new Amounts(q.value(), m.value()));
    }

    /**
     * {@code sell <qty> <price>} places a sell order; {@code sell} alone sells the stack in hand right now to the best
     * buy orders; {@code sell all} does that for everything in the inventory. The instant forms confirm in a menu first.
     */
    private void sell(Player p, String[] args) {
        if (args.length == 1) {
            sellHandNow(p);
            return;
        }
        if (args.length == 2 && (args[1].equalsIgnoreCase("all") || args[1].equalsIgnoreCase("hepsi"))) {
            if (actions.trading(p) && actions.permitted(p, "eldrosmarket.sell")) {
                menus.openSellAll(p);
            }
            return;
        }
        amounts(p, args).ifPresent(a -> actions.place(p, OrderSide.SELL, p.getInventory().getItemInMainHand(), a.qty(), a.price()));
    }

    private void sellHandNow(Player p) {
        if (!actions.trading(p) || !actions.permitted(p, "eldrosmarket.sell")) {
            return;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            msg.send(p, "instant.empty-hand");
            return;
        }
        Optional<com.eldros.market.api.model.TemplateId> template = rt.templates().find(hand);
        if (template.isEmpty()) {
            msg.send(p, "instant.no-buyers", Messages.component("item", hand.displayName()));
            return;
        }
        menus.openInstant(p, hand, template.get(), OrderSide.SELL, hand.getAmount());
    }

    private void buy(Player p, String[] args) {
        Optional<Amounts> a = amounts(p, args);
        if (a.isEmpty()) {
            return;
        }
        ItemStack item;
        if (args.length >= 4) {
            Material m = Material.matchMaterial(args[3]);
            if (m == null || !m.isItem() || m.isAir()) {
                msg.send(p, "item.unknown", Messages.text("name", args[3]));
                return;
            }
            item = new ItemStack(m);
        } else {
            item = p.getInventory().getItemInMainHand();
        }
        actions.place(p, OrderSide.BUY, item, a.get().qty(), a.get().price());
    }

    // --- orders / cancel ---------------------------------------------------------------------------------

    private void orders(Player p) {
        if (!actions.open(p)) {
            return;
        }
        actions.activeOrders(p).thenAccept(list -> {
            if (!p.isOnline()) {
                return;
            }
            if (list.isEmpty()) {
                msg.send(p, "orders.empty");
                return;
            }
            String lang = msg.languageOf(p);
            msg.send(p, "orders.header");
            for (OrderRow o : list) {
                Component side = msg.render(lang, o.side() == OrderSide.BUY ? "orders.side-buy" : "orders.side-sell");
                p.sendMessage(msg.render(lang, "orders.entry", Messages.text("id", o.id()), Messages.component("side", side),
                        Messages.text("remaining", o.quantity() - o.filled()), Messages.text("qty", o.quantity()),
                        Messages.component("item", rt.templates().name(o.template())),
                        msg.money(lang, "price", o.unitPrice(), rt.settings().scale())));
            }
        }).exceptionally(e -> {
            actions.fail(p, e);
            return null;
        });
    }

    private void cancel(Player p, String[] args) {
        Long id = args.length >= 2 ? parseId(args[1]) : null;
        if (id == null) {
            msg.sendList(p, "help");
            return;
        }
        actions.cancel(p, id);
    }

    private static Long parseId(String s) {
        String t = s.startsWith("#") ? s.substring(1) : s;
        try {
            long v = Long.parseLong(t);
            return v > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // --- mailbox / claim -----------------------------------------------------------------------------

    private void mailbox(Player p) {
        if (!actions.open(p)) {
            return;
        }
        actions.mailbox(p).thenAccept(rows -> {
            if (!p.isOnline()) {
                return;
            }
            if (rows.isEmpty()) {
                msg.send(p, "mailbox.empty");
                return;
            }
            String lang = msg.languageOf(p);
            msg.send(p, "mailbox.header");
            for (MailboxRow r : rows) {
                p.sendMessage(entry(lang, r));
            }
        }).exceptionally(e -> {
            actions.fail(p, e);
            return null;
        });
    }

    private Component entry(String lang, MailboxRow r) {
        String reasonText = reasonText(lang, r.reason());
        if ("MONEY".equals(r.kind())) {
            return msg.render(lang, "mailbox.entry-money", Messages.text("id", r.id()),
                    msg.money(lang, "amount", r.amount(), rt.settings().scale()), Messages.text("reason", reasonText));
        }
        return msg.render(lang, "mailbox.entry-item", Messages.text("id", r.id()), Messages.text("qty", r.qty()),
                Messages.component("item", rt.templates().name(r.template())), Messages.text("reason", reasonText));
    }

    private String reasonText(String lang, String reason) {
        String key = "mailbox.reasons." + reason;
        return msg.has(lang, key) ? msg.raw(lang, key) : reason;
    }

    /** {@code claim} = first entry, {@code claim all}/{@code hepsi} = everything that fits, {@code claim <id>} = that entry. */
    private void claim(Player p, String[] args) {
        String arg = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        boolean all = arg.equals("all") || arg.equals("hepsi");
        Long only = all || arg.isEmpty() ? null : parseId(arg);
        if (!all && !arg.isEmpty() && only == null) {
            msg.sendList(p, "help");
            return;
        }
        if (!actions.open(p)) {
            return;
        }
        actions.mailbox(p).thenAccept(rows -> {
            if (!p.isOnline()) {
                return;
            }
            List<MailboxRow> todo = new ArrayList<>(rows);
            if (only != null) {
                todo.removeIf(r -> r.id() != only);
            } else if (!all && todo.size() > 1) {
                todo = new ArrayList<>(todo.subList(0, 1));
            }
            actions.claim(p, todo);
        }).exceptionally(e -> {
            actions.fail(p, e);
            return null;
        });
    }

    // --- tab completion ------------------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        // Suggestions follow the player's language, not the alias typed: every alias accepts both languages' words,
        // and a player who switched with /pazar dil must see the new language right away.
        boolean turkish = "tr_TR".equals(msg.languageOf(sender));
        if (args.length == 1) {
            return prefixed(turkish ? TR : EN, args[0]);
        }
        Sub sub = SUBS.get(args[0].toLowerCase(Locale.ROOT));
        if (sub == null) {
            return List.of();
        }
        return switch (sub) {
            case LANG -> args.length == 2 ? prefixed(new ArrayList<>(msg.available()), args[1]) : List.of();
            case CLAIM, SELL -> args.length == 2 ? prefixed(List.of(turkish ? "hepsi" : "all"), args[1]) : List.of();
            case BUY -> args.length == 4 ? materials(args[3]) : List.of();
            default -> List.of();
        };
    }

    private static List<String> prefixed(List<String> options, String typed) {
        String t = typed.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(t)).toList();
    }

    private static List<String> materials(String typed) {
        String t = typed.toLowerCase(Locale.ROOT);
        Function<Material, String> key = m -> m.getKey().getKey();
        return Arrays.stream(Material.values())
                .filter(m -> !m.isLegacy() && m.isItem() && !m.isAir())
                .map(key)
                .filter(k -> k.startsWith(t))
                .limit(40)
                .toList();
    }
}
