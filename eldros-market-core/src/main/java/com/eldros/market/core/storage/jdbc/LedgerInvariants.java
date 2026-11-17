package com.eldros.market.core.storage.jdbc;

import com.eldros.market.core.storage.StorageException;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Storage-side invariants for {@code /marketadmin verify} and tests (docs/03 §7.7). Order-level checks here;
 * money/item conservation across the journal (I2/I3) is added with the journal.
 */
public final class LedgerInvariants {

    private LedgerInvariants() {
    }

    public static List<String> check(Connection c) {
        List<String> out = new ArrayList<>();
        try {
            collect(c, out, "I1: active buy order %s holds escrow %s, expected %s",
                    "SELECT id, escrow_money, (qty_total - qty_filled) * unit_price FROM orders "
                            + "WHERE status = 'ACTIVE' AND side = 'BUY' AND escrow_money <> (qty_total - qty_filled) * unit_price");
            collect(c, out, "I1: sell order %s holds money escrow %s",
                    "SELECT id, escrow_money FROM orders WHERE side = 'SELL' AND escrow_money <> 0");
            collect(c, out, "I1: closed order %s (%s) still holds escrow %s",
                    "SELECT id, status, escrow_money FROM orders WHERE status <> 'ACTIVE' AND status <> 'FROZEN' AND escrow_money <> 0");
            collect(c, out, "order %s is ACTIVE but fully filled",
                    "SELECT id FROM orders WHERE status = 'ACTIVE' AND qty_filled >= qty_total");
            collect(c, out, "I4: trade %s has the same buyer and seller",
                    "SELECT id FROM trades WHERE buyer_uuid = seller_uuid");
        } catch (SQLException e) {
            throw new StorageException("invariant check failed", e);
        }
        return out;
    }

    private static void collect(Connection c, List<String> out, String format, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                Object[] values = new Object[n];
                for (int i = 0; i < n; i++) {
                    values[i] = rs.getString(i + 1);
                }
                out.add(String.format(format, values));
            }
        }
    }
}
