package com.eldros.market.paper;

import com.eldros.market.core.storage.jdbc.JdbcJournal.MailboxRow;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which mailbox rows {@code mailbox.auto-collect-money} deposits, and the wait after a failed deposit. Only money that
 * is plainly available: items stay in the mailbox (the inventory may be full), and rows being claimed or in
 * quarantine are never touched. Pure Java so it is tested without a server.
 */
public final class AutoCollectPolicy {

    private AutoCollectPolicy() {
    }

    /** Available money rows in the given (oldest first) order, at most {@code max}. */
    public static List<MailboxRow> pick(List<MailboxRow> rows, int max) {
        return rows.stream()
                .filter(r -> "MONEY".equals(r.kind()) && "AVAILABLE".equals(r.status()))
                .limit(max)
                .toList();
    }

    /** After a failed or uncertain deposit, a player is skipped for a while instead of being retried every round. */
    public static final class Backoff {

        private final long waitMillis;
        private final Map<UUID, Long> until = new ConcurrentHashMap<>();

        public Backoff(long waitMillis) {
            this.waitMillis = waitMillis;
        }

        public boolean ready(UUID player, long now) {
            Long u = until.get(player);
            return u == null || now >= u;
        }

        public void failed(UUID player, long now) {
            until.put(player, now + waitMillis);
        }

        public void forget(UUID player) {
            until.remove(player);
        }
    }
}
