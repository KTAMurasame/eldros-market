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
