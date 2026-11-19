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
                        + "origin_op_id, created_at, updated_at, expires_at, seller_tax_bps, creation_fee) "
                        + "VALUES (?, ?, ?, ?, ?, 0, ?, 'ACTIVE', (SELECT COALESCE(MAX(seq), 0) + 1 FROM orders), ?, ?, ?, ?, ?, ?)",
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
            p.setInt(11, spec.side() == OrderSide.SELL ? spec.sellerTaxBps() : 0);
            p.setLong(12, spec.creationFee().minor());
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

    /** Sales tax rate stored on order {@code orderId} (basis points; 0 for buy orders and pre-v2 orders). */
    public int sellerTaxBps(Connection c, long orderId) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT seller_tax_bps FROM orders WHERE id = ?")) {
            p.setLong(1, orderId);
            try (ResultSet rs = p.executeQuery()) {
                if (!rs.next()) {
                    throw new StaleStateException("order " + orderId + " not found");
                }
                return rs.getInt(1);
            }
        }
    }

    public long insertTrade(Connection c, Fill fill, Money tax, long now) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "INSERT INTO trades (template_id, buy_order_id, sell_order_id, buyer_uuid, seller_uuid, qty, unit_price, executed_at, tax) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            p.setLong(9, tax.minor());
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

    // --- templates, meta, players, order queries (used by the paper module) ---------------------------

    public record TemplateRow(long id, String material, byte[] bytes, int dataVersion, String status) {
    }

    public List<TemplateRow> loadTemplates(Connection c) throws SQLException {
        List<TemplateRow> out = new ArrayList<>();
        try (PreparedStatement p = c.prepareStatement("SELECT id, material, item_bytes, data_version, status FROM item_templates ORDER BY id");
             ResultSet rs = p.executeQuery()) {
            while (rs.next()) {
                out.add(new TemplateRow(rs.getLong(1), rs.getString(2), rs.getBytes(3), rs.getInt(4), rs.getString(5)));
            }
        }
        return out;
    }

    public java.util.Optional<TemplateId> templateByFingerprint(Connection c, String fingerprint) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT template_id FROM item_template_fingerprints WHERE fingerprint = ?")) {
            p.setString(1, fingerprint);
            try (ResultSet rs = p.executeQuery()) {
                return rs.next() ? java.util.Optional.of(new TemplateId(rs.getLong(1))) : java.util.Optional.empty();
            }
        }
    }

    /** Adds another fingerprint for an existing template; a fingerprint already known is left as it is. */
    public void addFingerprint(Connection c, String fingerprint, TemplateId template) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "INSERT INTO item_template_fingerprints (fingerprint, template_id) VALUES (?, ?) ON CONFLICT (fingerprint) DO NOTHING")) {
            p.setString(1, fingerprint);
            p.setLong(2, template.value());
            p.executeUpdate();
        }
    }

    /** Version upgrade of one template (docs/03 §8.5): keeps the old bytes in history, guarded by the old data version. */
    public void upgradeTemplate(Connection c, TemplateId template, int oldDataVersion, byte[] newBytes, int newDataVersion, long now)
            throws SQLException {
        try (PreparedStatement p = c.prepareStatement("INSERT INTO item_template_history (template_id, old_bytes, old_data_version, migrated_at) "
                + "SELECT id, item_bytes, data_version, ? FROM item_templates WHERE id = ? AND data_version = ?")) {
            p.setLong(1, now);
            p.setLong(2, template.value());
            p.setInt(3, oldDataVersion);
            if (p.executeUpdate() != 1) {
                throw new StaleStateException("template " + template.value() + " is not at data version " + oldDataVersion);
            }
        }
        try (PreparedStatement p = c.prepareStatement("UPDATE item_templates SET item_bytes = ?, data_version = ? WHERE id = ? AND data_version = ?")) {
            p.setBytes(1, newBytes);
            p.setInt(2, newDataVersion);
            p.setLong(3, template.value());
            p.setInt(4, oldDataVersion);
            p.executeUpdate();
        }
    }

    public void markTemplateInvalid(Connection c, TemplateId template) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("UPDATE item_templates SET status = 'INVALID' WHERE id = ?")) {
            p.setLong(1, template.value());
            p.executeUpdate();
        }
        try (PreparedStatement p = c.prepareStatement("UPDATE orders SET status = 'FROZEN' WHERE template_id = ? AND status = 'ACTIVE'")) {
            p.setLong(1, template.value());
            p.executeUpdate();
        }
    }

    public java.util.Optional<String> meta(Connection c, String key) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT value FROM meta WHERE key = ?")) {
            p.setString(1, key);
            try (ResultSet rs = p.executeQuery()) {
                return rs.next() ? java.util.Optional.of(rs.getString(1)) : java.util.Optional.empty();
            }
        }
    }

    public void setMeta(Connection c, String key, String value) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value")) {
            p.setString(1, key);
            p.setString(2, value);
            p.executeUpdate();
        }
    }

    public java.util.Optional<String> playerLocale(Connection c, PlayerId player) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT locale FROM players WHERE uuid = ?")) {
            p.setString(1, player.value().toString());
            try (ResultSet rs = p.executeQuery()) {
                return rs.next() ? java.util.Optional.ofNullable(rs.getString(1)) : java.util.Optional.empty();
            }
        }
    }

    public void setPlayerLocale(Connection c, PlayerId player, String name, String locale) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("INSERT INTO players (uuid, last_name, locale) VALUES (?, ?, ?) "
                + "ON CONFLICT (uuid) DO UPDATE SET last_name = excluded.last_name, locale = excluded.locale")) {
            p.setString(1, player.value().toString());
            p.setString(2, name);
            p.setString(3, locale);
            p.executeUpdate();
        }
    }

    public record OrderRow(long id, OrderSide side, TemplateId template, Money unitPrice, long quantity, long filled, long createdAt) {
    }

    public List<OrderRow> activeOrdersOf(Connection c, PlayerId player) throws SQLException {
        List<OrderRow> out = new ArrayList<>();
        try (PreparedStatement p = c.prepareStatement("SELECT id, side, template_id, unit_price, qty_total, qty_filled, created_at "
                + "FROM orders WHERE owner_uuid = ? AND status = 'ACTIVE' ORDER BY id")) {
            p.setString(1, player.value().toString());
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) {
                    out.add(new OrderRow(rs.getLong(1), OrderSide.valueOf(rs.getString(2)), new TemplateId(rs.getLong(3)),
                            Money.ofMinor(rs.getLong(4)), rs.getLong(5), rs.getLong(6), rs.getLong(7)));
                }
            }
        }
        return out;
    }

    /** One trade seen from one player's side: {@code bought} is true when the player was the buyer. */
    public record TradeRow(long id, TemplateId template, boolean bought, long quantity, Money unitPrice, long executedAt) {
    }

    /**
     * The player's trades, newest first. {@code side} null = both, BUY = only bought, SELL = only sold.
     * Self-trades cannot exist (CHECK constraint), so a row is never both.
     */
    public List<TradeRow> tradesOf(Connection c, PlayerId player, OrderSide side, int limit, int offset) throws SQLException {
        String where = side == null ? "(buyer_uuid = ?1 OR seller_uuid = ?1)" : side == OrderSide.BUY ? "buyer_uuid = ?1" : "seller_uuid = ?1";
        List<TradeRow> out = new ArrayList<>();
        try (PreparedStatement p = c.prepareStatement("SELECT id, template_id, buyer_uuid = ?1, qty, unit_price, executed_at FROM trades WHERE "
                + where + " ORDER BY executed_at DESC, id DESC LIMIT ?2 OFFSET ?3")) {
            p.setString(1, player.value().toString());
            p.setInt(2, limit);
            p.setInt(3, offset);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) {
                    out.add(new TradeRow(rs.getLong(1), new TemplateId(rs.getLong(2)), rs.getInt(3) == 1, rs.getLong(4),
                            Money.ofMinor(rs.getLong(5)), rs.getLong(6)));
                }
            }
        }
        return out;
    }

    public int countActiveOrders(Connection c, PlayerId player) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT COUNT(*) FROM orders WHERE owner_uuid = ? AND status = 'ACTIVE'")) {
            p.setString(1, player.value().toString());
            try (ResultSet rs = p.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    public record Notification(long id, String messageKey, List<Long> args) {
    }

    /** Undelivered outbox rows of a player, oldest first. */
    public List<Notification> pendingNotifications(Connection c, PlayerId player) throws SQLException {
        List<Notification> out = new ArrayList<>();
        try (PreparedStatement p = c.prepareStatement(
                "SELECT id, message_key, args FROM notifications WHERE player_uuid = ? AND delivered_at IS NULL ORDER BY id")) {
            p.setString(1, player.value().toString());
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) {
                    List<Long> args = new ArrayList<>();
                    for (String part : rs.getString(3).split("\\|")) {
                        if (!part.isEmpty()) {
                            args.add(Long.parseLong(part));
                        }
                    }
                    out.add(new Notification(rs.getLong(1), rs.getString(2), List.copyOf(args)));
                }
            }
        }
        return out;
    }

    /** At-least-once delivery: marked after sending; a crash in between sends again once (scenario 5). */
    public void markDelivered(Connection c, List<Long> ids, long now) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("UPDATE notifications SET delivered_at = ? WHERE id = ? AND delivered_at IS NULL")) {
            for (long id : ids) {
                p.setLong(1, now);
                p.setLong(2, id);
                p.addBatch();
            }
            p.executeBatch();
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
