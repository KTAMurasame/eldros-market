package com.eldros.market.core.storage.jdbc;

import com.eldros.market.api.model.OrderSide;
import com.eldros.market.api.model.OrderStatus;
import com.eldros.market.api.model.TemplateId;
import com.eldros.market.core.domain.Money;
import com.eldros.market.core.domain.NewOrder;
import com.eldros.market.core.domain.Order;
import com.eldros.market.core.domain.PlayerId;
import com.eldros.market.core.engine.Fill;
import com.eldros.market.core.storage.StaleStateException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * All market SQL against the v1 schema. Every method runs on the caller's connection, so it joins the caller's
 * transaction. Updates of existing rows are compare-and-set: a row that is not in the expected state throws
 * {@link StaleStateException} and the transaction is rolled back (docs/03 §7.6). SQLite 3.47 features only (D10).
 */
public final class JdbcLedger {

    /** One mailbox credit. {@code sourceType/sourceRef/reason} is unique, so a retried write cannot double-credit. */
    public record Credit(PlayerId owner, String kind, TemplateId template, long qty, Money amount,
                         String reason, String sourceType, String sourceRef) {

        public static Credit money(PlayerId owner, Money amount, String reason, String sourceType, String sourceRef) {
            return new Credit(owner, "MONEY", null, 0, amount, reason, sourceType, sourceRef);
        }

        public static Credit items(PlayerId owner, TemplateId template, long qty, String reason, String sourceType, String sourceRef) {
            return new Credit(owner, "ITEM", template, qty, Money.ZERO, reason, sourceType, sourceRef);
        }
    }

    public record InsertedOrder(long id, long seq) {
    }

    public TemplateId createTemplate(Connection c, String material, byte[] bytes, int dataVersion, String fingerprint, long now)
            throws SQLException {
        long id;
        try (PreparedStatement p = c.prepareStatement(
                "INSERT INTO item_templates (material, item_bytes, data_version, created_at) VALUES (?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            p.setString(1, material);
            p.setBytes(2, bytes);
            p.setInt(3, dataVersion);
            p.setLong(4, now);
            p.executeUpdate();
            id = generatedKey(p);
        }
        try (PreparedStatement p = c.prepareStatement("INSERT INTO item_template_fingerprints (fingerprint, template_id) VALUES (?, ?)")) {
            p.setString(1, fingerprint);
            p.setLong(2, id);
            p.executeUpdate();
        }
        return new TemplateId(id);
    }

    /** Inserts an ACTIVE order with full escrow and its CREATED event. seq = next in time priority. */
    public InsertedOrder insertOrder(Connection c, NewOrder spec, String originOpId, long now) throws SQLException {
        Money escrow = spec.side() == OrderSide.BUY ? spec.unitPrice().times(spec.quantity()) : Money.ZERO;
        long id;
        try (PreparedStatement p = c.prepareStatement(
                "INSERT INTO orders (owner_uuid, side, template_id, unit_price, qty_total, qty_filled, escrow_money, status, seq, "
                        + "origin_op_id, created_at, updated_at, expires_at) "
                        + "VALUES (?, ?, ?, ?, ?, 0, ?, 'ACTIVE', (SELECT COALESCE(MAX(seq), 0) + 1 FROM orders), ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            p.setString(1, spec.owner().value().toString());
            p.setString(2, spec.side().name());
            p.setLong(3, spec.template().value());
            p.setLong(4, spec.unitPrice().minor());
            p.setLong(5, spec.quantity());
            p.setLong(6, escrow.minor());
            p.setString(7, originOpId);
            p.setLong(8, now);
            p.setLong(9, now);
            if (spec.expiresAt() == null) {
                p.setNull(10, Types.INTEGER);
            } else {
                p.setLong(10, spec.expiresAt());
            }
            p.executeUpdate();
            id = generatedKey(p);
        }
        long seq;
        try (PreparedStatement p = c.prepareStatement("SELECT seq FROM orders WHERE id = ?")) {
            p.setLong(1, id);
            try (ResultSet rs = p.executeQuery()) {
                rs.next();
                seq = rs.getLong(1);
            }
        }
        event(c, id, "CREATED", spec.quantity(), null, now);
        return new InsertedOrder(id, seq);
    }

    /**
     * Fills {@code qty} of an ACTIVE order whose remaining quantity must be {@code expectedRemaining}. A buy order's
     * escrow drops by {@code qty * own price} (I1). Returns true when the order is now FILLED.
     */
    public boolean applyFill(Connection c, long orderId, long qty, long expectedRemaining, long now) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "UPDATE orders SET qty_filled = qty_filled + ?, "
                        + "escrow_money = escrow_money - CASE WHEN side = 'BUY' THEN unit_price * ? ELSE 0 END, "
                        + "status = CASE WHEN qty_filled + ? = qty_total THEN 'FILLED' ELSE 'ACTIVE' END, updated_at = ? "
                        + "WHERE id = ? AND status = 'ACTIVE' AND qty_total - qty_filled = ?")) {
            p.setLong(1, qty);
            p.setLong(2, qty);
            p.setLong(3, qty);
            p.setLong(4, now);
            p.setLong(5, orderId);
            p.setLong(6, expectedRemaining);
            if (p.executeUpdate() != 1) {
                throw new StaleStateException("order " + orderId + " is not active with " + expectedRemaining + " remaining");
            }
        }
        return expectedRemaining == qty;
    }

    /** Ends an ACTIVE order whose escrow and remaining quantity must match what the engine planned to refund. */
    public void closeOrder(Connection c, long orderId, OrderStatus status, Money expectedEscrow, long expectedRemaining, long now)
            throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "UPDATE orders SET status = ?, escrow_money = 0, updated_at = ? "
                        + "WHERE id = ? AND status = 'ACTIVE' AND escrow_money = ? AND qty_total - qty_filled = ?")) {
            p.setString(1, status.name());
            p.setLong(2, now);
            p.setLong(3, orderId);
            p.setLong(4, expectedEscrow.minor());
            p.setLong(5, expectedRemaining);
            if (p.executeUpdate() != 1) {
                throw new StaleStateException("order " + orderId + " cannot be closed: state differs from the plan");
            }
        }
    }

    public long insertTrade(Connection c, Fill fill, long now) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "INSERT INTO trades (template_id, buy_order_id, sell_order_id, buyer_uuid, seller_uuid, qty, unit_price, executed_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            p.setLong(1, fill.template().value());
            p.setLong(2, fill.buyOrderId());
            p.setLong(3, fill.sellOrderId());
            p.setString(4, fill.buyer().value().toString());
            p.setString(5, fill.seller().value().toString());
            p.setLong(6, fill.quantity());
            p.setLong(7, fill.price().minor());
            p.setLong(8, now);
            p.executeUpdate();
            return generatedKey(p);
        }
    }

    public long credit(Connection c, Credit credit, long now) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "INSERT INTO mailbox (owner_uuid, kind, template_id, qty, amount, reason, source_type, source_ref, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'AVAILABLE', ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            p.setString(1, credit.owner().value().toString());
            p.setString(2, credit.kind());
            if (credit.template() == null) {
                p.setNull(3, Types.INTEGER);
            } else {
                p.setLong(3, credit.template().value());
            }
            p.setLong(4, credit.qty());
            p.setLong(5, credit.amount().minor());
            p.setString(6, credit.reason());
            p.setString(7, credit.sourceType());
            p.setString(8, credit.sourceRef());
            p.setLong(9, now);
            p.setLong(10, now);
            p.executeUpdate();
            return generatedKey(p);
        }
    }

    public void event(Connection c, long orderId, String type, long qty, Long refId, long now) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("INSERT INTO order_events (order_id, type, qty, ref_id, at) VALUES (?, ?, ?, ?, ?)")) {
            p.setLong(1, orderId);
            p.setString(2, type);
            p.setLong(3, qty);
            if (refId == null) {
                p.setNull(4, Types.INTEGER);
            } else {
                p.setLong(4, refId);
            }
            p.setLong(5, now);
            p.executeUpdate();
        }
    }

    /** Outbox row (docs/03 §7.4, scenario 5). Args are numbers only, rendered in the player's language at send time. */
    public void notify(Connection c, PlayerId player, String messageKey, long now, long... args) throws SQLException {
        StringBuilder joined = new StringBuilder();
        for (long arg : args) {
            joined.append(joined.isEmpty() ? "" : "|").append(arg);
        }
        try (PreparedStatement p = c.prepareStatement(
                "INSERT INTO notifications (player_uuid, message_key, args, created_at) VALUES (?, ?, ?, ?)")) {
            p.setString(1, player.value().toString());
            p.setString(2, messageKey);
            p.setString(3, joined.toString());
            p.setLong(4, now);
            p.executeUpdate();
        }
    }

    public List<Order> loadActiveOrders(Connection c) throws SQLException {
        List<Order> out = new ArrayList<>();
        try (PreparedStatement p = c.prepareStatement(
                "SELECT id, owner_uuid, side, template_id, unit_price, qty_total, qty_filled, seq, escrow_money, expires_at "
                        + "FROM orders WHERE status = 'ACTIVE' ORDER BY seq");
             ResultSet rs = p.executeQuery()) {
            while (rs.next()) {
                long expires = rs.getLong(10);
                Long expiresAt = rs.wasNull() ? null : expires;
                out.add(Order.restore(rs.getLong(1), new PlayerId(UUID.fromString(rs.getString(2))), OrderSide.valueOf(rs.getString(3)),
                        new TemplateId(rs.getLong(4)), Money.ofMinor(rs.getLong(5)), rs.getLong(6), rs.getLong(7), rs.getLong(8),
                        Money.ofMinor(rs.getLong(9)), expiresAt));
            }
        }
        return out;
    }

    private static long generatedKey(PreparedStatement p) throws SQLException {
        try (ResultSet keys = p.getGeneratedKeys()) {
            if (!keys.next()) {
                throw new SQLException("no generated key");
            }
            return keys.getLong(1);
        }
    }
}
