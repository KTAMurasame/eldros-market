package com.eldros.market.core.safety;

import com.eldros.market.core.storage.StorageException;
import com.eldros.market.core.storage.jdbc.TransactionRunner;

import java.sql.PreparedStatement;

/**
 * Market-wide safe mode (docs/03 §7.8). Entered when a write keeps failing after retries; while active no new
 * operation starts. Leaving it is the caller's job once {@link #probe} succeeds: rebuild the engine and run startup
 * recovery, which closes every half-finished operation through the already-tested recovery paths.
 */
public final class SafeMode {

    private volatile boolean active;
    private volatile String reason;

    public void enter(String why) {
        reason = why;
        active = true;
    }

    public void exit() {
        active = false;
        reason = null;
    }

    public boolean isActive() {
        return active;
    }

    public String reason() {
        return reason;
    }

    /** One small write; true when the database accepts writes again. Engine thread. */
    public boolean probe(TransactionRunner tx, long now) {
        try {
            tx.inTransaction(c -> {
                try (PreparedStatement p = c.prepareStatement(
                        "INSERT INTO meta (key, value) VALUES ('health_probe', ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value")) {
                    p.setString(1, Long.toString(now));
                    p.executeUpdate();
                }
                return null;
            });
            return true;
        } catch (StorageException e) {
            return false;
        }
    }
}
