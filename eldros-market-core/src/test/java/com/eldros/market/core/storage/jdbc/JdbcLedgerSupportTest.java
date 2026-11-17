package com.eldros.market.core.storage.jdbc;

import com.eldros.market.api.model.TemplateId;
import com.eldros.market.core.domain.PlayerId;
import com.eldros.market.core.storage.StaleStateException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Support queries used by the paper module: templates, fingerprints, version upgrade, meta and player locale. */
class JdbcLedgerSupportTest {

    @TempDir
    Path dir;

    private SqliteDatabase db;
    private TransactionRunner tx;
    private final JdbcLedger ledger = new JdbcLedger();

    @BeforeEach
    void open() {
        db = TestDatabases.openMigrated(dir.resolve("m.db"));
        tx = new TransactionRunner(db.writer());
    }

    @AfterEach
    void close() {
        db.close();
    }

    @Test
    void aTemplateIsFoundByEveryFingerprintItWasGiven() {
        TemplateId id = tx.inTransaction(c -> ledger.createTemplate(c, "minecraft:stone", new byte[] {1}, 10, "fp-a", 0));
        tx.inTransaction(c -> {
            ledger.addFingerprint(c, "fp-b", id);
            ledger.addFingerprint(c, "fp-b", id); // repeated: no error, no duplicate
            return null;
        });

        assertThat(tx.<java.util.Optional<TemplateId>>inTransaction(c -> ledger.templateByFingerprint(c, "fp-a"))).contains(id);
        assertThat(tx.<java.util.Optional<TemplateId>>inTransaction(c -> ledger.templateByFingerprint(c, "fp-b"))).contains(id);
        assertThat(tx.<java.util.Optional<TemplateId>>inTransaction(c -> ledger.templateByFingerprint(c, "fp-c"))).isEmpty();
    }

    // Break: an interrupted upgrade runs twice and overwrites the history, or upgrades from the wrong version.
    @Test
    void anUpgradeKeepsTheOldBytesAndRunsOnlyFromTheExpectedVersion() {
        TemplateId id = tx.inTransaction(c -> ledger.createTemplate(c, "minecraft:stone", new byte[] {1}, 10, "fp", 0));

        tx.inTransaction(c -> {
            ledger.upgradeTemplate(c, id, 10, new byte[] {2}, 20, 5);
            return null;
        });

        assertThat(tx.<java.util.List<JdbcLedger.TemplateRow>>inTransaction(ledger::loadTemplates)).singleElement().satisfies(t -> {
            assertThat(t.dataVersion()).isEqualTo(20);
            assertThat(t.bytes()).containsExactly(2);
        });
        assertThatThrownBy(() -> tx.inTransaction(c -> {
            ledger.upgradeTemplate(c, id, 10, new byte[] {3}, 30, 6);
            return null;
        })).isInstanceOf(StaleStateException.class);
    }

    @Test
    void metaAndPlayerLocaleAreStoredAndReplaced() {
        PlayerId p = new PlayerId(new UUID(0, 7));
        tx.inTransaction(c -> {
            ledger.setMeta(c, "server_data_version", "4189");
            ledger.setMeta(c, "server_data_version", "5023");
            ledger.setPlayerLocale(c, p, "Steve", "tr_TR");
            return null;
        });

        assertThat(tx.<java.util.Optional<String>>inTransaction(c -> ledger.meta(c, "server_data_version"))).contains("5023");
        assertThat(tx.<java.util.Optional<String>>inTransaction(c -> ledger.playerLocale(c, p))).contains("tr_TR");
        assertThat(tx.<java.util.Optional<String>>inTransaction(c -> ledger.playerLocale(c, new PlayerId(new UUID(0, 8))))).isEmpty();
    }
}
