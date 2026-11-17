package com.eldros.market.core.market;

import com.eldros.market.api.model.OrderSide;
import com.eldros.market.core.domain.Money;
import com.eldros.market.core.domain.NewOrder;
import com.eldros.market.core.domain.PlayerId;
import com.eldros.market.core.effect.EffectLog;
import com.eldros.market.core.safety.SafeMode;
import com.eldros.market.core.storage.jdbc.JdbcLedger.Credit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.eldros.market.core.market.Rig.DIAMOND;
import static org.assertj.core.api.Assertions.assertThat;

/** docs/03 §7.8, docs/04 §3.1 V-S-1…4: persistent write failures, safe mode, and leaving it through recovery. */
class SafeModeTest {

    private static final PlayerId A = new PlayerId(new UUID(0, 1));

    @TempDir
    Path dir;

    private FakeWorld world;
    private Rig rig;
    private final List<Long> sleeps = new ArrayList<>();

    @BeforeEach
    void start() {
        world = new FakeWorld();
        world.balances.put(A, 1_000L);
        world.give(A, DIAMOND, 10);
        rig = Rig.fresh(dir, world, null);
    }

    @AfterEach
    void stop() {
        rig.close();
    }

    private void breakWrites() throws SQLException {
        // Every later write fails as a full or broken disk would: a trigger that aborts any journal update.
        try (Statement s = rig.db.writer().createStatement()) {
            s.execute("CREATE TRIGGER broken_disk BEFORE UPDATE ON journal BEGIN SELECT RAISE(ABORT, 'disk I/O error'); END");
        }
    }

    private void repairWrites() throws SQLException {
        try (Statement s = rig.db.writer().createStatement()) {
            s.execute("DROP TRIGGER broken_disk");
        }
    }

    // V-S-3: a paid buy whose record cannot be written does not lose the money and is never paid twice.
    @Test
    void aBuyThatCannotBeRecordedRetriesEntersSafeModeAndIsRefundedOnRecovery() throws SQLException {
        SafeMode safeMode = new SafeMode();
        MarketOperations ops = new MarketOperations(rig.context, safeMode, sleeps::add);
        breakWrites();

        OpResult result = ops.buy(new NewOrder(A, OrderSide.BUY, DIAMOND, Money.ofMinor(5), 10, null)).join();

        assertThat(result.reason()).isEqualTo(OpResult.Reason.SAFE_MODE);
        assertThat(sleeps).containsExactly(200L, 500L, 1_000L);
        assertThat(safeMode.isActive()).isTrue();
        assertThat(world.balances.get(A)).isEqualTo(950); // the provider did take it

        repairWrites();
        rig.recovery.recoverAtStartup(EffectLog.read(dir.resolve("effects.log"))); // what leaving safe mode runs
        assertThat(rig.rows("SELECT state FROM journal")).containsExactly("RECOVERED");
        assertThat(rig.rows("SELECT amount, reason FROM mailbox")).containsExactly("50|RECOVERY_REFUND");
        assertThat(world.providerCalls).isEqualTo(1);
    }

    // Break: new operations keep starting while the database cannot record them.
    @Test
    void safeModeRejectsNewOperationsBeforeTheyTouchMoneyOrItems() {
        SafeMode safeMode = new SafeMode();
        safeMode.enter("test");
        MarketOperations ops = new MarketOperations(rig.context, safeMode, sleeps::add);

        assertThat(ops.buy(new NewOrder(A, OrderSide.BUY, DIAMOND, Money.ofMinor(5), 1, null)).join().reason())
                .isEqualTo(OpResult.Reason.SAFE_MODE);
        assertThat(ops.sell(new NewOrder(A, OrderSide.SELL, DIAMOND, Money.ofMinor(5), 1, null)).join().reason())
                .isEqualTo(OpResult.Reason.SAFE_MODE);
        assertThat(world.providerCalls).isZero();
        assertThat(rig.number("SELECT COUNT(*) FROM journal")).isZero();
    }

    // V-S-1: a transient failure that clears before the retries run out is invisible to the player.
    @Test
    void aTransientFailureIsRetriedAndSucceeds() throws SQLException {
        SafeMode safeMode = new SafeMode();
        MarketOperations ops = new MarketOperations(rig.context, safeMode, millis -> {
            sleeps.add(millis);
            if (sleeps.size() == 1) {
                try {
                    repairWrites();
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }
        });
        breakWrites();

        OpResult result = ops.buy(new NewOrder(A, OrderSide.BUY, DIAMOND, Money.ofMinor(5), 10, null)).join();

        assertThat(result.status()).isEqualTo(OpResult.Status.DONE);
        assertThat(safeMode.isActive()).isFalse();
        assertThat(rig.rows("SELECT state FROM journal")).containsExactly("APPLIED");
    }

    @Test
    void healthProbeSucceedsOnlyWhenTheDatabaseCanBeWritten() throws SQLException {
        SafeMode safeMode = new SafeMode();
        safeMode.enter("test");
        try (Statement s = rig.db.writer().createStatement()) {
            s.execute("CREATE TRIGGER broken_meta BEFORE INSERT ON meta BEGIN SELECT RAISE(ABORT, 'disk I/O error'); END");
        }
        assertThat(safeMode.probe(rig.context.tx(), 1)).isFalse();

        try (Statement s = rig.db.writer().createStatement()) {
            s.execute("DROP TRIGGER broken_meta");
        }
        assertThat(safeMode.probe(rig.context.tx(), 2)).isTrue();
    }

    @Test
    void outboxDeliversPendingNotificationsOnceMarked() {
        rig.context.tx().inTransaction(c -> {
            rig.context.ledger().notify(c, A, "notify.trade.sold", 0, 1, 2, 3);
            rig.context.ledger().credit(c, Credit.money(A, Money.ofMinor(1), "TRADE_MONEY", "TRADE", "1"), 0);
            return null;
        });

        var pending = rig.context.tx().inTransaction(c -> rig.context.ledger().pendingNotifications(c, A));
        assertThat(pending).singleElement().satisfies(n -> {
            assertThat(n.messageKey()).isEqualTo("notify.trade.sold");
            assertThat(n.args()).containsExactly(1L, 2L, 3L);
        });
        rig.context.tx().inTransaction(c -> {
            rig.context.ledger().markDelivered(c, pending.stream().map(n -> n.id()).toList(), 10);
            return null;
        });
        List<com.eldros.market.core.storage.jdbc.JdbcLedger.Notification> after =
                rig.context.tx().inTransaction(c -> rig.context.ledger().pendingNotifications(c, A));
        assertThat(after).isEmpty();
    }
}
