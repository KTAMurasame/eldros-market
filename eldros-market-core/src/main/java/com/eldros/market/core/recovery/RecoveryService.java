package com.eldros.market.core.recovery;

import com.eldros.market.core.domain.PlayerId;
import com.eldros.market.core.effect.EffectLog.Outcome;
import com.eldros.market.core.journal.JournalEntry;
import com.eldros.market.core.journal.JournalEntry.State;
import com.eldros.market.core.journal.JournalEntry.Type;
import com.eldros.market.core.market.MarketContext;
import com.eldros.market.core.storage.jdbc.JdbcJournal.MailboxRow;
import com.eldros.market.core.storage.jdbc.JdbcLedger.Credit;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Crash recovery (docs/03 §7.4–§7.6). Economy operations are closed from the effect log and never re-run; without a
 * record they are QUARANTINED for an admin. Inventory operations are closed from the player's markers at join.
 * Every case is one transaction guarded by compare-and-set, so running recovery again changes nothing (scenario 6).
 * All methods run on the engine thread.
 */
public final class RecoveryService {

    public record StartupReport(int recovered, int aborted, int quarantined, int replayedFills) {
    }

    /** Markers the caller (main thread) must remove, and markers whose journal entry does not exist (ORPHAN_MARKER). */
    public record ReconcileReport(Set<String> removeMarkers, Set<String> orphanMarkers, int recovered, int aborted, int quarantined) {
    }

    private final MarketContext x;

    public RecoveryService(MarketContext context) {
        this.x = context;
    }

    // --- startup -----------------------------------------------------------------------------------

    public StartupReport recoverAtStartup(Map<String, Outcome> effectOutcomes) {
        int recovered = 0, aborted = 0, quarantined = 0;
        List<JournalEntry> pending = x.tx().inTransaction(c -> x.journal().byState(c, State.PENDING));
        for (JournalEntry e : pending) {
            if (e.type() != Type.BUY_CREATE && e.type() != Type.CLAIM_MONEY) {
                continue; // inventory operations are decided at player join, from the markers
            }
            Outcome outcome = effectOutcomes.get(e.opId());
            if (outcome == Outcome.CONFIRMED) {
                x.tx().inTransaction(c -> {
                    x.journal().transition(c, e.opId(), State.PENDING, State.RECOVERED, null, null, null, null, now());
                    if (e.type() == Type.BUY_CREATE) {
                        x.ledger().credit(c, Credit.money(e.player(), e.amount(), "RECOVERY_REFUND", "JOURNAL", e.opId()), now());
                        x.ledger().notify(c, e.player(), "notify.recovery.refund", now(), e.amount().minor());
                    } else {
                        x.journal().finishClaim(c, e.mailboxId(), e.opId(), 0, now());
                    }
                    return null;
                });
                recovered++;
            } else if (outcome == Outcome.FAILED) {
                x.tx().inTransaction(c -> {
                    x.journal().transition(c, e.opId(), State.PENDING, State.ABORTED, null, null, null, null, now());
                    if (e.type() == Type.CLAIM_MONEY) {
                        x.journal().moveClaim(c, e.mailboxId(), e.opId(), "CLAIMING", "AVAILABLE", now());
                    }
                    return null;
                });
                aborted++;
            } else {
                String reason = outcome == Outcome.UNKNOWN ? "VAULT_EXCEPTION" : "VAULT_OUTCOME_UNKNOWN";
                x.tx().inTransaction(c -> {
                    x.journal().transition(c, e.opId(), State.PENDING, State.QUARANTINED, null, null, reason, null, now());
                    if (e.type() == Type.CLAIM_MONEY) {
                        x.journal().moveClaim(c, e.mailboxId(), e.opId(), "CLAIMING", "QUARANTINED", now());
                    }
                    x.ledger().notify(c, e.player(), "notify.recovery.review", now());
                    return null;
                });
                quarantined++;
            }
        }
        int fills = x.settlement().loadAndReplay(x.tx()).stream().mapToInt(p -> p.fills().size()).sum();
        compactEffectLog();
        return new StartupReport(recovered, aborted, quarantined, fills);
    }

    /** Keeps only effect records whose journal entry may still need them (PENDING or QUARANTINED). */
    private void compactEffectLog() {
        Set<String> keep = new HashSet<>();
        x.tx().inTransaction(c -> {
            x.journal().byState(c, State.PENDING).forEach(e -> keep.add(e.opId()));
            x.journal().byState(c, State.QUARANTINED).forEach(e -> keep.add(e.opId()));
            return null;
        });
        x.effects().compact(keep);
    }

    // --- player join -------------------------------------------------------------------------------

    /** Decision table of docs/03 §7.4 (scenarios 3 and 4, U-2…U-4) for one player, given the markers in their data. */
    public ReconcileReport reconcilePlayer(PlayerId player, Map<String, Long> markers) {
        Set<String> remove = new HashSet<>();
        Set<String> orphans = new HashSet<>();
        int[] counts = new int[3];
        List<JournalEntry> pending = x.tx().inTransaction(c -> x.journal().pendingForPlayer(c, player));
        Set<String> seen = new HashSet<>();
        for (JournalEntry e : pending) {
            if (e.type() != Type.SELL_CREATE && e.type() != Type.CLAIM_ITEM) {
                continue;
            }
            seen.add(e.opId());
            Long n = markers.get(e.opId());
            x.tx().inTransaction(c -> {
                int outcome = e.type() == Type.SELL_CREATE ? reconcileSell(c, e, n) : reconcileClaimItem(c, e, n);
                counts[outcome]++;
                if (outcome == 0 && n != null) {
                    remove.add(e.opId());
                }
                return null;
            });
        }
        for (String opId : markers.keySet()) {
            if (seen.contains(opId)) {
                continue;
            }
            Optional<JournalEntry> e = x.tx().inTransaction(c -> x.journal().find(c, opId));
            if (e.isEmpty()) {
                orphans.add(opId); // ORPHAN_MARKER: kept, reported for an admin (e.g. database restored from backup)
            } else if (e.get().state().isTerminal()) {
                remove.add(opId); // finished earlier; only the cleanup was lost
            }
        }
        return new ReconcileReport(remove, orphans, counts[0], counts[1], counts[2]);
    }

    /** @return 0 recovered, 1 aborted, 2 quarantined */
    private int reconcileSell(Connection c, JournalEntry e, Long n) throws SQLException {
        if (n == null) {
            x.journal().transition(c, e.opId(), State.PENDING, State.ABORTED, null, null, null, null, now());
            return 1;
        }
        if (n <= 0 || n > e.qty()) {
            x.journal().transition(c, e.opId(), State.PENDING, State.QUARANTINED, null, null, "ITEM_INCONSISTENT", n, now());
            return 2;
        }
        x.journal().transition(c, e.opId(), State.PENDING, State.RECOVERED, null, n, null, null, now());
        x.ledger().credit(c, Credit.items(e.player(), e.template(), n, "RECOVERY_REFUND", "JOURNAL", e.opId()), now());
        x.ledger().notify(c, e.player(), "notify.recovery.items", now(), e.template().value(), n);
        return 0;
    }

    private int reconcileClaimItem(Connection c, JournalEntry e, Long n) throws SQLException {
        Optional<MailboxRow> row = x.journal().mailbox(c, e.mailboxId());
        boolean claimingByOp = row.isPresent() && "CLAIMING".equals(row.get().status()) && e.opId().equals(row.get().claimOpId());
        if (n == null) {
            x.journal().transition(c, e.opId(), State.PENDING, State.ABORTED, null, null, null, null, now());
            if (claimingByOp) {
                x.journal().moveClaim(c, e.mailboxId(), e.opId(), "CLAIMING", "AVAILABLE", now());
            }
            return 1;
        }
        if (n <= 0 || n > e.qty() || !claimingByOp || row.get().qty() < n) {
            x.journal().transition(c, e.opId(), State.PENDING, State.QUARANTINED, null, null, "ITEM_INCONSISTENT", n, now());
            if (claimingByOp) {
                x.journal().moveClaim(c, e.mailboxId(), e.opId(), "CLAIMING", "QUARANTINED", now());
            }
            return 2;
        }
        x.journal().transition(c, e.opId(), State.PENDING, State.RECOVERED, null, n, null, null, now());
        x.journal().finishClaim(c, e.mailboxId(), e.opId(), n, now());
        return 0;
    }

    // --- admin -------------------------------------------------------------------------------------

    /**
     * {@code /marketadmin recovery resolve}. {@code applied}: the effect did happen (money was taken / paid, items were
     * removed / delivered); {@code quantity} is required for item cases and bounded by the journal and mailbox.
     * Throws {@link com.eldros.market.core.storage.StaleStateException} if the entry is not QUARANTINED (already resolved).
     */
    public void resolve(String opId, boolean applied, Long quantity, String by) {
        x.tx().inTransaction(c -> {
            JournalEntry e = x.journal().find(c, opId).orElseThrow(() -> new IllegalArgumentException("no journal entry " + opId));
            if (e.state() != State.QUARANTINED) {
                throw new com.eldros.market.core.storage.StaleStateException("journal " + opId + " is " + e.state() + ", not QUARANTINED");
            }
            Long resolvedQty = null;
            switch (e.type()) {
                case BUY_CREATE -> {
                    if (applied) {
                        x.ledger().credit(c, Credit.money(e.player(), e.amount(), "RESOLVED_REFUND", "JOURNAL", opId), now());
                    }
                }
                case CLAIM_MONEY -> {
                    if (applied) {
                        x.journal().resolveQuarantinedClaim(c, e.mailboxId(), opId, 0, now());
                    } else {
                        x.journal().moveClaim(c, e.mailboxId(), opId, "QUARANTINED", "AVAILABLE", now());
                    }
                }
                case SELL_CREATE -> {
                    if (applied) {
                        resolvedQty = requireQuantity(quantity, e.qty(), Long.MAX_VALUE);
                        x.ledger().credit(c, Credit.items(e.player(), e.template(), resolvedQty, "RESOLVED_REFUND", "JOURNAL", opId), now());
                    }
                }
                case CLAIM_ITEM -> {
                    Optional<MailboxRow> row = x.journal().mailbox(c, e.mailboxId());
                    boolean quarantinedClaim = row.isPresent() && "QUARANTINED".equals(row.get().status()) && opId.equals(row.get().claimOpId());
                    if (applied) {
                        long available = quarantinedClaim ? row.get().qty() : 0;
                        resolvedQty = requireQuantity(quantity, e.qty(), available);
                        x.journal().resolveQuarantinedClaim(c, e.mailboxId(), opId, resolvedQty, now());
                    } else if (quarantinedClaim) {
                        x.journal().moveClaim(c, e.mailboxId(), opId, "QUARANTINED", "AVAILABLE", now());
                    }
                }
            }
            x.journal().resolve(c, opId, applied, resolvedQty, by, now());
            return null;
        });
    }

    private static long requireQuantity(Long quantity, long journalQty, long mailboxQty) {
        if (quantity == null || quantity <= 0 || quantity > journalQty || quantity > mailboxQty) {
            throw new IllegalArgumentException("quantity must be 1.." + Math.min(journalQty, mailboxQty) + ": " + quantity);
        }
        return quantity;
    }

    private long now() {
        return x.clock().millis();
    }
}
