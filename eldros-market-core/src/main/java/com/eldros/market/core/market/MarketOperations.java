package com.eldros.market.core.market;

import com.eldros.market.api.model.OrderStatus;
import com.eldros.market.core.domain.Money;
import com.eldros.market.core.domain.NewOrder;
import com.eldros.market.core.domain.PlayerId;
import com.eldros.market.core.effect.EffectLog.Outcome;
import com.eldros.market.core.engine.CancelPlan;
import com.eldros.market.core.engine.MatchPlan;
import com.eldros.market.core.journal.JournalEntry;
import com.eldros.market.core.journal.JournalEntry.State;
import com.eldros.market.core.journal.JournalEntry.Type;
import com.eldros.market.core.port.EconomyGateway.EconomyResult;
import com.eldros.market.core.port.FaultInjector.CrashPoint;
import com.eldros.market.core.safety.SafeMode;
import com.eldros.market.core.settlement.SettlementService;
import com.eldros.market.core.storage.StaleStateException;
import com.eldros.market.core.storage.StorageException;
import com.eldros.market.core.storage.jdbc.JdbcJournal.MailboxRow;
import com.eldros.market.core.storage.jdbc.TransactionRunner.SqlWork;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * The four operations that touch the outside world (docs/03 §7.3): BUY_CREATE and CLAIM_MONEY call the economy,
 * SELL_CREATE and CLAIM_ITEM change the inventory. Every one writes its journal intent first; economy outcomes go
 * to the effect log; inventory changes carry a player marker made durable by save (Player#saveData). Steps hop
 * between threads with CompletableFuture; the main thread never waits.
 *
 * <p>Writes after an external effect are retried (200/500/1000 ms); if they still fail the market enters safe mode
 * and the operation is left for recovery, which refunds from the effect log or the markers (docs/03 §7.8).
 */
public final class MarketOperations {

    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis);
    }

    private static final long[] RETRY_DELAYS = {200, 500, 1_000};

    private static final class SafeModeTripped extends RuntimeException {
        SafeModeTripped(Throwable cause) {
            super(cause);
        }
    }

    private final MarketContext x;
    private final SafeMode safeMode;
    private final Sleeper sleeper;

    public MarketOperations(MarketContext context) {
        this(context, new SafeMode(), MarketOperations::sleepQuietly);
    }

    public MarketOperations(MarketContext context, SafeMode safeMode, Sleeper sleeper) {
        this.x = context;
        this.safeMode = safeMode;
        this.sleeper = sleeper;
    }

    public SafeMode safeMode() {
        return safeMode;
    }

    // --- BUY_CREATE ----------------------------------------------------------------------------------

    public CompletableFuture<OpResult> buy(NewOrder spec) {
        String op = UUID.randomUUID().toString();
        if (safeMode.isActive()) {
            return CompletableFuture.completedFuture(OpResult.rejected(op, OpResult.Reason.SAFE_MODE));
        }
        Money amount = spec.unitPrice().times(spec.quantity()).plus(spec.creationFee()); // escrow + fee in one withdrawal
        return guard(op, CompletableFuture.supplyAsync(() -> {
                    x.tx().inTransaction(c -> {
                        x.journal().insertPending(c, JournalEntry.pending(op, Type.BUY_CREATE, spec.owner(), spec.template(),
                                spec.quantity(), spec.unitPrice(), amount, null, now()));
                        return null;
                    });
                    x.faults().at(CrashPoint.BUY_PENDING_WRITTEN);
                    return op;
                }, x.engineThread())
                .thenApplyAsync(o -> callProvider(() -> x.economy().withdraw(spec.owner(), amount), CrashPoint.BUY_PROVIDER_DONE),
                        x.mainThread())
                .thenApplyAsync(outcome -> logEffect(op, outcome, CrashPoint.BUY_EFFECT_LOGGED), x.effectThread())
                .thenApplyAsync(outcome -> switch (outcome) {
                    case CONFIRMED -> {
                        MatchPlan plan = durably(c -> {
                            MatchPlan p = x.settlement().placeInTransaction(c, spec, op);
                            x.journal().transition(c, op, State.PENDING, State.APPLIED, p.incomingOrder().id(), null, null, null, now());
                            x.faults().at(CrashPoint.BUY_IN_TRANSACTION);
                            return p;
                        });
                        x.faults().at(CrashPoint.BUY_COMMITTED);
                        x.settlement().applyCommitted(plan, spec);
                        yield OpResult.done(op, plan, spec.quantity());
                    }
                    case FAILED -> {
                        transition(op, State.ABORTED, null);
                        yield OpResult.rejected(op, OpResult.Reason.INSUFFICIENT_FUNDS);
                    }
                    case UNKNOWN -> {
                        transition(op, State.QUARANTINED, "VAULT_EXCEPTION");
                        yield OpResult.quarantined(op);
                    }
                }, x.engineThread()));
    }

    // --- SELL_CREATE ---------------------------------------------------------------------------------

    public CompletableFuture<OpResult> sell(NewOrder spec) {
        String op = UUID.randomUUID().toString();
        if (safeMode.isActive()) {
            return CompletableFuture.completedFuture(OpResult.rejected(op, OpResult.Reason.SAFE_MODE));
        }
        PlayerId owner = spec.owner();
        return guard(op, CompletableFuture.supplyAsync(() -> {
                    x.tx().inTransaction(c -> {
                        x.journal().insertPending(c, JournalEntry.pending(op, Type.SELL_CREATE, owner, spec.template(),
                                spec.quantity(), spec.unitPrice(), null, null, now()));
                        return null;
                    });
                    x.faults().at(CrashPoint.SELL_PENDING_WRITTEN);
                    return op;
                }, x.engineThread())
                .thenApplyAsync(o -> {
                    // Inventory change and marker in the same synchronous block, then one save (03 §7.1, R-1).
                    if (!x.inventory().remove(owner, spec.template(), spec.quantity())) {
                        return false;
                    }
                    x.markers().put(owner, op, spec.quantity());
                    x.faults().at(CrashPoint.SELL_REMOVED_UNSAVED);
                    x.markers().save(owner);
                    x.faults().at(CrashPoint.SELL_SAVED);
                    return true;
                }, x.mainThread())
                .thenApplyAsync(removed -> {
                    if (!removed) {
                        transition(op, State.ABORTED, null);
                        return OpResult.rejected(op, OpResult.Reason.NOT_ENOUGH_ITEMS);
                    }
                    MatchPlan plan = durably(c -> {
                        MatchPlan p = x.settlement().placeInTransaction(c, spec, op);
                        x.journal().transition(c, op, State.PENDING, State.APPLIED, p.incomingOrder().id(), null, null, null, now());
                        x.faults().at(CrashPoint.SELL_IN_TRANSACTION);
                        return p;
                    });
                    x.faults().at(CrashPoint.SELL_COMMITTED);
                    x.settlement().applyCommitted(plan, spec);
                    return OpResult.done(op, plan, spec.quantity());
                }, x.engineThread())
                .thenApplyAsync(result -> {
                    if (result.status() == OpResult.Status.DONE) {
                        x.markers().remove(owner, op); // durability of the removal is not needed (terminal + marker → cleanup)
                    }
                    return result;
                }, x.mainThread()));
    }

    // --- CLAIM_MONEY / CLAIM_ITEM ----------------------------------------------------------------------

    public CompletableFuture<OpResult> claim(PlayerId player, long mailboxId) {
        String op = UUID.randomUUID().toString();
        if (safeMode.isActive()) {
            return CompletableFuture.completedFuture(OpResult.rejected(op, OpResult.Reason.SAFE_MODE));
        }
        return guard(op, CompletableFuture.supplyAsync(() -> x.tx().inTransaction(c -> {
                    Optional<MailboxRow> found = x.journal().mailbox(c, mailboxId);
                    if (found.isEmpty() || !found.get().owner().equals(player) || !"AVAILABLE".equals(found.get().status())) {
                        return Optional.<MailboxRow>empty();
                    }
                    MailboxRow row = found.get();
                    boolean money = "MONEY".equals(row.kind());
                    x.journal().startClaim(c, mailboxId, player, op, now());
                    x.journal().insertPending(c, JournalEntry.pending(op, money ? Type.CLAIM_MONEY : Type.CLAIM_ITEM, player,
                            row.template(), money ? null : row.qty(), null, money ? row.amount() : null, mailboxId, now()));
                    return Optional.of(row);
                }), x.engineThread())
                .thenCompose(row -> {
                    if (row.isEmpty()) {
                        return CompletableFuture.completedFuture(OpResult.rejected(op, OpResult.Reason.NOT_AVAILABLE));
                    }
                    return "MONEY".equals(row.get().kind()) ? claimMoney(op, player, row.get()) : claimItems(op, player, row.get());
                }));
    }

    private CompletableFuture<OpResult> claimMoney(String op, PlayerId player, MailboxRow row) {
        return CompletableFuture.supplyAsync(() -> {
                    x.faults().at(CrashPoint.MONEY_CLAIMING_WRITTEN);
                    return callProvider(() -> x.economy().deposit(player, row.amount()), CrashPoint.MONEY_PROVIDER_DONE);
                }, x.mainThread())
                .thenApplyAsync(outcome -> logEffect(op, outcome, CrashPoint.MONEY_EFFECT_LOGGED), x.effectThread())
                .thenApplyAsync(outcome -> switch (outcome) {
                    case CONFIRMED -> {
                        durably(c -> {
                            x.journal().transition(c, op, State.PENDING, State.APPLIED, null, null, null, null, now());
                            x.journal().finishClaim(c, row.id(), op, 0, now());
                            x.faults().at(CrashPoint.MONEY_IN_TRANSACTION);
                            return null;
                        });
                        x.faults().at(CrashPoint.MONEY_COMMITTED);
                        yield OpResult.done(op, null, 0);
                    }
                    case FAILED -> {
                        durably(c -> {
                            x.journal().transition(c, op, State.PENDING, State.ABORTED, null, null, null, null, now());
                            x.journal().moveClaim(c, row.id(), op, "CLAIMING", "AVAILABLE", now());
                            return null;
                        });
                        yield OpResult.rejected(op, OpResult.Reason.NOT_AVAILABLE);
                    }
                    case UNKNOWN -> {
                        durably(c -> {
                            x.journal().transition(c, op, State.PENDING, State.QUARANTINED, null, null, "VAULT_EXCEPTION", null, now());
                            x.journal().moveClaim(c, row.id(), op, "CLAIMING", "QUARANTINED", now());
                            return null;
                        });
                        yield OpResult.quarantined(op);
                    }
                }, x.engineThread());
    }

    private CompletableFuture<OpResult> claimItems(String op, PlayerId player, MailboxRow row) {
        return CompletableFuture.supplyAsync(() -> {
                    x.faults().at(CrashPoint.ITEM_CLAIMING_WRITTEN);
                    long n = x.inventory().isOnline(player) ? x.inventory().fit(player, row.template(), row.qty()) : 0;
                    if (n <= 0) {
                        return 0L;
                    }
                    x.inventory().add(player, row.template(), n);
                    x.markers().put(player, op, n);
                    x.faults().at(CrashPoint.ITEM_ADDED_UNSAVED);
                    x.markers().save(player);
                    x.faults().at(CrashPoint.ITEM_SAVED);
                    return n;
                }, x.mainThread())
                .thenApplyAsync(n -> {
                    if (n == 0) {
                        durably(c -> {
                            x.journal().transition(c, op, State.PENDING, State.ABORTED, null, null, null, null, now());
                            x.journal().moveClaim(c, row.id(), op, "CLAIMING", "AVAILABLE", now());
                            return null;
                        });
                        return OpResult.rejected(op, OpResult.Reason.INVENTORY_FULL);
                    }
                    durably(c -> {
                        x.journal().transition(c, op, State.PENDING, State.APPLIED, null, n, null, null, now());
                        x.journal().finishClaim(c, row.id(), op, n, now());
                        x.faults().at(CrashPoint.ITEM_IN_TRANSACTION);
                        return null;
                    });
                    x.faults().at(CrashPoint.ITEM_COMMITTED);
                    return OpResult.done(op, null, n);
                }, x.engineThread())
                .thenApplyAsync(result -> {
                    if (result.status() == OpResult.Status.DONE) {
                        x.markers().remove(player, op);
                    }
                    return result;
                }, x.mainThread());
    }

    // --- cancel ------------------------------------------------------------------------------------

    /** Player cancel. Pure database + engine; no external effect, so no journal entry is needed. */
    public CompletableFuture<OpResult> cancel(PlayerId player, long orderId) {
        if (safeMode.isActive()) {
            return CompletableFuture.completedFuture(OpResult.rejected(null, OpResult.Reason.SAFE_MODE));
        }
        return CompletableFuture.supplyAsync(() -> {
            CancelPlan probe;
            try {
                probe = x.engine().planCancel(orderId, OrderStatus.CANCELLED);
            } catch (IllegalStateException e) {
                return OpResult.rejected(null, OpResult.Reason.NOT_AVAILABLE);
            }
            if (!probe.owner().equals(player)) {
                return OpResult.rejected(null, OpResult.Reason.NOT_OWNER);
            }
            CancelPlan done = x.settlement().cancel(x.tx(), orderId, OrderStatus.CANCELLED);
            return OpResult.done(null, null, done.remaining());
        }, x.engineThread());
    }

    // --- edit (re-price / add quantity) ----------------------------------------------------------------

    /**
     * Replaces an active order with one at {@code newPrice} holding {@code addQuantity} more items. What the edit needs
     * from outside the market is taken exactly like a new order of that size would take it, under the same journal
     * types: extra escrow for a bid through BUY_CREATE (amount = the difference), extra items for a sell through
     * SELL_CREATE (qty = the added items). So every crash window recovers like the create it mirrors, refunding only
     * the extra and leaving the old order intact. A cheaper bid's surplus goes to the mailbox.
     */
    public CompletableFuture<OpResult> edit(PlayerId player, long orderId, Money newPrice, long addQuantity) {
        String op = UUID.randomUUID().toString();
        if (safeMode.isActive()) {
            return CompletableFuture.completedFuture(OpResult.rejected(op, OpResult.Reason.SAFE_MODE));
        }
        if (addQuantity < 0) {
            throw new IllegalArgumentException("addQuantity must be >= 0");
        }
        return guard(op, CompletableFuture.supplyAsync(() -> {
            var current = x.engine().activeOrder(orderId);
            if (current.isEmpty()) {
                return CompletableFuture.completedFuture(OpResult.rejected(op, OpResult.Reason.NOT_AVAILABLE));
            }
            var order = current.get();
            if (!order.owner().equals(player)) {
                return CompletableFuture.completedFuture(OpResult.rejected(op, OpResult.Reason.NOT_OWNER));
            }
            SettlementService.EditSpec spec = new SettlementService.EditSpec(player, order.template(), orderId,
                    order.remaining(), newPrice, addQuantity);
            long newQuantity = order.remaining() + addQuantity;
            if (order.side() == com.eldros.market.api.model.OrderSide.BUY) {
                Money need = newPrice.times(newQuantity);
                Money extra = need.compareTo(order.escrow()) > 0 ? need.minus(order.escrow()) : Money.ZERO;
                return extra.minor() == 0 ? editNow(op, spec) : editFundedByMoney(op, spec, extra, newQuantity);
            }
            return addQuantity == 0 ? editNow(op, spec) : editFundedByItems(op, spec);
        }, x.engineThread()).thenCompose(f -> f));
    }

    /** No outside effect needed (lower or equal escrow, or a sell re-price): one transaction, no journal entry. */
    private CompletableFuture<OpResult> editNow(String op, SettlementService.EditSpec spec) {
        SettlementService.EditResult r = durably(c -> x.settlement().editInTransaction(c, spec, Money.ZERO, 0, null));
        x.settlement().applyEdit(r);
        return CompletableFuture.completedFuture(r.applied()
                ? OpResult.done(op, r.plan(), spec.expectedRemaining() + spec.addQuantity())
                : OpResult.rejected(op, OpResult.Reason.ORDER_CHANGED));
    }

    private CompletableFuture<OpResult> editFundedByMoney(String op, SettlementService.EditSpec spec, Money extra, long newQuantity) {
        PlayerId owner = spec.owner();
        x.tx().inTransaction(c -> {
            x.journal().insertPending(c, JournalEntry.pending(op, Type.BUY_CREATE, owner, spec.template(), newQuantity,
                    spec.newPrice(), extra, null, now()));
            return null;
        });
        x.faults().at(CrashPoint.BUY_PENDING_WRITTEN);
        return CompletableFuture.supplyAsync(() -> callProvider(() -> x.economy().withdraw(owner, extra), CrashPoint.BUY_PROVIDER_DONE),
                        x.mainThread())
                .thenApplyAsync(outcome -> logEffect(op, outcome, CrashPoint.BUY_EFFECT_LOGGED), x.effectThread())
                .thenApplyAsync(outcome -> switch (outcome) {
                    case CONFIRMED -> {
                        SettlementService.EditResult r = durably(c -> {
                            SettlementService.EditResult e = x.settlement().editInTransaction(c, spec, extra, 0, op);
                            x.journal().transition(c, op, State.PENDING, State.APPLIED,
                                    e.applied() ? e.plan().incomingOrder().id() : null, null, null, null, now());
                            x.faults().at(CrashPoint.BUY_IN_TRANSACTION);
                            return e;
                        });
                        x.faults().at(CrashPoint.BUY_COMMITTED);
                        x.settlement().applyEdit(r);
                        yield r.applied() ? OpResult.done(op, r.plan(), newQuantity) : OpResult.rejected(op, OpResult.Reason.ORDER_CHANGED);
                    }
                    case FAILED -> {
                        transition(op, State.ABORTED, null);
                        yield OpResult.rejected(op, OpResult.Reason.INSUFFICIENT_FUNDS);
                    }
                    case UNKNOWN -> {
                        transition(op, State.QUARANTINED, "VAULT_EXCEPTION");
                        yield OpResult.quarantined(op);
                    }
                }, x.engineThread());
    }

    private CompletableFuture<OpResult> editFundedByItems(String op, SettlementService.EditSpec spec) {
        PlayerId owner = spec.owner();
        long add = spec.addQuantity();
        x.tx().inTransaction(c -> {
            x.journal().insertPending(c, JournalEntry.pending(op, Type.SELL_CREATE, owner, spec.template(), add,
                    spec.newPrice(), null, null, now()));
            return null;
        });
        x.faults().at(CrashPoint.SELL_PENDING_WRITTEN);
        return CompletableFuture.supplyAsync(() -> {
                    if (!x.inventory().remove(owner, spec.template(), add)) {
                        return false;
                    }
                    x.markers().put(owner, op, add);
                    x.faults().at(CrashPoint.SELL_REMOVED_UNSAVED);
                    x.markers().save(owner);
                    x.faults().at(CrashPoint.SELL_SAVED);
                    return true;
                }, x.mainThread())
                .thenApplyAsync(removed -> {
                    if (!removed) {
                        transition(op, State.ABORTED, null);
                        return OpResult.rejected(op, OpResult.Reason.NOT_ENOUGH_ITEMS);
                    }
                    SettlementService.EditResult r = durably(c -> {
                        SettlementService.EditResult e = x.settlement().editInTransaction(c, spec, Money.ZERO, add, op);
                        x.journal().transition(c, op, State.PENDING, State.APPLIED,
                                e.applied() ? e.plan().incomingOrder().id() : null, null, null, null, now());
                        x.faults().at(CrashPoint.SELL_IN_TRANSACTION);
                        return e;
                    });
                    x.faults().at(CrashPoint.SELL_COMMITTED);
                    x.settlement().applyEdit(r);
                    return r.applied()
                            ? OpResult.done(op, r.plan(), spec.expectedRemaining() + add)
                            : OpResult.rejected(op, OpResult.Reason.ORDER_CHANGED);
                }, x.engineThread())
                .thenApplyAsync(result -> {
                    // The items were either placed in the new order or refunded to the mailbox in the same commit.
                    if (result.reason() != OpResult.Reason.NOT_ENOUGH_ITEMS) {
                        x.markers().remove(owner, op);
                    }
                    return result;
                }, x.mainThread());
    }

    // --- helpers -----------------------------------------------------------------------------------

    /**
     * Runs a write that follows an external effect. Retries storage failures; a stale state on a retry means an
     * earlier attempt committed although the driver reported an error ("ghost commit", V-S-2). Either way, when the
     * retries are spent the market enters safe mode and recovery will close the operation from durable evidence.
     */
    private <T> T durably(SqlWork<T> work) {
        StorageException last = null;
        for (int attempt = 0; attempt <= RETRY_DELAYS.length; attempt++) {
            try {
                return x.tx().inTransaction(work);
            } catch (StaleStateException e) {
                if (attempt == 0) {
                    throw e; // a genuine logic conflict, not a storage fault
                }
                last = e;
                break;
            } catch (StorageException e) {
                last = e;
                if (attempt < RETRY_DELAYS.length) {
                    sleeper.sleep(RETRY_DELAYS[attempt]);
                }
            }
        }
        safeMode.enter("write failed after retries: " + last.getMessage());
        throw new SafeModeTripped(last);
    }

    private CompletableFuture<OpResult> guard(String op, CompletableFuture<OpResult> chain) {
        return chain.exceptionally(t -> {
            Throwable cause = t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
            if (cause instanceof SafeModeTripped) {
                return OpResult.rejected(op, OpResult.Reason.SAFE_MODE);
            }
            throw t instanceof CompletionException ce ? ce : new CompletionException(t);
        });
    }

    private Outcome callProvider(Supplier<EconomyResult> call, CrashPoint after) {
        Outcome outcome;
        try {
            outcome = call.get().success() ? Outcome.CONFIRMED : Outcome.FAILED;
        } catch (RuntimeException e) {
            outcome = Outcome.UNKNOWN; // the provider may or may not have applied it (F0 B6, 03 §7.3)
        }
        x.faults().at(after);
        return outcome;
    }

    private Outcome logEffect(String op, Outcome outcome, CrashPoint after) {
        x.effects().append(op, outcome);
        x.faults().at(after);
        return outcome;
    }

    private void transition(String op, State to, String reason) {
        durably(c -> {
            x.journal().transition(c, op, State.PENDING, to, null, null, reason, null, now());
            return null;
        });
    }

    private long now() {
        return x.clock().millis();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
