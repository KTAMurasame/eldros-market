package com.eldros.market.core.market;

import com.eldros.market.api.model.OrderSide;
import com.eldros.market.core.domain.Money;
import com.eldros.market.core.domain.NewOrder;
import com.eldros.market.core.domain.PlayerId;
import com.eldros.market.core.storage.StaleStateException;
import com.eldros.market.core.storage.jdbc.JdbcLedger.Credit;
import com.eldros.market.core.storage.jdbc.LedgerInvariants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static com.eldros.market.core.market.Rig.DIAMOND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketOperationsTest {

    private static final PlayerId A = new PlayerId(new UUID(0, 1));
    private static final PlayerId B = new PlayerId(new UUID(0, 2));

    @TempDir
    Path dir;

    private FakeWorld world;
    private Rig rig;

    @BeforeEach
    void start() {
        world = new FakeWorld();
        world.balances.put(A, 1_000L);
        world.balances.put(B, 1_000L);
        world.give(B, DIAMOND, 64);
        rig = Rig.fresh(dir, world, null);
    }

    @AfterEach
    void stop() {
        rig.close();
    }

    private OpResult buy(PlayerId p, long price, long qty) {
        return rig.ops.buy(new NewOrder(p, OrderSide.BUY, DIAMOND, Money.ofMinor(price), qty, null)).join();
    }

    private OpResult sell(PlayerId p, long price, long qty) {
        return rig.ops.sell(new NewOrder(p, OrderSide.SELL, DIAMOND, Money.ofMinor(price), qty, null)).join();
    }

    private long mailboxId(PlayerId owner, String kind) {
        return rig.number("SELECT id FROM mailbox WHERE owner_uuid = '" + owner.value() + "' AND kind = '" + kind
                + "' AND status = 'AVAILABLE' ORDER BY id LIMIT 1");
    }

    // --- end to end --------------------------------------------------------------------------------

    @Test
    void aBuyerAndASellerTradeAndBothCollectFromTheMailbox() {
        assertThat(sell(B, 9, 10).status()).isEqualTo(OpResult.Status.DONE);
        assertThat(buy(A, 12, 10).status()).isEqualTo(OpResult.Status.DONE);

        assertThat(rig.ops.claim(A, mailboxId(A, "ITEM")).join().quantity()).isEqualTo(10);
        assertThat(rig.ops.claim(A, mailboxId(A, "MONEY")).join().status()).isEqualTo(OpResult.Status.DONE); // price improvement 30
        assertThat(rig.ops.claim(B, mailboxId(B, "MONEY")).join().status()).isEqualTo(OpResult.Status.DONE); // 90

        assertThat(world.balances.get(A)).isEqualTo(1_000 - 120 + 30);
        assertThat(world.balances.get(B)).isEqualTo(1_000 + 90);
        assertThat(world.durableItems(A, DIAMOND)).isEqualTo(10);
        assertThat(world.durableItems(B, DIAMOND)).isEqualTo(54);
        assertThat(world.all(A)).isEmpty();
        assertThat(LedgerInvariants.check(rig.db.writer())).isEmpty();
    }

    @Test
    void aBuyWithoutEnoughMoneyIsRejectedAndLeavesNoOrder() {
        OpResult result = buy(A, 500, 3); // 1,500 > 1,000

        assertThat(result.reason()).isEqualTo(OpResult.Reason.INSUFFICIENT_FUNDS);
        assertThat(rig.rows("SELECT state FROM journal")).containsExactly("ABORTED");
        assertThat(rig.number("SELECT COUNT(*) FROM orders")).isZero();
    }

    @Test
    void aSellWithoutTheItemsIsRejectedAndTakesNothing() {
        OpResult result = sell(A, 5, 1);

        assertThat(result.reason()).isEqualTo(OpResult.Reason.NOT_ENOUGH_ITEMS);
        assertThat(rig.rows("SELECT state FROM journal")).containsExactly("ABORTED");
        assertThat(world.all(A)).isEmpty();
    }

    @Test
    void playersCannotCancelSomeoneElsesOrder() {
        sell(B, 5, 1);

        assertThat(rig.ops.cancel(A, 1).join().reason()).isEqualTo(OpResult.Reason.NOT_OWNER);
        assertThat(rig.ops.cancel(B, 1).join().status()).isEqualTo(OpResult.Status.DONE);
        assertThat(rig.rows("SELECT kind, qty, reason FROM mailbox")).containsExactly("ITEM|1|CANCEL_REFUND");
    }

    // --- provider exceptions (V-X, D-X) ---------------------------------------------------------------

    @Test
    void aWithdrawThatThrowsBeforeApplyingIsQuarantinedAndNeverRetried() {
        world.mode = FakeWorld.ProviderMode.THROW_BEFORE;

        OpResult result = buy(A, 5, 2);

        assertThat(result.status()).isEqualTo(OpResult.Status.QUARANTINED);
        assertThat(rig.rows("SELECT state, quarantine_reason FROM journal")).containsExactly("QUARANTINED|VAULT_EXCEPTION");
        assertThat(world.providerCalls).isEqualTo(1);
        assertThat(world.balances.get(A)).isEqualTo(1_000);
    }

    @Test
    void aWithdrawThatThrowsAfterApplyingIsAlsoQuarantinedAndTheAdminRefundsIt() {
        world.mode = FakeWorld.ProviderMode.THROW_AFTER;
        String op = buy(A, 5, 2).opId();
        assertThat(world.balances.get(A)).isEqualTo(990); // the provider did take it

        rig.recovery.resolve(op, true, null, "admin");

        assertThat(rig.rows("SELECT amount, reason FROM mailbox")).containsExactly("10|RESOLVED_REFUND");
        assertThat(world.providerCalls).isEqualTo(1);
    }

    // D-Q-1: a quarantined payout cannot be claimed again (no second deposit).
    @Test
    void aQuarantinedPayoutCannotBeClaimed() {
        rig.context.tx().inTransaction(c -> rig.context.ledger().credit(c, Credit.money(A, Money.ofMinor(30), "TRADE_MONEY", "TRADE", "9"), 0));
        world.mode = FakeWorld.ProviderMode.THROW_AFTER;
        rig.ops.claim(A, 1).join();
        world.mode = FakeWorld.ProviderMode.NORMAL;

        OpResult again = rig.ops.claim(A, 1).join();

        assertThat(again.reason()).isEqualTo(OpResult.Reason.NOT_AVAILABLE);
        assertThat(world.providerCalls).isEqualTo(1);
        assertThat(rig.rows("SELECT status FROM mailbox")).containsExactly("QUARANTINED");
    }

    // --- admin resolution (A-1…A-9, A-I-1…A-I-7) ----------------------------------------------------

    @Test
    void resolvingTwiceOrInTheOtherDirectionIsRefusedAndCreditsOnce() {
        world.mode = FakeWorld.ProviderMode.THROW_AFTER;
        String op = buy(A, 5, 2).opId();
        rig.recovery.resolve(op, true, null, "admin-1");

        assertThatThrownBy(() -> rig.recovery.resolve(op, true, null, "admin-2")).isInstanceOf(StaleStateException.class);
        assertThatThrownBy(() -> rig.recovery.resolve(op, false, null, "admin-2")).isInstanceOf(StaleStateException.class);
        assertThat(rig.number("SELECT COUNT(*) FROM mailbox")).isEqualTo(1);
        assertThat(rig.rows("SELECT state, resolved_by FROM journal")).containsExactly("RESOLVED_APPLIED|admin-1");
    }

    @Test
    void resolvingAnEntryThatIsNotQuarantinedIsRefused() {
        String op = buy(A, 5, 2).opId();

        assertThatThrownBy(() -> rig.recovery.resolve(op, true, null, "admin")).isInstanceOf(StaleStateException.class);
    }

    @Test
    void aNotAppliedPayoutGoesBackToTheMailboxAndCanThenBeClaimedOnce() {
        rig.context.tx().inTransaction(c -> rig.context.ledger().credit(c, Credit.money(A, Money.ofMinor(30), "TRADE_MONEY", "TRADE", "9"), 0));
        world.mode = FakeWorld.ProviderMode.THROW_BEFORE;
        String op = rig.ops.claim(A, 1).join().opId();
        world.mode = FakeWorld.ProviderMode.NORMAL;

        rig.recovery.resolve(op, false, null, "admin");
        OpResult claimed = rig.ops.claim(A, 1).join();

        assertThat(claimed.status()).isEqualTo(OpResult.Status.DONE);
        assertThat(world.balances.get(A)).isEqualTo(1_030);
        assertThat(world.providerCalls).isEqualTo(2); // one that failed before applying, one successful
    }

    // --- items: partial, full, inconsistent, orphan ---------------------------------------------------

    @Test
    void aClaimThatOnlyPartlyFitsDeliversWhatFitsAndKeepsTheRest() {
        rig.context.tx().inTransaction(c -> rig.context.ledger().credit(c, Credit.items(A, DIAMOND, 200, "TRADE_ITEM", "TRADE", "9"), 0));
        world.capacity = 128;

        OpResult first = rig.ops.claim(A, 1).join();

        assertThat(first.quantity()).isEqualTo(128);
        assertThat(rig.rows("SELECT qty, status FROM mailbox")).containsExactly("72|AVAILABLE");
        world.capacity = Long.MAX_VALUE;
        assertThat(rig.ops.claim(A, 1).join().quantity()).isEqualTo(72);
        assertThat(world.durableItems(A, DIAMOND)).isEqualTo(200);
    }

    @Test
    void aClaimIntoAFullInventoryChangesNothing() {
        rig.context.tx().inTransaction(c -> rig.context.ledger().credit(c, Credit.items(A, DIAMOND, 5, "TRADE_ITEM", "TRADE", "9"), 0));
        world.capacity = 0;

        assertThat(rig.ops.claim(A, 1).join().reason()).isEqualTo(OpResult.Reason.INVENTORY_FULL);
        assertThat(rig.rows("SELECT status FROM mailbox")).containsExactly("AVAILABLE");
        assertThat(world.all(A)).isEmpty();
    }

    // U-2: a marker quantity larger than the journal's is inconsistent: quarantine, never an automatic refund.
    @Test
    void anInconsistentMarkerIsQuarantinedAndResolvedWithABoundedQuantity() {
        world.give(A, DIAMOND, 10);
        rig.close();
        Rig r = Rig.fresh(dir.resolve("u2"), world, com.eldros.market.core.port.FaultInjector.CrashPoint.SELL_SAVED);
        Throwable t = org.assertj.core.api.Assertions.catchThrowable(() ->
                r.ops.sell(new NewOrder(A, OrderSide.SELL, DIAMOND, Money.ofMinor(5), 10, null)).join());
        assertThat(t).hasCauseInstanceOf(Rig.SimulatedCrash.class);
        String op = r.rows("SELECT op_id FROM journal").getFirst();
        world.setDurableMarker(A, op, 99); // corrupted: more than was ever removed

        Rig after = r.crashAndRestart();
        after.join(A);

        assertThat(after.rows("SELECT state, quarantine_reason, observed_marker_qty FROM journal"))
                .containsExactly("QUARANTINED|ITEM_INCONSISTENT|99");
        assertThat(after.number("SELECT COUNT(*) FROM mailbox")).isZero();
        assertThat(world.all(A)).containsKey(op); // kept until resolved

        assertThatThrownBy(() -> after.recovery.resolve(op, true, 11L, "admin")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> after.recovery.resolve(op, true, null, "admin")).isInstanceOf(IllegalArgumentException.class);
        after.recovery.resolve(op, true, 10L, "admin");
        assertThat(after.rows("SELECT qty, reason FROM mailbox")).containsExactly("10|RESOLVED_REFUND");

        after.join(A);
        assertThat(world.all(A)).doesNotContainKey(op); // terminal + marker → cleanup
        rig = after; // closed by @AfterEach
    }

    @Test
    void aMarkerWithNoJournalEntryIsReportedAsOrphanAndKept() {
        world.setDurableMarker(A, "unknown-op", 5);

        var report = rig.join(A);

        assertThat(report.orphanMarkers()).containsExactly("unknown-op");
        assertThat(world.all(A)).containsKey("unknown-op");
    }
}
