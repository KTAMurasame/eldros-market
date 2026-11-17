package com.eldros.market.paper.command;

import com.eldros.market.api.model.OrderSide;
import com.eldros.market.api.model.TemplateId;
import com.eldros.market.core.domain.Money;
import com.eldros.market.core.domain.NewOrder;
import com.eldros.market.core.domain.PlayerId;
import com.eldros.market.core.engine.MatchPlan;
import com.eldros.market.core.market.OpResult;
import com.eldros.market.core.service.AmountParser;
import com.eldros.market.core.service.ValidationCode;
import com.eldros.market.core.service.ValidationResult;
import com.eldros.market.core.storage.jdbc.JdbcJournal.MailboxRow;
import com.eldros.market.core.storage.jdbc.JdbcLedger.OrderRow;
import com.eldros.market.paper.MarketRuntime;
import com.eldros.market.paper.i18n.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.logging.Level;

/** {@code /market} (aliases {@code /pazar}, {@code /em}) with English and Turkish subcommand names. */
public final class MarketCommand implements TabExecutor {

    private enum Sub { HELP, SELL, BUY, ORDERS, CANCEL, MAILBOX, CLAIM, LANG }

    private static final Map<String, Sub> SUBS = Map.ofEntries(
            Map.entry("help", Sub.HELP), Map.entry("yardım", Sub.HELP), Map.entry("yardim", Sub.HELP),
            Map.entry("sell", Sub.SELL), Map.entry("sat", Sub.SELL),
            Map.entry("buy", Sub.BUY), Map.entry("al", Sub.BUY),
            Map.entry("orders", Sub.ORDERS), Map.entry("emirler", Sub.ORDERS),
            Map.entry("cancel", Sub.CANCEL), Map.entry("iptal", Sub.CANCEL),
            Map.entry("mailbox", Sub.MAILBOX), Map.entry("posta", Sub.MAILBOX),
            Map.entry("claim", Sub.CLAIM), Map.entry("topla", Sub.CLAIM),
            Map.entry("lang", Sub.LANG), Map.entry("dil", Sub.LANG));

    private static final List<String> EN = List.of("help", "sell", "buy", "orders", "cancel", "mailbox", "claim", "lang");
    private static final List<String> TR = List.of("yardım", "sat", "al", "emirler", "iptal", "posta", "topla", "dil");

    private final MarketRuntime rt;
    private final Messages msg;

    public MarketCommand(MarketRuntime runtime) {
        this.rt = runtime;
        this.msg = runtime.messages();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Sub sub = args.length == 0 ? Sub.HELP : SUBS.get(args[0].toLowerCase(Locale.ROOT));
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
        if (sub == Sub.LANG) {
            lang(player, args);
            return true;
        }
        if (!rt.isLoaded()) {
            msg.send(player, "general.loading");
            return true;
        }
        if (rt.safeMode().isActive()) {
            msg.send(player, "general.safe-mode");
            return true;
        }
        switch (sub) {
            case SELL -> {
                if (permitted(player, "eldrosmarket.sell")) {
                    locked(player, () -> sell(player, args));
                }
            }
            case BUY -> {
                if (permitted(player, "eldrosmarket.buy")) {
                    locked(player, () -> buy(player, args));
                }
            }
            case ORDERS -> orders(player);
            case CANCEL -> locked(player, () -> cancel(player, args));
            case MAILBOX -> mailbox(player);
            case CLAIM -> locked(player, () -> claim(player, args));
            default -> msg.send(player, "general.unknown-subcommand");
        }
        return true;
    }

    private boolean permitted(Player p, String perm) {
        if (p.hasPermission(perm)) {
            return true;
        }
        msg.send(p, "general.no-permission");
        return false;
    }

    /** One action per player at a time; the lock is released when the action's future completes. */
    private void locked(Player p, java.util.function.Supplier<CompletableFuture<?>> action) {
        if (!rt.tryLock(p.getUniqueId())) {
            msg.send(p, "general.busy");
            return;
        }
        CompletableFuture<?> f;
        try {
            f = action.get();
        } catch (RuntimeException e) {
            rt.unlock(p.getUniqueId());
            throw e;
        }
        f.whenComplete((r, e) -> {
            rt.unlock(p.getUniqueId());
            if (e != null) {
                rt.main().execute(() -> fail(p, e));
            }
        });
    }

    private void fail(Player p, Throwable e) {
        rt.plugin().getLogger().log(Level.SEVERE, "Market action of " + p.getName() + " failed", e);
        if (p.isOnline()) {
            msg.send(p, "general.error");
        }
    }

    private static CompletableFuture<Void> done() {
        return CompletableFuture.completedFuture(null);
    }

    // --- sell / buy --------------------------------------------------------------------------------

    private record Amounts(long qty, Money price) {
    }

    private Optional<Amounts> amounts(Player p, String[] args) {
        if (args.length < 3) {
            msg.sendList(p, "help");
            return Optional.empty();
        }
        AmountParser.Result<Long> q = AmountParser.parseQuantity(args[1]);
        if (!q.isOk()) {
            validation(p, q.error());
            return Optional.empty();
        }
        AmountParser.Result<Money> m = AmountParser.parseMoney(args[2], rt.settings().scale());
        if (!m.isOk()) {
            validation(p, m.error());
            return Optional.empty();
        }
        return Optional.of(new Amounts(q.value(), m.value()));
    }

    private void validation(Player p, ValidationCode code) {
        String lang = msg.languageOf(p);
        var s = rt.settings();
        TagResolver max = switch (code) {
            case QUANTITY_TOO_LARGE -> Messages.text("max", s.limits().maxQuantityPerOrder());
            case PRICE_TOO_HIGH -> msg.money(lang, "max", s.limits().maxUnitPrice(), s.scale());
            case TOO_MANY_ORDERS -> Messages.text("max", orderLimit(p));
            default -> Messages.text("max", "");
        };
        msg.send(p, "validation." + code.name().toLowerCase(Locale.ROOT), max,
                msg.money(lang, "min", s.limits().minUnitPrice(), s.scale()));
    }

    private CompletableFuture<?> sell(Player p, String[] args) {
        Optional<Amounts> a = amounts(p, args);
        if (a.isEmpty()) {
            return done();
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        Optional<String> refusal = rt.filter().refusal(hand);
        if (refusal.isPresent()) {
            msg.send(p, refusal.get());
            return done();
        }
        if (!rt.tryItemOperation()) {
            msg.send(p, "general.busy");
            return done();
        }
        return place(p, OrderSide.SELL, hand.clone(), a.get());
    }

    private CompletableFuture<?> buy(Player p, String[] args) {
        Optional<Amounts> a = amounts(p, args);
        if (a.isEmpty()) {
            return done();
        }
        ItemStack item;
        if (args.length >= 4) {
            Material m = Material.matchMaterial(args[3]);
            if (m == null || !m.isItem() || m.isAir()) {
                msg.send(p, "item.unknown", Messages.text("name", args[3]));
                return done();
            }
            item = new ItemStack(m);
        } else {
            item = p.getInventory().getItemInMainHand().clone();
        }
        Optional<String> refusal = rt.filter().refusal(item);
        if (refusal.isPresent()) {
            msg.send(p, refusal.get());
            return done();
        }
        return place(p, OrderSide.BUY, item, a.get());
    }

    private CompletableFuture<?> place(Player p, OrderSide side, ItemStack item, Amounts a) {
        PlayerId owner = new PlayerId(p.getUniqueId());
        int limit = orderLimit(p);
        return rt.onEngine(() -> rt.tx().inTransaction(c -> rt.ledger().countActiveOrders(c, owner)))
                .thenComposeAsync(active -> {
                    ValidationResult v = rt.limits().validate(a.price(), a.qty(), active, limit);
                    if (!v.isOk()) {
                        validation(p, v.code());
                        return done();
                    }
                    return rt.resolveTemplate(item).thenComposeAsync(template -> {
                        Long expires = rt.settings().expiryEnabled() ? rt.now() + rt.settings().expiryDays() * 86_400_000L : null;
                        NewOrder spec = new NewOrder(owner, side, template, a.price(), a.qty(), expires);
                        CompletableFuture<OpResult> op = side == OrderSide.BUY ? rt.ops().buy(spec) : rt.ops().sell(spec);
                        return op.thenAcceptAsync(r -> placed(p, side, template, a, r), rt.main());
                    }, rt.main());
                }, rt.main());
    }

    private void placed(Player p, OrderSide side, TemplateId template, Amounts a, OpResult r) {
        if (!p.isOnline()) {
            return;
        }
        if (r.status() != OpResult.Status.DONE) {
            reason(p, r);
            return;
        }
        String lang = msg.languageOf(p);
        var scale = rt.settings().scale();
        msg.send(p, side == OrderSide.BUY ? "order.created-buy" : "order.created-sell",
                Messages.text("qty", a.qty()), Messages.component("item", rt.templates().name(template)),
                msg.money(lang, "price", a.price(), scale), msg.money(lang, "total", a.price().times(a.qty()), scale));
        MatchPlan plan = r.plan();
        long filled = plan == null ? 0 : plan.fills().stream().mapToLong(f -> f.quantity()).sum();
        if (filled > 0) {
            msg.send(p, "order.filled-now", Messages.text("filled", filled), Messages.text("qty", a.qty()));
        }
    }

    private void reason(Player p, OpResult r) {
        if (r.status() == OpResult.Status.QUARANTINED) {
            msg.send(p, "reason.quarantined");
        } else {
            msg.send(p, "reason." + r.reason().name().toLowerCase(Locale.ROOT));
        }
    }

    /** Highest {@code eldrosmarket.limit.orders.<n>} the player has, else the configured default. */
    private int orderLimit(Player p) {
        int best = -1;
        for (PermissionAttachmentInfo info : p.getEffectivePermissions()) {
            String perm = info.getPermission();
            if (info.getValue() && perm.startsWith("eldrosmarket.limit.orders.")) {
                try {
                    best = Math.max(best, Integer.parseInt(perm.substring("eldrosmarket.limit.orders.".length())));
                } catch (NumberFormatException ignored) {
                    // not a number: ignore
                }
            }
        }
        return best >= 0 ? best : rt.settings().defaultMaxActiveOrders();
    }

    // --- orders / cancel ------------------------------------------------------------------------------

    private void orders(Player p) {
        PlayerId owner = new PlayerId(p.getUniqueId());
        rt.onEngine(() -> rt.tx().inTransaction(c -> rt.ledger().activeOrdersOf(c, owner))).thenAcceptAsync(list -> {
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
        }, rt.main()).exceptionally(e -> {
            rt.main().execute(() -> fail(p, e));
            return null;
        });
    }

    private CompletableFuture<?> cancel(Player p, String[] args) {
        Long id = args.length >= 2 ? parseId(args[1]) : null;
        if (id == null) {
            msg.sendList(p, "help");
            return done();
        }
        return rt.ops().cancel(new PlayerId(p.getUniqueId()), id).thenAcceptAsync(r -> {
            if (r.status() == OpResult.Status.DONE) {
                msg.send(p, "order.cancelled", Messages.text("id", id));
            } else {
                reason(p, r);
            }
        }, rt.main());
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

    private CompletableFuture<List<MailboxRow>> mailboxRows(Player p) {
        PlayerId owner = new PlayerId(p.getUniqueId());
        return rt.onEngine(() -> rt.tx().inTransaction(c -> rt.journal().availableFor(c, owner)));
    }

    private void mailbox(Player p) {
        mailboxRows(p).thenAcceptAsync(rows -> {
            if (rows.isEmpty()) {
                msg.send(p, "mailbox.empty");
                return;
            }
            String lang = msg.languageOf(p);
            msg.send(p, "mailbox.header");
            for (MailboxRow r : rows) {
                p.sendMessage(entry(lang, r));
            }
        }, rt.main()).exceptionally(e -> {
            rt.main().execute(() -> fail(p, e));
            return null;
        });
    }

    private Component entry(String lang, MailboxRow r) {
        String reasonKey = "mailbox.reasons." + r.reason();
        String reasonText = msg.has(lang, reasonKey) ? msg.raw(lang, reasonKey) : r.reason();
        if ("MONEY".equals(r.kind())) {
            return msg.render(lang, "mailbox.entry-money", Messages.text("id", r.id()),
                    msg.money(lang, "amount", r.amount(), rt.settings().scale()), Messages.text("reason", reasonText));
        }
        return msg.render(lang, "mailbox.entry-item", Messages.text("id", r.id()), Messages.text("qty", r.qty()),
                Messages.component("item", rt.templates().name(r.template())), Messages.text("reason", reasonText));
    }

    /** {@code claim} = first entry, {@code claim all}/{@code hepsi} = everything that fits, {@code claim <id>} = that entry. */
    private CompletableFuture<Void> claim(Player p, String[] args) {
        String arg = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        boolean all = arg.equals("all") || arg.equals("hepsi");
        Long only = all || arg.isEmpty() ? null : parseId(arg);
        if (!all && !arg.isEmpty() && only == null) {
            msg.sendList(p, "help");
            return done();
        }
        return mailboxRows(p).thenComposeAsync(rows -> {
            List<MailboxRow> todo = new ArrayList<>(rows);
            if (only != null) {
                todo.removeIf(r -> r.id() != only);
            } else if (!all && todo.size() > 1) {
                todo = new ArrayList<>(todo.subList(0, 1));
            }
            if (todo.isEmpty()) {
                msg.send(p, "claim.nothing");
                return done();
            }
            return claimNext(p, todo, 0);
        }, rt.main());
    }

    private CompletableFuture<Void> claimNext(Player p, List<MailboxRow> rows, int index) {
        if (index >= rows.size() || !p.isOnline()) {
            return done();
        }
        MailboxRow row = rows.get(index);
        boolean item = "ITEM".equals(row.kind());
        if (item && !rt.tryItemOperation()) {
            msg.send(p, "general.busy");
            return done();
        }
        return rt.ops().claim(new PlayerId(p.getUniqueId()), row.id()).thenComposeAsync(r -> {
            if (r.status() != OpResult.Status.DONE) {
                reason(p, r);
                return done();
            }
            String lang = msg.languageOf(p);
            if (!item) {
                msg.send(p, "claim.money", msg.money(lang, "amount", row.amount(), rt.settings().scale()));
                return claimNext(p, rows, index + 1);
            }
            msg.send(p, "claim.items", Messages.text("qty", r.quantity()), Messages.component("item", rt.templates().name(row.template())));
            if (r.quantity() < row.qty()) {
                msg.send(p, "claim.partial", Messages.text("qty", r.quantity()));
                return done(); // inventory is full; the rest stays
            }
            return claimNext(p, rows, index + 1);
        }, rt.main());
    }

    // --- language ------------------------------------------------------------------------------------

    private void lang(Player p, String[] args) {
        String code = args.length >= 2 ? msg.canonical(args[1]) : null;
        if (code == null) {
            msg.send(p, "lang.unknown", Messages.text("list", String.join(", ", msg.available())));
            return;
        }
        msg.setPreference(p.getUniqueId(), code);
        msg.send(p, "lang.set", Messages.text("name", msg.raw(code, "language-name")));
        PlayerId id = new PlayerId(p.getUniqueId());
        String name = p.getName();
        rt.onEngine(() -> rt.tx().inTransaction(c -> {
            rt.ledger().setPlayerLocale(c, id, name, code);
            return null;
        }));
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
            case CLAIM -> args.length == 2 ? prefixed(List.of(turkish ? "hepsi" : "all"), args[1]) : List.of();
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
