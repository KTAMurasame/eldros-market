package com.eldros.market.core.settlement;

import com.eldros.market.api.model.OrderStatus;
import com.eldros.market.core.domain.Money;
import com.eldros.market.core.domain.NewOrder;
import com.eldros.market.core.domain.Order;
import com.eldros.market.core.domain.PlayerId;
import com.eldros.market.api.model.OrderSide;
import com.eldros.market.api.model.TemplateId;
import com.eldros.market.core.engine.BookReplayer;
import com.eldros.market.core.engine.CancelPlan;
import com.eldros.market.core.engine.Fill;
import com.eldros.market.core.engine.MatchPlan;
import com.eldros.market.core.engine.MatchingEngine;
import com.eldros.market.core.service.Fees;
import com.eldros.market.core.storage.jdbc.JdbcLedger;
import com.eldros.market.core.storage.jdbc.JdbcLedger.Credit;
import com.eldros.market.core.storage.jdbc.TransactionRunner;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * Makes matching durable (docs/03 §6.2). Order insert, matching plan and every consequence of it (order updates,
 * trades, mailbox credits, events, notifications) are written in one transaction; the engine is changed only after
 * that commit. A failed transaction therefore leaves both the database and the engine exactly as they were.
 * Engine thread only.
 */
public final class SettlementService {

    private final MatchingEngine engine;
    private final JdbcLedger ledger;
    private final Clock clock;

    public SettlementService(MatchingEngine engine, JdbcLedger ledger, Clock clock) {
        this.engine = engine;
        this.ledger = ledger;
        this.clock = clock;
    }

    /** Places an order and settles its immediate matches. {@code originOpId} links it to its journal entry (may be null). */
    public MatchPlan place(TransactionRunner tx, NewOrder spec, String originOpId) {
        MatchPlan plan = tx.inTransaction(c -> placeInTransaction(c, spec, originOpId));
        applyCommitted(plan, spec);
        return plan;
    }

    /**
     * The transactional half of {@link #place}: for callers that write more in the same transaction (the journal).
     * The caller must {@link #applyCommitted apply} the returned plan after commit, and must not apply it otherwise.
     * An immediate-or-cancel order's unfilled part is closed and refunded here, in the same transaction.
     */
    public MatchPlan placeInTransaction(Connection c, NewOrder spec, String originOpId) throws SQLException {
        long now = clock.millis();
        JdbcLedger.InsertedOrder row = ledger.insertOrder(c, spec, originOpId, now);
        Order order = Order.create(row.id(), spec.owner(), spec.side(), spec.template(), spec.unitPrice(), spec.quantity(),
                row.seq(), spec.expiresAt());
        MatchPlan plan = engine.plan(order);
        // An instant trader is shown the result directly, so only the resting side gets an outbox notification.
        persistPlan(c, plan, now, !spec.immediateOrCancel());
        if (spec.immediateOrCancel()) {
            persistUnfilled(c, plan, now);
        }
        return plan;
    }

    /**
     * Engine half after commit: applies the plan and, for an immediate-or-cancel order with an unfilled part, takes
     * it straight back out of the book (its row was closed in the same transaction as the fills). Both steps run
     * back to back on the engine thread, so no other operation ever sees the order resting.
     */
    public void applyCommitted(MatchPlan plan, NewOrder spec) {
        engine.apply(plan);
        Order incoming = plan.incomingOrder();
        if (spec.immediateOrCancel() && incoming.status() == OrderStatus.ACTIVE) {
            engine.applyCancel(engine.planCancel(incoming.id(), OrderStatus.CANCELLED));
        }
    }

    // --- edit: re-price and/or add quantity ------------------------------------------------------------

    /**
     * A request to replace active order {@code orderId} (owned by {@code owner}, trading {@code template}) with one at
     * {@code newPrice} for {@code expectedRemaining + addQuantity} items. {@code expectedRemaining} is what the order
     * had when the edit started; if it changed since, the edit is not applied.
     */
    public record EditSpec(PlayerId owner, TemplateId template, long orderId, long expectedRemaining, Money newPrice,
                           long addQuantity) {
    }

    /**
     * Outcome of an edit. When {@code applied}, {@code replaced} was closed and {@code plan} is the new order (it may
     * already have traded). Otherwise nothing changed except that {@code moneyRefund}/{@code itemRefund} (whatever was
     * taken from the player for the edit) went to the mailbox.
     */
    public record EditResult(boolean applied, CancelPlan replaced, MatchPlan plan, Money moneyRefund, long itemRefund) {
    }

    public EditResult edit(TransactionRunner tx, EditSpec spec, Money fundedMoney, long fundedItems, String originOpId) {
        EditResult result = tx.inTransaction(c -> editInTransaction(c, spec, fundedMoney, fundedItems, originOpId));
        applyEdit(result);
        return result;
    }

    /**
     * Transactional half of {@link #edit}. {@code fundedMoney}/{@code fundedItems} were already taken from the player
     * (extra escrow for a higher bid or more items for a sell). The old order is closed and the replacement inserted
     * in this transaction; money left over (a lower bid) goes to the mailbox. The caller must
     * {@link #applyEdit apply} the result after commit. The replacement gets a new place in the queue.
     */
    public EditResult editInTransaction(Connection c, EditSpec spec, Money fundedMoney, long fundedItems, String originOpId)
            throws SQLException {
        long now = clock.millis();
        Optional<Order> current = engine.activeOrder(spec.orderId());
        CancelPlan cancel = current.isPresent() ? engine.planCancel(spec.orderId(), OrderStatus.CANCELLED) : null;
        long newQuantity = spec.expectedRemaining() + spec.addQuantity();
        Money need = spec.newPrice().times(newQuantity);
        boolean still = cancel != null && cancel.owner().equals(spec.owner()) && cancel.template().equals(spec.template())
                && cancel.remaining() == spec.expectedRemaining();
        boolean funded = cancel != null && (cancel.side() == OrderSide.SELL
                ? fundedItems == spec.addQuantity() && fundedMoney.minor() == 0
                : fundedItems == 0 && cancel.refundMoney().plus(fundedMoney).compareTo(need) >= 0);
        if (!still || !funded) {
            String ref = Long.toString(spec.orderId());
            if (fundedMoney.minor() > 0) {
                ledger.credit(c, Credit.money(spec.owner(), fundedMoney, "EDIT_REFUND", "ORDER", ref), now);
            }
            if (fundedItems > 0) {
                ledger.credit(c, Credit.items(spec.owner(), spec.template(), fundedItems, "EDIT_REFUND", "ORDER", ref), now);
            }
            return new EditResult(false, null, null, fundedMoney, fundedItems);
        }
        ledger.closeOrder(c, spec.orderId(), OrderStatus.CANCELLED, cancel.refundMoney(), cancel.remaining(), now);
        ledger.event(c, spec.orderId(), "CANCELLED", cancel.remaining(), null, now);
        Money leftover = cancel.side() == OrderSide.BUY ? cancel.refundMoney().plus(fundedMoney).minus(need) : Money.ZERO;
        if (leftover.minor() > 0) {
            ledger.credit(c, Credit.money(spec.owner(), leftover, "EDIT_REFUND", "ORDER", Long.toString(spec.orderId())), now);
        }
        // The replacement keeps the tax rate the player accepted; an edit is never a way around it.
        NewOrder replacement = new NewOrder(spec.owner(), cancel.side(), spec.template(), spec.newPrice(), newQuantity,
                current.get().expiresAt()).withFees(ledger.sellerTaxBps(c, spec.orderId()), Money.ZERO);
        JdbcLedger.InsertedOrder row = ledger.insertOrder(c, replacement, originOpId, now);
        Order order = Order.create(row.id(), spec.owner(), cancel.side(), spec.template(), spec.newPrice(), newQuantity,
                row.seq(), replacement.expiresAt());
        MatchPlan plan = engine.plan(order); // the old order is still in the book here; it is the owner's, so skipped
        persistPlan(c, plan, now, true);
        return new EditResult(true, cancel, plan, leftover, 0);
    }

    /** Engine half of an edit after commit: removes the old order, then applies the replacement's plan. */
    public void applyEdit(EditResult result) {
        if (result.applied()) {
            engine.applyCancel(result.replaced());
            engine.apply(result.plan());
        }
    }

    /** Closes the unfilled part of an immediate-or-cancel order and refunds it to the owner's mailbox. */
    private void persistUnfilled(Connection c, MatchPlan plan, long now) throws SQLException {
        Order incoming = plan.incomingOrder();
        long filled = plan.fills().stream().mapToLong(Fill::quantity).sum();
        long remaining = plan.incomingRemainingBefore() - filled;
        if (remaining <= 0) {
            return;
        }
        boolean buy = incoming.side() == OrderSide.BUY;
        Money escrow = buy ? incoming.unitPrice().times(remaining) : Money.ZERO;
        String ref = Long.toString(incoming.id());
        ledger.closeOrder(c, incoming.id(), OrderStatus.CANCELLED, escrow, remaining, now);
        if (buy) {
            ledger.credit(c, Credit.money(incoming.owner(), escrow, "UNFILLED_REFUND", "ORDER", ref), now);
        } else {
            ledger.credit(c, Credit.items(incoming.owner(), incoming.template(), remaining, "UNFILLED_REFUND", "ORDER", ref), now);
        }
        ledger.event(c, incoming.id(), "CANCELLED", remaining, null, now);
    }

    public CancelPlan cancel(TransactionRunner tx, long orderId, OrderStatus finalStatus) {
        CancelPlan plan = engine.planCancel(orderId, finalStatus);
        tx.inTransaction(c -> {
            persistCancel(c, plan, clock.millis());
            return null;
        });
        engine.applyCancel(plan);
        return plan;
    }

    /**
     * Startup: loads active orders into the (empty) engine in seq order and persists any fills a crash left unmade
     * (docs/03 §6.4). If persisting fails the engine no longer matches the database: the market must not open.
     */
    public List<MatchPlan> loadAndReplay(TransactionRunner tx) {
        List<Order> active = tx.inTransaction(ledger::loadActiveOrders);
        List<MatchPlan> withFills = BookReplayer.rebuild(engine, active).stream().filter(p -> !p.fills().isEmpty()).toList();
        if (!withFills.isEmpty()) {
            tx.inTransaction(c -> {
                long now = clock.millis();
                for (MatchPlan plan : withFills) {
                    persistPlan(c, plan, now, true);
                }
                return null;
            });
        }
        return withFills;
    }

    private void persistPlan(Connection c, MatchPlan plan, long now, boolean notifyIncoming) throws SQLException {
        long incomingRemaining = plan.incomingRemainingBefore();
        for (int i = 0; i < plan.fills().size(); i++) {
            Fill fill = plan.fills().get(i);
            long q = fill.quantity();
            boolean restingFilled = ledger.applyFill(c, fill.restingOrderId(), q, plan.restingRemainingBefore().get(i), now);
            boolean incomingFilled = ledger.applyFill(c, fill.incomingOrderId(), q, incomingRemaining, now);
            incomingRemaining -= q;
            // The seller's own order carries the rate it accepted when it was placed (read here, so a resting seller
            // who is offline, and every replay after a restart, pays exactly that rate).
            Money tax = Fees.tax(fill.total(), ledger.sellerTaxBps(c, fill.sellOrderId()));
            long tradeId = ledger.insertTrade(c, fill, tax, now);
            String ref = Long.toString(tradeId);

            ledger.event(c, fill.restingOrderId(), restingFilled ? "FILLED" : "FILLED_PART", q, tradeId, now);
            ledger.event(c, fill.incomingOrderId(), incomingFilled ? "FILLED" : "FILLED_PART", q, tradeId, now);

            ledger.credit(c, Credit.items(fill.buyer(), fill.template(), q, "TRADE_ITEM", "TRADE", ref), now);
            Money net = fill.total().minus(tax);
            if (net.minor() > 0) {
                ledger.credit(c, Credit.money(fill.seller(), net, "TRADE_MONEY", "TRADE", ref), now);
            }
            Money improvement = fill.priceImprovement();
            if (improvement.minor() > 0) {
                ledger.credit(c, Credit.money(fill.buyer(), improvement, "PRICE_IMPROVEMENT", "TRADE", ref), now);
            }

            long template = fill.template().value();
            boolean incomingBuys = fill.buyOrderId() == fill.incomingOrderId();
            if (notifyIncoming || incomingBuys) {
                if (tax.minor() > 0) {
                    ledger.notify(c, fill.seller(), "notify.trade.sold-taxed", now, template, q, fill.price().minor(), tax.minor());
                } else {
                    ledger.notify(c, fill.seller(), "notify.trade.sold", now, template, q, fill.price().minor());
                }
            }
            if (notifyIncoming || !incomingBuys) {
                ledger.notify(c, fill.buyer(), "notify.trade.bought", now, template, q, fill.price().minor());
            }
        }
    }

    private void persistCancel(Connection c, CancelPlan plan, long now) throws SQLException {
        boolean expired = plan.finalStatus() == OrderStatus.EXPIRED;
        String reason = expired ? "EXPIRE_REFUND" : "CANCEL_REFUND";
        String ref = Long.toString(plan.orderId());
        ledger.closeOrder(c, plan.orderId(), plan.finalStatus(), plan.refundMoney(), plan.remaining(), now);
        if (plan.refundMoney().minor() > 0) {
            ledger.credit(c, Credit.money(plan.owner(), plan.refundMoney(), reason, "ORDER", ref), now);
        }
        if (plan.refundItems() > 0) {
            ledger.credit(c, Credit.items(plan.owner(), plan.template(), plan.refundItems(), reason, "ORDER", ref), now);
        }
        ledger.event(c, plan.orderId(), expired ? "EXPIRED" : "CANCELLED", plan.remaining(), null, now);
        ledger.notify(c, plan.owner(), expired ? "notify.order.expired" : "notify.order.cancelled", now,
                plan.template().value(), plan.remaining());
    }
}
