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
import com.eldros.market.core.storage.jdbc.JdbcJournal.MailboxRow;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * The four operations that touch the outside world (docs/03 §7.3): BUY_CREATE and CLAIM_MONEY call the economy,
 * SELL_CREATE and CLAIM_ITEM change the inventory. Every one writes its journal intent first; economy outcomes go
 * to the effect log; inventory changes carry a player marker made durable by save (Player#saveData). Steps hop
 * between threads with CompletableFuture; the main thread never waits.
 */
public final class MarketOperations {

    private final MarketContext x;

    public MarketOperations(MarketContext context) {
        this.x = context;
    }

    // --- BUY_CREATE ----------------------------------------------------------------------------------

    public CompletableFuture<OpResult> buy(NewOrder spec) {
        String op = UUID.randomUUID().toString();
        Money amount = spec.unitPrice().times(spec.quantity());
        return CompletableFuture.supplyAsync(() -> {
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
                        MatchPlan plan = x.tx().inTransaction(c -> {
                            MatchPlan p = x.settlement().placeInTransaction(c, spec, op);
                            x.journal().transition(c, op, State.PENDING, State.APPLIED, p.incomingOrder().id(), null, null, null, now());
                            x.faults().at(CrashPoint.BUY_IN_TRANSACTION);
                            return p;
                        });
                        x.faults().at(CrashPoint.BUY_COMMITTED);
                        x.engine().apply(plan);
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
                }, x.engineThread());
    }

    // --- SELL_CREATE ---------------------------------------------------------------------------------

    public CompletableFuture<OpResult> sell(NewOrder spec) {
        String op = UUID.randomUUID().toString();
        PlayerId owner = spec.owner();
        return CompletableFuture.supplyAsync(() -> {
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
                    MatchPlan plan = x.tx().inTransaction(c -> {
                        MatchPlan p = x.settlement().placeInTransaction(c, spec, op);
                        x.journal().transition(c, op, State.PENDING, State.APPLIED, p.incomingOrder().id(), null, null, null, now());
                        x.faults().at(CrashPoint.SELL_IN_TRANSACTION);
                        return p;
                    });
                    x.faults().at(CrashPoint.SELL_COMMITTED);
                    x.engine().apply(plan);
                    return OpResult.done(op, plan, spec.quantity());
                }, x.engineThread())
                .thenApplyAsync(result -> {
                    if (result.status() == OpResult.Status.DONE) {
                        x.markers().remove(owner, op); // durability of the removal is not needed (terminal + marker → cleanup)
                    }
                    return result;
                }, x.mainThread());
    }

    // --- CLAIM_MONEY / CLAIM_ITEM ----------------------------------------------------------------------

    public CompletableFuture<OpResult> claim(PlayerId player, long mailboxId) {
        String op = UUID.randomUUID().toString();
        return CompletableFuture.supplyAsync(() -> x.tx().inTransaction(c -> {
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
                });
    }

    private CompletableFuture<OpResult> claimMoney(String op, PlayerId player, MailboxRow row) {
        return CompletableFuture.supplyAsync(() -> {
                    x.faults().at(CrashPoint.MONEY_CLAIMING_WRITTEN);
                    return callProvider(() -> x.economy().deposit(player, row.amount()), CrashPoint.MONEY_PROVIDER_DONE);
                }, x.mainThread())
                .thenApplyAsync(outcome -> logEffect(op, outcome, CrashPoint.MONEY_EFFECT_LOGGED), x.effectThread())
                .thenApplyAsync(outcome -> switch (outcome) {
                    case CONFIRMED -> {
                        x.tx().inTransaction(c -> {
                            x.journal().transition(c, op, State.PENDING, State.APPLIED, null, null, null, null, now());
                            x.journal().finishClaim(c, row.id(), op, 0, now());
                            x.faults().at(CrashPoint.MONEY_IN_TRANSACTION);
                            return null;
                        });
                        x.faults().at(CrashPoint.MONEY_COMMITTED);
                        yield OpResult.done(op, null, 0);
                    }
                    case FAILED -> {
                        x.tx().inTransaction(c -> {
                            x.journal().transition(c, op, State.PENDING, State.ABORTED, null, null, null, null, now());
                            x.journal().moveClaim(c, row.id(), op, "CLAIMING", "AVAILABLE", now());
                            return null;
                        });
                        yield OpResult.rejected(op, OpResult.Reason.NOT_AVAILABLE);
                    }
                    case UNKNOWN -> {
                        x.tx().inTransaction(c -> {
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
                        x.tx().inTransaction(c -> {
                            x.journal().transition(c, op, State.PENDING, State.ABORTED, null, null, null, null, now());
                            x.journal().moveClaim(c, row.id(), op, "CLAIMING", "AVAILABLE", now());
                            return null;
                        });
                        return OpResult.rejected(op, OpResult.Reason.INVENTORY_FULL);
                    }
                    x.tx().inTransaction(c -> {
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

    // --- helpers -----------------------------------------------------------------------------------

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
        x.tx().inTransaction(c -> {
            x.journal().transition(c, op, State.PENDING, to, null, null, reason, null, now());
            return null;
        });
    }

    private long now() {
        return x.clock().millis();
    }
}
