package com.eldros.market.core.storage.jdbc;

import com.eldros.market.api.model.TemplateId;
import com.eldros.market.core.domain.Money;
import com.eldros.market.core.domain.PlayerId;
import com.eldros.market.core.journal.JournalEntry;
import com.eldros.market.core.journal.JournalEntry.State;
import com.eldros.market.core.journal.JournalEntry.Type;
import com.eldros.market.core.storage.StaleStateException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Journal and mailbox-claim SQL (docs/03 §7). Every state change is compare-and-set on the expected state: a second
 * run of the same step finds nothing to change and throws {@link StaleStateException} (docs/03 §7.6, scenario 6).
 */
public final class JdbcJournal {

    private static final String COLUMNS = "op_id, type, player_uuid, state, template_id, qty, qty_effective, unit_price, "
            + "amount, mailbox_id, order_id, quarantine_reason, observed_marker_qty, attempts, created_at";

    public void insertPending(Connection c, JournalEntry e) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("INSERT INTO journal (op_id, type, player_uuid, state, template_id, qty, "
                + "unit_price, amount, mailbox_id, created_at, updated_at) VALUES (?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, ?, ?)")) {
            p.setString(1, e.opId());
            p.setString(2, e.type().name());
            p.setString(3, e.player().value().toString());
            setLong(p, 4, e.template() == null ? null : e.template().value());
            setLong(p, 5, e.qty());
            setLong(p, 6, e.unitPrice() == null ? null : e.unitPrice().minor());
            setLong(p, 7, e.amount() == null ? null : e.amount().minor());
            setLong(p, 8, e.mailboxId());
            p.setLong(9, e.createdAt());
            p.setLong(10, e.createdAt());
            p.executeUpdate();
        }
    }

    /** Moves an entry from {@code from} to {@code to}, optionally recording order id / effective quantity / reason. */
    public void transition(Connection c, String opId, State from, State to, Long orderId, Long qtyEffective,
                           String quarantineReason, Long observedMarkerQty, long now) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("UPDATE journal SET state = ?, order_id = COALESCE(?, order_id), "
                + "qty_effective = COALESCE(?, qty_effective), quarantine_reason = COALESCE(?, quarantine_reason), "
                + "observed_marker_qty = COALESCE(?, observed_marker_qty), attempts = attempts + 1, updated_at = ? "
                + "WHERE op_id = ? AND state = ?")) {
            p.setString(1, to.name());
            setLong(p, 2, orderId);
            setLong(p, 3, qtyEffective);
            p.setString(4, quarantineReason);
            setLong(p, 5, observedMarkerQty);
            p.setLong(6, now);
            p.setString(7, opId);
            p.setString(8, from.name());
            if (p.executeUpdate() != 1) {
                throw new StaleStateException("journal " + opId + " is not " + from);
            }
        }
    }

    /** Admin resolution of a QUARANTINED entry (docs/03 §7.4). */
    public void resolve(Connection c, String opId, boolean applied, Long resolvedQty, String by, long now) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("UPDATE journal SET state = ?, resolution = ?, resolved_qty = ?, "
                + "resolved_by = ?, resolved_at = ?, updated_at = ? WHERE op_id = ? AND state = 'QUARANTINED'")) {
            p.setString(1, applied ? State.RESOLVED_APPLIED.name() : State.RESOLVED_NOT_APPLIED.name());
            p.setString(2, applied ? "applied" : "not-applied");
            setLong(p, 3, resolvedQty);
            p.setString(4, by);
            p.setLong(5, now);
            p.setLong(6, now);
            p.setString(7, opId);
            if (p.executeUpdate() != 1) {
                throw new StaleStateException("journal " + opId + " is not quarantined");
            }
        }
    }

    public Optional<JournalEntry> find(Connection c, String opId) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT " + COLUMNS + " FROM journal WHERE op_id = ?")) {
            p.setString(1, opId);
            try (ResultSet rs = p.executeQuery()) {
                return rs.next() ? Optional.of(row(rs)) : Optional.empty();
            }
        }
    }

    public List<JournalEntry> byState(Connection c, State state) throws SQLException {
        return query(c, "SELECT " + COLUMNS + " FROM journal WHERE state = ? ORDER BY created_at, op_id", state.name());
    }

    public List<JournalEntry> pendingForPlayer(Connection c, PlayerId player) throws SQLException {
        return query(c, "SELECT " + COLUMNS + " FROM journal WHERE state = 'PENDING' AND player_uuid = ? ORDER BY created_at, op_id",
                player.value().toString());
    }

    // --- mailbox claim transitions -----------------------------------------------------------------

    public record MailboxRow(long id, PlayerId owner, String kind, TemplateId template, long qty, Money amount,
                             String status, String claimOpId, long version, String reason) {
    }

    public Optional<MailboxRow> mailbox(Connection c, long id) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT id, owner_uuid, kind, template_id, qty, amount, status, claim_op_id, "
                + "version, reason FROM mailbox WHERE id = ?")) {
            p.setLong(1, id);
            try (ResultSet rs = p.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                long template = rs.getLong(4);
                TemplateId t = rs.wasNull() ? null : new TemplateId(template);
                return Optional.of(new MailboxRow(rs.getLong(1), new PlayerId(UUID.fromString(rs.getString(2))), rs.getString(3), t,
                        rs.getLong(5), Money.ofMinor(rs.getLong(6)), rs.getString(7), rs.getString(8), rs.getLong(9), rs.getString(10)));
            }
        }
    }

    public List<MailboxRow> availableFor(Connection c, PlayerId owner) throws SQLException {
        List<MailboxRow> out = new ArrayList<>();
        try (PreparedStatement p = c.prepareStatement("SELECT id FROM mailbox WHERE owner_uuid = ? AND status = 'AVAILABLE' ORDER BY id")) {
            p.setString(1, owner.value().toString());
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) {
                    mailbox(c, rs.getLong(1)).ifPresent(out::add);
                }
            }
        }
        return out;
    }

    /** AVAILABLE → CLAIMING for this owner, binding the claim to an op id. */
    public void startClaim(Connection c, long mailboxId, PlayerId owner, String opId, long now) throws SQLException {
        mailboxUpdate(c, "status = 'CLAIMING', claim_op_id = ?", "id = ? AND owner_uuid = ? AND status = 'AVAILABLE'",
                now, ps -> {
                    ps.setString(1, opId);
                    ps.setLong(2, mailboxId);
                    ps.setString(3, owner.value().toString());
                }, "mailbox " + mailboxId + " is not available to " + owner.value());
    }

    /** CLAIMING (by {@code opId}) → CLAIMED, or for a partial item claim: qty reduced and back to AVAILABLE. */
    public void finishClaim(Connection c, long mailboxId, String opId, long claimedQty, long now) throws SQLException {
        mailboxUpdate(c, "qty = CASE WHEN kind = 'ITEM' AND ? < qty THEN qty - ? ELSE qty END, "
                        + "status = CASE WHEN kind = 'ITEM' AND ? < qty THEN 'AVAILABLE' ELSE 'CLAIMED' END, "
                        + "claim_op_id = CASE WHEN kind = 'ITEM' AND ? < qty THEN NULL ELSE claim_op_id END",
                "id = ? AND status = ? AND claim_op_id = ?", now, ps -> {
                    ps.setLong(1, claimedQty);
                    ps.setLong(2, claimedQty);
                    ps.setLong(3, claimedQty);
                    ps.setLong(4, claimedQty);
                    ps.setLong(5, mailboxId);
                    ps.setString(6, "CLAIMING");
                    ps.setString(7, opId);
                }, "mailbox " + mailboxId + " is not being claimed by " + opId);
    }

    /** Moves a claim bound to {@code opId} from one status to another (e.g. CLAIMING → AVAILABLE / QUARANTINED). */
    public void moveClaim(Connection c, long mailboxId, String opId, String from, String to, long now) throws SQLException {
        mailboxUpdate(c, "status = ?, claim_op_id = CASE WHEN ? = 'AVAILABLE' THEN NULL ELSE claim_op_id END",
                "id = ? AND status = ? AND claim_op_id = ?", now, ps -> {
                    ps.setString(1, to);
                    ps.setString(2, to);
                    ps.setLong(3, mailboxId);
                    ps.setString(4, from);
                    ps.setString(5, opId);
                }, "mailbox " + mailboxId + " is not " + from + " for " + opId);
    }

    /** QUARANTINED → CLAIMED (all), or {@code qty} reduced and back to AVAILABLE (partial), for an admin resolution. */
    public void resolveQuarantinedClaim(Connection c, long mailboxId, String opId, long claimedQty, long now) throws SQLException {
        mailboxUpdate(c, "qty = CASE WHEN kind = 'ITEM' AND ? < qty THEN qty - ? ELSE qty END, "
                        + "status = CASE WHEN kind = 'ITEM' AND ? < qty THEN 'AVAILABLE' ELSE 'CLAIMED' END, "
                        + "claim_op_id = CASE WHEN kind = 'ITEM' AND ? < qty THEN NULL ELSE claim_op_id END",
                "id = ? AND status = 'QUARANTINED' AND claim_op_id = ?", now, ps -> {
                    ps.setLong(1, claimedQty);
                    ps.setLong(2, claimedQty);
                    ps.setLong(3, claimedQty);
                    ps.setLong(4, claimedQty);
                    ps.setLong(5, mailboxId);
                    ps.setString(6, opId);
                }, "mailbox " + mailboxId + " is not quarantined for " + opId);
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement p) throws SQLException;
    }

    private void mailboxUpdate(Connection c, String set, String where, long now, Binder binder, String staleMessage) throws SQLException {
        // `now` is a long we generate, never user input, so inlining it is safe; caller parameters stay bound.
        String sql = "UPDATE mailbox SET " + set + ", version = version + 1, updated_at = " + now + " WHERE " + where;
        try (PreparedStatement p = c.prepareStatement(sql)) {
            binder.bind(p);
            if (p.executeUpdate() != 1) {
                throw new StaleStateException(staleMessage);
            }
        }
    }

    private List<JournalEntry> query(Connection c, String sql, String arg) throws SQLException {
        List<JournalEntry> out = new ArrayList<>();
        try (PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, arg);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) {
                    out.add(row(rs));
                }
            }
        }
        return out;
    }

    private static JournalEntry row(ResultSet rs) throws SQLException {
        Long template = longOrNull(rs, 5);
        Long price = longOrNull(rs, 8);
        Long amount = longOrNull(rs, 9);
        return new JournalEntry(rs.getString(1), Type.valueOf(rs.getString(2)), new PlayerId(UUID.fromString(rs.getString(3))),
                State.valueOf(rs.getString(4)), template == null ? null : new TemplateId(template), longOrNull(rs, 6),
                longOrNull(rs, 7), price == null ? null : Money.ofMinor(price), amount == null ? null : Money.ofMinor(amount),
                longOrNull(rs, 10), longOrNull(rs, 11), rs.getString(12), longOrNull(rs, 13), rs.getInt(14), rs.getLong(15));
    }

    private static Long longOrNull(ResultSet rs, int column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    private static void setLong(PreparedStatement p, int index, Long value) throws SQLException {
        if (value == null) {
            p.setNull(index, Types.INTEGER);
        } else {
            p.setLong(index, value);
        }
    }
}
