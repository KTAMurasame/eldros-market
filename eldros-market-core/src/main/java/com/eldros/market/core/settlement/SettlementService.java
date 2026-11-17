package com.eldros.market.core.settlement;

import com.eldros.market.api.model.OrderStatus;
import com.eldros.market.core.domain.Money;
import com.eldros.market.core.domain.NewOrder;
import com.eldros.market.core.domain.Order;
import com.eldros.market.core.engine.BookReplayer;
import com.eldros.market.core.engine.CancelPlan;
import com.eldros.market.core.engine.Fill;
import com.eldros.market.core.engine.MatchPlan;
import com.eldros.market.core.engine.MatchingEngine;
import com.eldros.market.core.storage.jdbc.JdbcLedger;
import com.eldros.market.core.storage.jdbc.JdbcLedger.Credit;
import com.eldros.market.core.storage.jdbc.TransactionRunner;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;

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
        engine.apply(plan);
        return plan;
    }

    /**
     * The transactional half of {@link #place}: for callers that write more in the same transaction (the journal).
     * The caller must {@link MatchingEngine#apply apply} the returned plan after commit, and must not apply it otherwise.
     */
    public MatchPlan placeInTransaction(Connection c, NewOrder spec, String originOpId) throws SQLException {
        long now = clock.millis();
        JdbcLedger.InsertedOrder row = ledger.insertOrder(c, spec, originOpId, now);
        Order order = Order.create(row.id(), spec.owner(), spec.side(), spec.template(), spec.unitPrice(), spec.quantity(),
                row.seq(), spec.expiresAt());
        MatchPlan plan = engine.plan(order);
        persistPlan(c, plan, now);
        return plan;
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
                    persistPlan(c, plan, now);
                }
                return null;
            });
        }
        return withFills;
    }

    private void persistPlan(Connection c, MatchPlan plan, long now) throws SQLException {
        long incomingRemaining = plan.incomingRemainingBefore();
        for (int i = 0; i < plan.fills().size(); i++) {
            Fill fill = plan.fills().get(i);
            long q = fill.quantity();
            boolean restingFilled = ledger.applyFill(c, fill.restingOrderId(), q, plan.restingRemainingBefore().get(i), now);
            boolean incomingFilled = ledger.applyFill(c, fill.incomingOrderId(), q, incomingRemaining, now);
            incomingRemaining -= q;
            long tradeId = ledger.insertTrade(c, fill, now);
            String ref = Long.toString(tradeId);

            ledger.event(c, fill.restingOrderId(), restingFilled ? "FILLED" : "FILLED_PART", q, tradeId, now);
            ledger.event(c, fill.incomingOrderId(), incomingFilled ? "FILLED" : "FILLED_PART", q, tradeId, now);

            ledger.credit(c, Credit.items(fill.buyer(), fill.template(), q, "TRADE_ITEM", "TRADE", ref), now);
            ledger.credit(c, Credit.money(fill.seller(), fill.total(), "TRADE_MONEY", "TRADE", ref), now);
            Money improvement = fill.priceImprovement();
            if (improvement.minor() > 0) {
                ledger.credit(c, Credit.money(fill.buyer(), improvement, "PRICE_IMPROVEMENT", "TRADE", ref), now);
            }

            long template = fill.template().value();
            ledger.notify(c, fill.seller(), "notify.trade.sold", now, template, q, fill.price().minor());
            ledger.notify(c, fill.buyer(), "notify.trade.bought", now, template, q, fill.price().minor());
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
