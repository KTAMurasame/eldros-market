package com.eldros.market.paper.action;

import com.eldros.market.api.model.OrderSide;
import com.eldros.market.api.model.TemplateId;
import com.eldros.market.core.domain.Money;
import com.eldros.market.core.domain.NewOrder;
import com.eldros.market.core.domain.PlayerId;
import com.eldros.market.core.engine.BookView;
import com.eldros.market.core.engine.MatchPlan;
import com.eldros.market.core.market.OpResult;
import com.eldros.market.core.service.ValidationCode;
import com.eldros.market.core.service.ValidationResult;
import com.eldros.market.core.storage.jdbc.JdbcJournal.MailboxRow;
import com.eldros.market.core.storage.jdbc.JdbcLedger.OrderRow;
import com.eldros.market.core.storage.jdbc.JdbcLedger.TradeRow;
import com.eldros.market.paper.MarketRuntime;
import com.eldros.market.paper.i18n.Messages;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Player actions shared by the command and the menus, so both follow exactly the same checks: market loaded,
 * safe mode, permission, one action per player, item-operation rate, limits and filter. All methods are called on
 * the main thread; returned futures complete on the main thread.
 */
public final class MarketActions {

    private final MarketRuntime rt;
    private final Messages msg;

    public MarketActions(MarketRuntime runtime) {
        this.rt = runtime;
        this.msg = runtime.messages();
    }

    public MarketRuntime runtime() {
        return rt;
    }

    // --- gating ----------------------------------------------------------------------------------------

    /** Market loaded and not in safe mode; tells the player otherwise. */
    public boolean open(Player p) {
        if (!rt.isLoaded()) {
            msg.send(p, "general.loading");
            return false;
        }
        if (rt.safeMode().isActive()) {
            msg.send(p, "general.safe-mode");
            return false;
        }
        return true;
    }

    public boolean permitted(Player p, String perm) {
        if (p.hasPermission(perm)) {
            return true;
        }
        msg.send(p, "general.no-permission");
        return false;
    }

    /**
     * Runs a state-changing action under the player's lock. The lock is released when the action's future
     * completes; a failure is logged and reported. The returned future never completes exceptionally.
     */
    public CompletableFuture<Boolean> locked(Player p, Supplier<CompletableFuture<Boolean>> action) {
        if (!rt.tryLock(p.getUniqueId())) {
            msg.send(p, "general.busy");
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Boolean> f;
        try {
            f = action.get();
        } catch (RuntimeException e) {
            rt.unlock(p.getUniqueId());
            fail(p, e);
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Boolean> out = new CompletableFuture<>();
        f.whenComplete((ok, e) -> {
            rt.unlock(p.getUniqueId());
            rt.main().execute(() -> {
                if (e != null) {
                    fail(p, e);
                    out.complete(false);
                } else {
                    out.complete(Boolean.TRUE.equals(ok));
                }
            });
        });
        return out;
    }

    public void fail(Player p, Throwable e) {
        rt.plugin().getLogger().log(Level.SEVERE, "Market action of " + p.getName() + " failed", e);
        if (p.isOnline()) {
            msg.send(p, "general.error");
        }
    }

    private static CompletableFuture<Boolean> no() {
        return CompletableFuture.completedFuture(false);
    }

    // --- messages ----------------------------------------------------------------------------------------

    public void validation(Player p, ValidationCode code) {
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

    public void reason(Player p, OpResult r) {
        if (r.status() == OpResult.Status.QUARANTINED) {
            msg.send(p, "reason.quarantined");
        } else {
            msg.send(p, "reason." + r.reason().name().toLowerCase(Locale.ROOT));
        }
    }

    /** Highest {@code eldrosmarket.limit.orders.<n>} the player has, else the configured default. */
    public int orderLimit(Player p) {
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

    // --- orders ------------------------------------------------------------------------------------------

    /**
     * Places an order for {@code item} (a sell takes matching items from the inventory, a buy reserves money).
     * Checks filter, rate, limits; completes with true when the order was created.
     */
    public CompletableFuture<Boolean> place(Player p, OrderSide side, ItemStack item, long qty, Money price) {
        if (!open(p) || !permitted(p, side == OrderSide.BUY ? "eldrosmarket.buy" : "eldrosmarket.sell")) {
            return no();
        }
        Optional<String> refusal = rt.filter().refusal(item);
        if (refusal.isPresent()) {
            msg.send(p, refusal.get());
            return no();
        }
        ItemStack copy = item.clone();
        return locked(p, () -> {
            if (side == OrderSide.SELL && !rt.tryItemOperation()) {
                msg.send(p, "general.busy");
                return no();
            }
            PlayerId owner = new PlayerId(p.getUniqueId());
            int limit = orderLimit(p);
            return rt.onEngine(() -> rt.tx().inTransaction(c -> rt.ledger().countActiveOrders(c, owner)))
                    .thenComposeAsync(active -> {
                        ValidationResult v = rt.limits().validate(price, qty, active, limit);
                        if (!v.isOk()) {
                            validation(p, v.code());
                            return no();
                        }
                        return rt.resolveTemplate(copy).thenComposeAsync(template -> {
                            Long expires = rt.settings().expiryEnabled() ? rt.now() + rt.settings().expiryDays() * 86_400_000L : null;
                            NewOrder spec = new NewOrder(owner, side, template, price, qty, expires);
                            CompletableFuture<OpResult> op = side == OrderSide.BUY ? rt.ops().buy(spec) : rt.ops().sell(spec);
                            return op.thenApplyAsync(r -> placed(p, side, template, qty, price, r), rt.main());
                        }, rt.main());
                    }, rt.main());
        });
    }

    private boolean placed(Player p, OrderSide side, TemplateId template, long qty, Money price, OpResult r) {
        if (!p.isOnline()) {
            return r.status() == OpResult.Status.DONE;
        }
        if (r.status() != OpResult.Status.DONE) {
            reason(p, r);
            return false;
        }
        String lang = msg.languageOf(p);
        var scale = rt.settings().scale();
        msg.send(p, side == OrderSide.BUY ? "order.created-buy" : "order.created-sell",
                Messages.text("qty", qty), Messages.component("item", rt.templates().name(template)),
                msg.money(lang, "price", price, scale), msg.money(lang, "total", price.times(qty), scale));
        MatchPlan plan = r.plan();
        long filled = plan == null ? 0 : plan.fills().stream().mapToLong(f -> f.quantity()).sum();
        if (filled > 0) {
            msg.send(p, "order.filled-now", Messages.text("filled", filled), Messages.text("qty", qty));
        }
        return true;
    }

    public CompletableFuture<Boolean> cancel(Player p, long id) {
        if (!open(p)) {
            return no();
        }
        return locked(p, () -> rt.ops().cancel(new PlayerId(p.getUniqueId()), id).thenApplyAsync(r -> {
            if (r.status() == OpResult.Status.DONE) {
                msg.send(p, "order.cancelled", Messages.text("id", id));
                return true;
            }
            reason(p, r);
            return false;
        }, rt.main()));
    }

    // --- mailbox -----------------------------------------------------------------------------------------

    /** Claims the rows in order; stops at the first refusal or when the inventory is full. True if all were claimed. */
    public CompletableFuture<Boolean> claim(Player p, List<MailboxRow> rows) {
        if (!open(p)) {
            return no();
        }
        if (rows.isEmpty()) {
            msg.send(p, "claim.nothing");
            return no();
        }
        return locked(p, () -> claimNext(p, rows, 0));
    }

    private CompletableFuture<Boolean> claimNext(Player p, List<MailboxRow> rows, int index) {
        if (index >= rows.size()) {
            return CompletableFuture.completedFuture(true);
        }
        if (!p.isOnline()) {
            return no();
        }
        MailboxRow row = rows.get(index);
        boolean item = "ITEM".equals(row.kind());
        if (item && !rt.tryItemOperation()) {
            msg.send(p, "general.busy");
            return no();
        }
        return rt.ops().claim(new PlayerId(p.getUniqueId()), row.id()).thenComposeAsync(r -> {
            if (r.status() != OpResult.Status.DONE) {
                reason(p, r);
                return no();
            }
            String lang = msg.languageOf(p);
            if (!item) {
                msg.send(p, "claim.money", msg.money(lang, "amount", row.amount(), rt.settings().scale()));
                return claimNext(p, rows, index + 1);
            }
            msg.send(p, "claim.items", Messages.text("qty", r.quantity()), Messages.component("item", rt.templates().name(row.template())));
            if (r.quantity() < row.qty()) {
                msg.send(p, "claim.partial", Messages.text("qty", r.quantity()));
                return no(); // inventory is full; the rest stays
            }
            return claimNext(p, rows, index + 1);
        }, rt.main());
    }

    // --- language ----------------------------------------------------------------------------------------

    /** Sets and stores the player's language; false if the code is unknown. */
    public boolean setLanguage(Player p, String requested) {
        String code = requested == null ? null : msg.canonical(requested);
        if (code == null) {
            msg.send(p, "lang.unknown", Messages.text("list", String.join(", ", msg.available())));
            return false;
        }
        msg.setPreference(p.getUniqueId(), code);
        msg.send(p, "lang.set", Messages.text("name", msg.raw(code, "language-name")));
        PlayerId id = new PlayerId(p.getUniqueId());
        String name = p.getName();
        rt.onEngine(() -> rt.tx().inTransaction(c -> {
            rt.ledger().setPlayerLocale(c, id, name, code);
            return null;
        })).exceptionally(e -> {
            rt.plugin().getLogger().log(Level.WARNING, "Could not store the language of " + name, e);
            return null;
        });
        return true;
    }

    // --- queries (complete on the main thread) -----------------------------------------------------------

    public CompletableFuture<List<OrderRow>> activeOrders(Player p) {
        PlayerId owner = new PlayerId(p.getUniqueId());
        return onMain(rt.onEngine(() -> rt.tx().inTransaction(c -> rt.ledger().activeOrdersOf(c, owner))));
    }

    public CompletableFuture<List<MailboxRow>> mailbox(Player p) {
        PlayerId owner = new PlayerId(p.getUniqueId());
        return onMain(rt.onEngine(() -> rt.tx().inTransaction(c -> rt.journal().availableFor(c, owner))));
    }

    public CompletableFuture<List<MailboxRow>> inReview(Player p) {
        PlayerId owner = new PlayerId(p.getUniqueId());
        return onMain(rt.onEngine(() -> rt.tx().inTransaction(c -> rt.journal().inReviewFor(c, owner))));
    }

    public CompletableFuture<List<TradeRow>> history(Player p, OrderSide side, int limit, int offset) {
        PlayerId owner = new PlayerId(p.getUniqueId());
        return onMain(rt.onEngine(() -> rt.tx().inTransaction(c -> rt.ledger().tradesOf(c, owner, side, limit, offset))));
    }

    public CompletableFuture<List<BookView.MarketSummary>> markets() {
        return onMain(rt.onEngine(() -> rt.engine().summaries()));
    }

    public record Book(List<BookView.DepthLevel> asks, List<BookView.DepthLevel> bids) {
    }

    public CompletableFuture<Book> book(Player p, TemplateId template, int levels) {
        PlayerId viewer = new PlayerId(p.getUniqueId());
        return onMain(rt.onEngine(() -> new Book(rt.engine().depth(template, OrderSide.SELL, levels, viewer),
                rt.engine().depth(template, OrderSide.BUY, levels, viewer))));
    }

    public CompletableFuture<BookView.Preview> preview(Player p, OrderSide side, TemplateId template, Money price, long qty) {
        PlayerId owner = new PlayerId(p.getUniqueId());
        return onMain(rt.onEngine(() -> rt.engine().preview(owner, side, template, price, qty)));
    }

    private <T> CompletableFuture<T> onMain(CompletableFuture<T> f) {
        CompletableFuture<T> out = new CompletableFuture<>();
        f.whenComplete((v, e) -> rt.main().execute(() -> {
            if (e != null) {
                out.completeExceptionally(e);
            } else {
                out.complete(v);
            }
        }));
        return out;
    }
}
