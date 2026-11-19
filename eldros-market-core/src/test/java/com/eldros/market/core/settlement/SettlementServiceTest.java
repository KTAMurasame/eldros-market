package com.eldros.market.core.settlement;

import com.eldros.market.api.model.OrderSide;
import com.eldros.market.api.model.OrderStatus;
import com.eldros.market.api.model.TemplateId;
import com.eldros.market.core.domain.Money;
import com.eldros.market.core.domain.NewOrder;
import com.eldros.market.core.domain.PlayerId;
import com.eldros.market.core.engine.MatchingEngine;
import com.eldros.market.core.storage.StaleStateException;
import com.eldros.market.core.storage.jdbc.JdbcLedger;
import com.eldros.market.core.storage.jdbc.LedgerInvariants;
import com.eldros.market.core.storage.jdbc.SqliteDatabase;
import com.eldros.market.core.storage.jdbc.TestDatabases;
import com.eldros.market.core.storage.jdbc.TransactionRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.eldros.market.api.model.OrderSide.BUY;
import static com.eldros.market.api.model.OrderSide.SELL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettlementServiceTest {

    private static final PlayerId A = new PlayerId(new UUID(0, 1));
    private static final PlayerId B = new PlayerId(new UUID(0, 2));
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_000_000), ZoneOffset.UTC);

    @TempDir
    Path dir;

    private SqliteDatabase db;
    private TransactionRunner tx;
    private JdbcLedger ledger;
    private MatchingEngine engine;
    private SettlementService settlement;
    private TemplateId diamond;

    @BeforeEach
    void open() {
        db = TestDatabases.openMigrated(dir.resolve("market.db"));
        tx = new TransactionRunner(db.writer());
        ledger = new JdbcLedger();
        engine = new MatchingEngine();
        settlement = new SettlementService(engine, ledger, CLOCK);
        diamond = tx.inTransaction(c -> ledger.createTemplate(c, "minecraft:diamond", new byte[] {1}, 4189, "fp-diamond", CLOCK.millis()));
    }

    @AfterEach
    void close() {
        db.close();
    }

    private NewOrder order(PlayerId owner, OrderSide side, long price, long quantity) {
        return new NewOrder(owner, side, diamond, Money.ofMinor(price), quantity, null);
    }

    private List<String> rows(String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement s = db.writer().createStatement(); ResultSet rs = s.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= n; i++) {
                    row.append(i > 1 ? "|" : "").append(rs.getString(i));
                }
                out.add(row.toString());
            }
        }
        return out;
    }

    // Break: a trade is applied in memory but its money, items or history never reach the database.
    @Test
    void aMatchWritesOrdersTradeCreditsEventsAndNotificationsInOneGo() throws SQLException {
        settlement.place(tx, order(B, SELL, 9, 4), null);
        settlement.place(tx, order(A, BUY, 12, 4), null);

        assertThat(rows("SELECT side, qty_filled, escrow_money, status FROM orders ORDER BY id"))
                .containsExactly("SELL|4|0|FILLED", "BUY|4|0|FILLED");
        assertThat(rows("SELECT buyer_uuid = '" + A.value() + "', seller_uuid = '" + B.value() + "', qty, unit_price FROM trades"))
                .containsExactly("1|1|4|9");
        assertThat(rows("SELECT owner_uuid = '" + A.value() + "', kind, qty, amount, reason FROM mailbox ORDER BY id"))
                .containsExactlyInAnyOrder("1|ITEM|4|0|TRADE_ITEM", "0|MONEY|0|36|TRADE_MONEY", "1|MONEY|0|12|PRICE_IMPROVEMENT");
        assertThat(rows("SELECT type FROM order_events ORDER BY id")).containsExactly("CREATED", "CREATED", "FILLED", "FILLED");
        assertThat(rows("SELECT message_key FROM notifications ORDER BY id")).containsExactly("notify.trade.sold", "notify.trade.bought");
        assertThat(engine.book(diamond).asks()).isEmpty();
        assertThat(LedgerInvariants.check(db.writer())).isEmpty();
    }

    @Test
    void anOrderThatDoesNotMatchIsStoredActiveWithFullEscrow() throws SQLException {
        settlement.place(tx, order(A, BUY, 12, 10), null);

        assertThat(rows("SELECT qty_filled, escrow_money, status FROM orders")).containsExactly("0|120|ACTIVE");
        assertThat(rows("SELECT COUNT(*) FROM trades")).containsExactly("0");
    }

    private NewOrder instant(PlayerId owner, OrderSide side, long price, long quantity) {
        return NewOrder.immediate(owner, side, diamond, Money.ofMinor(price), quantity);
    }

    // Break: an instant sell leaves its unfilled part resting in the book, or the unsold items vanish.
    @Test
    void anInstantSellThatOnlyPartlyFillsReturnsTheRestAndLeavesNothingInTheBook() throws SQLException {
        settlement.place(tx, order(B, BUY, 10, 3), null);

        settlement.place(tx, instant(A, SELL, 10, 5), null);

        assertThat(rows("SELECT side, qty_filled, escrow_money, status FROM orders ORDER BY id"))
                .containsExactly("BUY|3|0|FILLED", "SELL|3|0|CANCELLED");
        assertThat(rows("SELECT owner_uuid = '" + A.value() + "', kind, qty, amount, reason FROM mailbox ORDER BY id"))
                .containsExactlyInAnyOrder("0|ITEM|3|0|TRADE_ITEM", "1|MONEY|0|30|TRADE_MONEY", "1|ITEM|2|0|UNFILLED_REFUND");
        assertThat(rows("SELECT type FROM order_events WHERE order_id = 2 ORDER BY id"))
                .containsExactly("CREATED", "FILLED_PART", "CANCELLED");
        // The instant trader sees the result directly; only the resting side is notified (no duplicate chat line).
        assertThat(rows("SELECT player_uuid = '" + B.value() + "', message_key FROM notifications ORDER BY id"))
                .containsExactly("1|notify.trade.bought");
        assertThat(engine.book(diamond).asks()).isEmpty();
        assertThat(engine.book(diamond).bids()).isEmpty();
        assertThat(LedgerInvariants.check(db.writer())).isEmpty();
    }

    // Break: the unfilled part of an instant buy keeps its escrow, or the price improvement is lost.
    @Test
    void anInstantBuyRefundsUnusedEscrowAndPriceImprovement() throws SQLException {
        settlement.place(tx, order(B, SELL, 8, 2), null);
        settlement.place(tx, order(B, SELL, 10, 1), null);

        settlement.place(tx, instant(A, BUY, 10, 5), null);

        assertThat(rows("SELECT side, qty_filled, escrow_money, status FROM orders WHERE side = 'BUY'"))
                .containsExactly("BUY|3|0|CANCELLED");
        assertThat(rows("SELECT kind, qty, amount, reason FROM mailbox WHERE owner_uuid = '" + A.value() + "' ORDER BY id"))
                .containsExactly("ITEM|2|0|TRADE_ITEM", "MONEY|0|4|PRICE_IMPROVEMENT", "ITEM|1|0|TRADE_ITEM", "MONEY|0|20|UNFILLED_REFUND");
        assertThat(engine.book(diamond).asks()).isEmpty();
        assertThat(engine.book(diamond).bids()).isEmpty();
        assertThat(LedgerInvariants.check(db.writer())).isEmpty();
    }

    // Break: a fully filled instant order is "cancelled" a second time and refunds money it never held.
    @Test
    void aFullyFilledInstantOrderIsSimplyFilled() throws SQLException {
        settlement.place(tx, order(B, BUY, 10, 5), null);

        settlement.place(tx, instant(A, SELL, 10, 5), null);

        assertThat(rows("SELECT status FROM orders ORDER BY id")).containsExactly("FILLED", "FILLED");
        assertThat(rows("SELECT COUNT(*) FROM mailbox WHERE reason = 'UNFILLED_REFUND'")).containsExactly("0");
        assertThat(LedgerInvariants.check(db.writer())).isEmpty();
    }

    // Break: an instant order with nothing to match rests in the book like a normal order.
    @Test
    void anInstantOrderWithNoMatchIsReturnedWhole() throws SQLException {
        settlement.place(tx, instant(A, SELL, 10, 4), null);

        assertThat(rows("SELECT qty_filled, status FROM orders")).containsExactly("0|CANCELLED");
        assertThat(rows("SELECT kind, qty, reason FROM mailbox")).containsExactly("ITEM|4|UNFILLED_REFUND");
        assertThat(engine.book(diamond).asks()).isEmpty();
        assertThat(LedgerInvariants.check(db.writer())).isEmpty();
    }

    // Break: the instant order's own limit is ignored and it trades at a worse price than the player accepted.
    @Test
    void anInstantSellNeverTradesBelowItsLimit() throws SQLException {
        settlement.place(tx, order(B, BUY, 10, 2), null);
        settlement.place(tx, order(B, BUY, 7, 5), null);

        settlement.place(tx, instant(A, SELL, 9, 5), null);

        assertThat(rows("SELECT qty, unit_price FROM trades")).containsExactly("2|10");
        assertThat(engine.book(diamond).bids()).singleElement().satisfies(o -> assertThat(o.unitPrice()).isEqualTo(Money.ofMinor(7)));
    }

    // Break: the engine is rebuilt from a database that still holds the instant order as ACTIVE after a restart.
    @Test
    void afterARestartTheInstantOrderIsNotReloaded() {
        settlement.place(tx, order(B, BUY, 10, 1), null);
        settlement.place(tx, instant(A, SELL, 10, 3), null);

        MatchingEngine fresh = new MatchingEngine();
        new SettlementService(fresh, ledger, CLOCK).loadAndReplay(tx);

        assertThat(fresh.book(diamond).asks()).isEmpty();
        assertThat(fresh.book(diamond).bids()).isEmpty();
    }

    // --- sales tax ---------------------------------------------------------------------------------

    // Break: the tax is charged to the buyer, not deducted from the seller, or never recorded on the trade.
    @Test
    void aTaxedSellerReceivesTheProceedsMinusTheTax() throws SQLException {
        settlement.place(tx, order(A, BUY, 10, 4), null);

        settlement.place(tx, order(B, SELL, 10, 4).withFees(250, Money.ZERO), null); // 2.5% of 40 = 1

        assertThat(rows("SELECT owner_uuid = '" + B.value() + "', kind, qty, amount, reason FROM mailbox ORDER BY id"))
                .containsExactlyInAnyOrder("0|ITEM|4|0|TRADE_ITEM", "1|MONEY|0|39|TRADE_MONEY");
        assertThat(rows("SELECT qty, unit_price, tax FROM trades")).containsExactly("4|10|1");
        assertThat(rows("SELECT message_key, args FROM notifications WHERE player_uuid = '" + B.value() + "'"))
                .containsExactly("notify.trade.sold-taxed|" + diamond.value() + "|4|10|1");
        assertThat(LedgerInvariants.check(db.writer())).isEmpty();
    }

    // Break: the rate of whoever arrives last is used instead of the rate stored on the seller's own order.
    @Test
    void theSellOrdersOwnRateAppliesWhenItIsTheRestingSide() throws SQLException {
        settlement.place(tx, order(B, SELL, 10, 4).withFees(1_000, Money.ZERO), null); // 10%
        settlement.place(tx, order(A, BUY, 10, 4).withFees(5_000, Money.ZERO), null);  // a buy order's rate is never used

        assertThat(rows("SELECT tax FROM trades")).containsExactly("4");
        assertThat(rows("SELECT amount FROM mailbox WHERE reason = 'TRADE_MONEY'")).containsExactly("36");
    }

    @Test
    void untaxedSellersStillGetTheOldNotification() throws SQLException {
        settlement.place(tx, order(A, BUY, 10, 1), null);
        settlement.place(tx, order(B, SELL, 10, 1), null);

        assertThat(rows("SELECT message_key FROM notifications WHERE player_uuid = '" + B.value() + "'"))
                .containsExactly("notify.trade.sold");
        assertThat(rows("SELECT tax FROM trades")).containsExactly("0");
    }

    // Break: editing a sell order drops its tax rate (a free way to dodge the tax).
    @Test
    void anEditedSellOrderKeepsItsTaxRate() throws SQLException {
        settlement.place(tx, order(A, SELL, 10, 4).withFees(1_000, Money.ZERO), null);
        edit(1, 4, 12, 0, 0, 0);

        settlement.place(tx, order(B, BUY, 12, 4), null);

        assertThat(rows("SELECT unit_price, tax FROM trades")).containsExactly("12|4");
    }

    // --- edit (re-price / add quantity) -------------------------------------------------------------

    private SettlementService.EditResult edit(long orderId, long expectedRemaining, long newPrice, long addQty,
                                              long fundedMoney, long fundedItems) {
        return settlement.edit(tx, new SettlementService.EditSpec(A, diamond, orderId, expectedRemaining,
                Money.ofMinor(newPrice), addQty), Money.ofMinor(fundedMoney), fundedItems, null);
    }

    // Break: raising a bid keeps the old escrow, so the order is under-funded (I1) or the extra money vanishes.
    @Test
    void raisingABidReplacesTheOrderWithTheExtraEscrow() throws SQLException {
        settlement.place(tx, order(A, BUY, 5, 10), null);

        SettlementService.EditResult r = edit(1, 10, 7, 0, 20, 0);

        assertThat(r.applied()).isTrue();
        assertThat(rows("SELECT id, unit_price, qty_total, escrow_money, status FROM orders ORDER BY id"))
                .containsExactly("1|5|10|0|CANCELLED", "2|7|10|70|ACTIVE");
        assertThat(rows("SELECT COUNT(*) FROM mailbox")).containsExactly("0");
        assertThat(engine.book(diamond).bids()).singleElement().satisfies(o -> {
            assertThat(o.id()).isEqualTo(2);
            assertThat(o.unitPrice()).isEqualTo(Money.ofMinor(7));
        });
        assertThat(LedgerInvariants.check(db.writer())).isEmpty();
    }

    // Break: lowering a bid keeps the surplus escrow inside the order instead of giving it back.
    @Test
    void loweringABidRefundsTheSurplusToTheMailbox() throws SQLException {
        settlement.place(tx, order(A, BUY, 5, 10), null);

        edit(1, 10, 3, 0, 0, 0);

        assertThat(rows("SELECT unit_price, escrow_money, status FROM orders WHERE id = 2")).containsExactly("3|30|ACTIVE");
        assertThat(rows("SELECT kind, amount, reason FROM mailbox")).containsExactly("MONEY|20|EDIT_REFUND");
        assertThat(LedgerInvariants.check(db.writer())).isEmpty();
    }

    @Test
    void addingToASellOrderUsesTheItemsTakenForIt() throws SQLException {
        settlement.place(tx, order(A, SELL, 9, 5), null);

        edit(1, 5, 9, 3, 0, 3);

        assertThat(rows("SELECT id, qty_total, status FROM orders ORDER BY id")).containsExactly("1|5|CANCELLED", "2|8|ACTIVE");
        assertThat(engine.book(diamond).asks()).singleElement().satisfies(o -> assertThat(o.remaining()).isEqualTo(8));
        assertThat(LedgerInvariants.check(db.writer())).isEmpty();
    }

    // Break: an edited order that now crosses the book rests instead of trading.
    @Test
    void anEditThatCrossesTradesRightAway() throws SQLException {
        settlement.place(tx, order(B, SELL, 6, 4), null);
        settlement.place(tx, order(A, BUY, 5, 10), null);

        edit(2, 10, 6, 0, 10, 0);

        assertThat(rows("SELECT qty, unit_price FROM trades")).containsExactly("4|6");
        assertThat(engine.book(diamond).bids()).singleElement().satisfies(o -> assertThat(o.remaining()).isEqualTo(6));
        assertThat(LedgerInvariants.check(db.writer())).isEmpty();
    }

    // Break: the order filled partly while the extra money was being taken, and the edit is applied to stale numbers.
    @Test
    void ifTheOrderChangedMeanwhileNothingIsReplacedAndTheFundingComesBack() throws SQLException {
        settlement.place(tx, order(A, BUY, 5, 10), null);
        settlement.place(tx, order(B, SELL, 5, 2), null); // fills 2 of A's 10 before the edit lands

        SettlementService.EditResult r = edit(1, 10, 7, 0, 20, 0);

        assertThat(r.applied()).isFalse();
        assertThat(rows("SELECT unit_price, qty_filled, escrow_money, status FROM orders WHERE id = 1")).containsExactly("5|2|40|ACTIVE");
        assertThat(rows("SELECT kind, amount, reason FROM mailbox WHERE reason = 'EDIT_REFUND'")).containsExactly("MONEY|20|EDIT_REFUND");
        assertThat(LedgerInvariants.check(db.writer())).isEmpty();
    }

    @Test
    void editingAnOrderThatIsGoneRefundsItemsTaken() throws SQLException {
        settlement.place(tx, order(A, SELL, 9, 5), null);
        settlement.cancel(tx, 1, OrderStatus.CANCELLED);

        SettlementService.EditResult r = edit(1, 5, 9, 2, 0, 2);

        assertThat(r.applied()).isFalse();
        assertThat(rows("SELECT kind, qty, reason FROM mailbox ORDER BY id")).containsExactly("ITEM|5|CANCEL_REFUND", "ITEM|2|EDIT_REFUND");
    }

    @Test
    void afterARestartOnlyTheReplacementIsLoaded() {
        settlement.place(tx, order(A, BUY, 5, 10), null);
        edit(1, 10, 7, 0, 20, 0);

        MatchingEngine fresh = new MatchingEngine();
        new SettlementService(fresh, ledger, CLOCK).loadAndReplay(tx);

        assertThat(fresh.book(diamond).bids()).singleElement().satisfies(o -> assertThat(o.id()).isEqualTo(2));
    }

    // Break: a stale engine view over-fills a resting order, or a half-written trade survives a failure.
    @Test
    void ifTheDatabaseDisagreesWithTheEngineNothingIsWrittenAndTheEngineIsUnchanged() throws SQLException {
        settlement.place(tx, order(B, SELL, 10, 5), null);
        try (Statement s = db.writer().createStatement()) {
            s.executeUpdate("UPDATE orders SET qty_filled = 2 WHERE id = 1"); // changed behind the engine's back
        }

        assertThatThrownBy(() -> settlement.place(tx, order(A, BUY, 10, 5), null)).isInstanceOf(StaleStateException.class);

        assertThat(rows("SELECT COUNT(*) FROM orders")).containsExactly("1");
        assertThat(rows("SELECT COUNT(*) FROM trades")).containsExactly("0");
        assertThat(rows("SELECT COUNT(*) FROM mailbox")).containsExactly("0");
        assertThat(engine.book(diamond).asks()).singleElement().satisfies(o -> assertThat(o.remaining()).isEqualTo(5));
        assertThat(engine.book(diamond).bids()).isEmpty();
    }

    // Break: a cancel refunds the original amount, or the order stays active in the database.
    @Test
    void cancellingAPartlyFilledBuyRefundsItsRemainingEscrowToTheMailbox() throws SQLException {
        settlement.place(tx, order(A, BUY, 12, 10), null);
        settlement.place(tx, order(B, SELL, 12, 4), null);

        settlement.cancel(tx, 1, OrderStatus.CANCELLED);

        assertThat(rows("SELECT status, escrow_money FROM orders WHERE id = 1")).containsExactly("CANCELLED|0");
        assertThat(rows("SELECT kind, amount, reason, source_type, source_ref FROM mailbox WHERE reason = 'CANCEL_REFUND'"))
                .containsExactly("MONEY|72|CANCEL_REFUND|ORDER|1");
        assertThat(engine.book(diamond).bids()).isEmpty();
        assertThat(LedgerInvariants.check(db.writer())).isEmpty();
    }

    @Test
    void expiringASellReturnsItsRemainingItems() throws SQLException {
        settlement.place(tx, order(A, SELL, 10, 7), null);

        settlement.cancel(tx, 1, OrderStatus.EXPIRED);

        assertThat(rows("SELECT kind, qty, reason FROM mailbox")).containsExactly("ITEM|7|EXPIRE_REFUND");
        assertThat(rows("SELECT message_key FROM notifications")).containsExactly("notify.order.expired");
    }

    // Break: after a restart the book is empty, partial fills are forgotten, or priority changes.
    @Test
    void reopeningTheDatabaseRestoresTheSameBook() {
        settlement.place(tx, order(A, BUY, 12, 10), null);
        settlement.place(tx, order(B, SELL, 12, 4), null);
        settlement.place(tx, order(B, SELL, 15, 3), null);

        MatchingEngine restarted = new MatchingEngine();
        List<?> plans = new SettlementService(restarted, ledger, CLOCK).loadAndReplay(tx);

        assertThat(plans).isEmpty();
        assertThat(restarted.book(diamond).bids()).singleElement().satisfies(o -> {
            assertThat(o.remaining()).isEqualTo(6);
            assertThat(o.escrow()).isEqualTo(Money.ofMinor(72));
        });
        assertThat(restarted.book(diamond).asks()).singleElement().satisfies(o -> assertThat(o.unitPrice()).isEqualTo(Money.ofMinor(15)));
    }

    // docs/03 §6.4: a crash between "order saved" and "order matched" leaves a crossed book on disk.
    @Test
    void aCrossedBookOnDiskIsMatchedAndPersistedOnLoad() throws SQLException {
        tx.inTransaction(c -> {
            ledger.insertOrder(c, order(B, SELL, 9, 2), null, CLOCK.millis());
            ledger.insertOrder(c, order(A, BUY, 12, 2), null, CLOCK.millis());
            return null;
        });

        MatchingEngine restarted = new MatchingEngine();
        new SettlementService(restarted, ledger, CLOCK).loadAndReplay(tx);

        assertThat(rows("SELECT qty, unit_price FROM trades")).containsExactly("2|9");
        assertThat(rows("SELECT status FROM orders ORDER BY id")).containsExactly("FILLED", "FILLED");
        assertThat(restarted.book(diamond).bids()).isEmpty();
        assertThat(LedgerInvariants.check(db.writer())).isEmpty();
    }

    // I1 against storage: in F1 this could not fail; here the escrow comes from disk and can.
    @Test
    void anEscrowThatDoesNotMatchTheRemainingQuantityIsReported() throws SQLException {
        settlement.place(tx, order(A, BUY, 12, 10), null);
        try (Statement s = db.writer().createStatement()) {
            s.executeUpdate("UPDATE orders SET escrow_money = 119 WHERE id = 1");
        }

        assertThat(LedgerInvariants.check(db.writer())).singleElement().asString().contains("I1");
    }

    // Break: the same credit is written twice (e.g. a retried transaction) and money is created.
    @Test
    void theSameCreditCannotBeWrittenTwice() {
        tx.inTransaction(c -> ledger.credit(c, JdbcLedger.Credit.money(A, Money.ofMinor(5), "TRADE_MONEY", "TRADE", "7"), CLOCK.millis()));

        assertThatThrownBy(() -> tx.inTransaction(c ->
                ledger.credit(c, JdbcLedger.Credit.money(A, Money.ofMinor(5), "TRADE_MONEY", "TRADE", "7"), CLOCK.millis())))
                .hasRootCauseInstanceOf(SQLException.class);
    }

    // Second line of defence behind SelfTradeFilter: the CHECK constraint, not a foreign-key error, must refuse it.
    @Test
    void theDatabaseItselfRejectsASelfTrade() {
        settlement.place(tx, order(A, BUY, 9, 1), null);
        settlement.place(tx, order(B, SELL, 10, 1), null);

        assertThatThrownBy(() -> {
            try (Statement s = db.writer().createStatement()) {
                s.executeUpdate("INSERT INTO trades (template_id, buy_order_id, sell_order_id, buyer_uuid, seller_uuid, qty, unit_price, executed_at) "
                        + "VALUES (" + diamond.value() + ", 1, 2, 'x', 'x', 1, 1, 0)");
            }
        }).isInstanceOf(SQLException.class).hasMessageContaining("CHECK constraint failed");
    }
}
