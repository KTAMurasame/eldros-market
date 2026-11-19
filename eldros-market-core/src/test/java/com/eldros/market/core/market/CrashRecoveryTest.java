package com.eldros.market.core.market;

import com.eldros.market.api.model.OrderSide;
import com.eldros.market.core.domain.Money;
import com.eldros.market.core.domain.NewOrder;
import com.eldros.market.core.domain.PlayerId;
import com.eldros.market.core.port.FaultInjector.CrashPoint;
import com.eldros.market.core.storage.jdbc.JdbcLedger.Credit;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static com.eldros.market.core.market.Rig.DIAMOND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * docs/04 §3.1, §3.2, §3.4: every crash window of the four external operations. For each point: crash there,
 * restart, run startup recovery and the player's join; run both twice more and require no change (scenario 6);
 * require the journal outcome from docs/03 §7.4, at most one economy call, and — after any quarantine is resolved
 * with the truth an admin would establish — exact conservation of money and items.
 */
class CrashRecoveryTest {

    private static final PlayerId A = new PlayerId(new UUID(0, 1));
    private static final long START_BALANCE = 1_000;
    private static final long START_ITEMS = 20;

    /** Journal state expected after recovery for each crash point (docs/03 §7.4). */
    private static final Map<CrashPoint, String> EXPECTED = Map.ofEntries(
            Map.entry(CrashPoint.BUY_PENDING_WRITTEN, "QUARANTINED"),
            Map.entry(CrashPoint.BUY_PROVIDER_DONE, "QUARANTINED"),
            Map.entry(CrashPoint.BUY_EFFECT_LOGGED, "RECOVERED"),
            Map.entry(CrashPoint.BUY_IN_TRANSACTION, "RECOVERED"),
            Map.entry(CrashPoint.BUY_COMMITTED, "APPLIED"),
            Map.entry(CrashPoint.SELL_PENDING_WRITTEN, "ABORTED"),
            Map.entry(CrashPoint.SELL_REMOVED_UNSAVED, "ABORTED"),
            Map.entry(CrashPoint.SELL_SAVED, "RECOVERED"),
            Map.entry(CrashPoint.SELL_IN_TRANSACTION, "RECOVERED"),
            Map.entry(CrashPoint.SELL_COMMITTED, "APPLIED"),
            Map.entry(CrashPoint.MONEY_CLAIMING_WRITTEN, "QUARANTINED"),
            Map.entry(CrashPoint.MONEY_PROVIDER_DONE, "QUARANTINED"),
            Map.entry(CrashPoint.MONEY_EFFECT_LOGGED, "RECOVERED"),
            Map.entry(CrashPoint.MONEY_IN_TRANSACTION, "RECOVERED"),
            Map.entry(CrashPoint.MONEY_COMMITTED, "APPLIED"),
            Map.entry(CrashPoint.ITEM_CLAIMING_WRITTEN, "ABORTED"),
            Map.entry(CrashPoint.ITEM_ADDED_UNSAVED, "ABORTED"),
            Map.entry(CrashPoint.ITEM_SAVED, "RECOVERED"),
            Map.entry(CrashPoint.ITEM_IN_TRANSACTION, "RECOVERED"),
            Map.entry(CrashPoint.ITEM_COMMITTED, "APPLIED"));

    @TempDir
    Path dir;

    @ParameterizedTest
    @EnumSource(CrashPoint.class)
    void everyCrashWindowRecoversWithoutLossOrDuplication(CrashPoint point) {
        crashAndRecover(point, false);
    }

    // Break: an instant (immediate-or-cancel) order loses or duplicates its unfilled part across a crash.
    @ParameterizedTest
    @EnumSource(value = CrashPoint.class, mode = EnumSource.Mode.MATCH_ANY, names = {"BUY_.*", "SELL_.*"})
    void instantOrdersRecoverFromEveryCrashWindowToo(CrashPoint point) {
        crashAndRecover(point, true);
    }

    // Break: an edit that crashes after taking the extra money or items loses them, or leaves two orders active.
    @ParameterizedTest
    @EnumSource(value = CrashPoint.class, mode = EnumSource.Mode.MATCH_ANY, names = {"BUY_.*", "SELL_.*"})
    void editsRecoverFromEveryCrashWindow(CrashPoint point) {
        FakeWorld world = new FakeWorld();
        world.balances.put(A, START_BALANCE);
        world.give(A, DIAMOND, START_ITEMS);
        Rig rig = Rig.fresh(dir, world, point);
        boolean buy = point.name().startsWith("BUY");
        // The order being edited exists before the crash point is armed: placed straight through settlement.
        rig.context.settlement().place(rig.context.tx(),
                new NewOrder(A, buy ? OrderSide.BUY : OrderSide.SELL, DIAMOND, Money.ofMinor(5), 10, null), null);
        long moneyBefore = totalMoney(rig);
        long itemsBefore = totalItems(rig);

        Throwable thrown = catchThrowable(() -> rig.ops.edit(A, 1, Money.ofMinor(buy ? 7 : 5), buy ? 0 : 3).join());
        assertThat(thrown).isInstanceOf(CompletionException.class).hasCauseInstanceOf(Rig.SimulatedCrash.class);
        int callsAtCrash = world.providerCalls;

        Rig after = rig.crashAndRestart();
        after.join(A);
        List<String> firstPass = after.snapshot();
        for (int i = 0; i < 2; i++) {
            Rig again = after.crashAndRestart();
            again.join(A);
            assertThat(again.snapshot()).as("repeated recovery must change nothing").isEqualTo(firstPass);
            after = again;
        }

        String op = after.rows("SELECT op_id FROM journal").getFirst();
        assertThat(after.rows("SELECT state FROM journal")).containsExactly(EXPECTED.get(point));
        assertThat(world.providerCalls).as("no economy call is ever repeated").isEqualTo(callsAtCrash).isLessThanOrEqualTo(1);
        assertThat(world.all(A)).as("no marker is left behind").isEmpty();
        assertThat(after.number("SELECT COUNT(*) FROM orders WHERE status = 'ACTIVE'")).as("exactly one live order").isEqualTo(1);
        if (EXPECTED.get(point).equals("QUARANTINED")) {
            after.recovery.resolve(op, world.lastCallApplied, null, "test-admin");
        }
        assertThat(totalMoney(after)).as("money conservation").isEqualTo(moneyBefore);
        assertThat(totalItems(after)).as("item conservation").isEqualTo(itemsBefore);
        after.close();
    }

    private void crashAndRecover(CrashPoint point, boolean instant) {
        FakeWorld world = new FakeWorld();
        world.balances.put(A, START_BALANCE);
        world.give(A, DIAMOND, START_ITEMS);
        Rig rig = Rig.fresh(dir, world, point);
        String flow = point.name().substring(0, point.name().indexOf('_'));

        // The money/item claims need something in the mailbox first; it counts as market-held at the start.
        if (flow.equals("MONEY")) {
            rig.context.tx().inTransaction(c -> rig.context.ledger().credit(c, Credit.money(A, Money.ofMinor(30), "TRADE_MONEY", "TRADE", "1"), 0));
        } else if (flow.equals("ITEM")) {
            rig.context.tx().inTransaction(c -> rig.context.ledger().credit(c, Credit.items(A, DIAMOND, 10, "TRADE_ITEM", "TRADE", "1"), 0));
        }
        long moneyBefore = totalMoney(rig);
        long itemsBefore = totalItems(rig);

        Throwable thrown = catchThrowable(() -> {
            switch (flow) {
                case "BUY" -> rig.ops.buy(new NewOrder(A, OrderSide.BUY, DIAMOND, Money.ofMinor(5), 10, null, instant)).join();
                case "SELL" -> rig.ops.sell(new NewOrder(A, OrderSide.SELL, DIAMOND, Money.ofMinor(5), 10, null, instant)).join();
                default -> rig.ops.claim(A, 1).join();
            }
        });
        assertThat(thrown).isInstanceOf(CompletionException.class).hasCauseInstanceOf(Rig.SimulatedCrash.class);
        int callsAtCrash = world.providerCalls;

        Rig after = rig.crashAndRestart();
        after.join(A);
        List<String> firstPass = after.snapshot();
        for (int i = 0; i < 2; i++) { // scenario 6: recovery and join again, twice
            Rig again = after.crashAndRestart();
            again.join(A);
            assertThat(again.snapshot()).as("repeated recovery must change nothing").isEqualTo(firstPass);
            after = again;
        }

        String op = after.rows("SELECT op_id FROM journal").getFirst();
        assertThat(after.rows("SELECT state FROM journal")).containsExactly(EXPECTED.get(point));
        assertThat(world.providerCalls).as("no economy call is ever repeated").isEqualTo(callsAtCrash).isLessThanOrEqualTo(1);
        assertThat(world.all(A)).as("no marker is left behind").isEmpty();

        if (EXPECTED.get(point).equals("QUARANTINED")) {
            after.recovery.resolve(op, world.lastCallApplied, null, "test-admin"); // the truth an admin would establish
        }
        assertThat(totalMoney(after)).as("money conservation").isEqualTo(moneyBefore);
        assertThat(totalItems(after)).as("item conservation").isEqualTo(itemsBefore);
        after.close();
    }

    /** Player balance + escrow of active buys + mailbox money not yet paid out + fees and taxes that left the economy. */
    private static long totalMoney(Rig rig) {
        return rig.world.balances.getOrDefault(A, 0L)
                + rig.number("SELECT COALESCE(SUM(escrow_money), 0) FROM orders WHERE status = 'ACTIVE'")
                + rig.number("SELECT COALESCE(SUM(amount), 0) FROM mailbox WHERE kind = 'MONEY' AND status <> 'CLAIMED'")
                + rig.number("SELECT COALESCE(SUM(creation_fee), 0) FROM orders")
                + rig.number("SELECT COALESCE(SUM(tax), 0) FROM trades");
    }

    // Break: a crash after the escrow + fee withdrawal refunds only the escrow, or charges the fee for an order
    // that never came to exist.
    @ParameterizedTest
    @EnumSource(value = CrashPoint.class, mode = EnumSource.Mode.MATCH_ANY, names = {"BUY_.*"})
    void buyOrdersWithAFeeRecoverFromEveryCrashWindow(CrashPoint point) {
        FakeWorld world = new FakeWorld();
        world.balances.put(A, START_BALANCE);
        Rig rig = Rig.fresh(dir, world, point);
        long before = totalMoney(rig);

        Throwable thrown = catchThrowable(() -> rig.ops.buy(new NewOrder(A, OrderSide.BUY, DIAMOND, Money.ofMinor(5), 10, null)
                .withFees(0, Money.ofMinor(7))).join());
        assertThat(thrown).isInstanceOf(CompletionException.class).hasCauseInstanceOf(Rig.SimulatedCrash.class);

        Rig after = rig.crashAndRestart();
        after.join(A);
        String op = after.rows("SELECT op_id FROM journal").getFirst();
        assertThat(after.rows("SELECT state FROM journal")).containsExactly(EXPECTED.get(point));
        if (EXPECTED.get(point).equals("QUARANTINED")) {
            after.recovery.resolve(op, world.lastCallApplied, null, "test-admin");
        }
        // Applied: 57 left the balance, 50 is escrow and 7 the fee. Not applied: all 57 come back to the mailbox.
        assertThat(totalMoney(after)).as("money conservation incl. the fee").isEqualTo(before);
        if (EXPECTED.get(point).equals("RECOVERED")) {
            assertThat(after.rows("SELECT amount FROM mailbox WHERE reason = 'RECOVERY_REFUND'")).containsExactly("57");
        }
        after.close();
    }

    /** Durable inventory + remaining of active sells + mailbox items not yet delivered. */
    private static long totalItems(Rig rig) {
        return rig.world.durableItems(A, DIAMOND)
                + rig.number("SELECT COALESCE(SUM(qty_total - qty_filled), 0) FROM orders WHERE status = 'ACTIVE' AND side = 'SELL'")
                + rig.number("SELECT COALESCE(SUM(qty), 0) FROM mailbox WHERE kind = 'ITEM' AND status <> 'CLAIMED'");
    }
}
