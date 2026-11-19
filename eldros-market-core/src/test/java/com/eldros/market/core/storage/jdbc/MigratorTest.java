package com.eldros.market.core.storage.jdbc;

import com.eldros.market.core.storage.SchemaException;
import com.eldros.market.core.storage.StorageVersionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigratorTest {

    @TempDir
    Path dir;

    private static long count(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void freshDatabaseGetsTheWholeSchemaWithoutABackup() throws SQLException {
        List<Integer> backups = new ArrayList<>();
        try (SqliteDatabase db = TestDatabases.open(dir.resolve("m.db"))) {
            Migrator.Result result = new Migrator(db.writer(), backups::add).migrate();

            assertThat(result.applied()).containsExactly(1, 2);
            assertThat(count(db.writer(), "SELECT COUNT(*) FROM orders")).isZero();
            assertThat(count(db.writer(), "SELECT COUNT(*) FROM schema_version")).isEqualTo(2);
            assertThat(backups).isEmpty();
        }
    }

    // Break: upgrading a 0.1.0 database (schema v1) loses orders or gives old orders a tax they never agreed to.
    @Test
    void aVersionOneDatabaseIsBackedUpAndUpgradedWithoutTaxOnOldOrders() throws SQLException {
        List<Integer> backups = new ArrayList<>();
        try (SqliteDatabase db = TestDatabases.open(dir.resolve("m.db"))) {
            new Migrator(db.writer(), v -> { }, List.of("V1__init.sql")).migrate();
            try (var s = db.writer().createStatement()) {
                s.executeUpdate("INSERT INTO item_templates (material, item_bytes, data_version, created_at) "
                        + "VALUES ('minecraft:diamond', x'01', 4189, 0)");
                s.executeUpdate("INSERT INTO orders (owner_uuid, side, template_id, unit_price, qty_total, qty_filled, "
                        + "escrow_money, status, seq, created_at, updated_at) "
                        + "VALUES ('00000000-0000-0000-0000-000000000001', 'BUY', 1, 5, 10, 0, 50, 'ACTIVE', 1, 0, 0)");
            }

            Migrator.Result result = new Migrator(db.writer(), backups::add).migrate();

            assertThat(backups).containsExactly(1);
            assertThat(result.applied()).containsExactly(2);
            assertThat(count(db.writer(), "SELECT COUNT(*) FROM orders WHERE seller_tax_bps = 0 AND creation_fee = 0")).isEqualTo(1);
        }
    }

    // Break: every restart re-runs CREATE TABLE and fails, or silently re-applies a migration.
    @Test
    void runningAgainChangesNothing() throws SQLException {
        try (SqliteDatabase db = TestDatabases.open(dir.resolve("m.db"))) {
            new Migrator(db.writer(), v -> { }).migrate();
            Migrator.Result second = new Migrator(db.writer(), v -> { }).migrate();

            assertThat(second.applied()).isEmpty();
            assertThat(count(db.writer(), "SELECT COUNT(*) FROM schema_version")).isEqualTo(2);
        }
    }

    // Break: an edited migration file is accepted and the live schema silently differs from the code's idea of it.
    @Test
    void aChangedChecksumIsRefused() throws SQLException {
        try (SqliteDatabase db = TestDatabases.open(dir.resolve("m.db"))) {
            new Migrator(db.writer(), v -> { }).migrate();
            try (Statement s = db.writer().createStatement()) {
                s.executeUpdate("UPDATE schema_version SET checksum = 'tampered' WHERE version = 1");
            }

            assertThatThrownBy(() -> new Migrator(db.writer(), v -> { }).migrate()).isInstanceOf(SchemaException.class);
        }
    }

    // M-U-3: a database written by a newer plugin version must not be opened by an older one.
    @Test
    void aDatabaseFromANewerPluginIsRefused() throws SQLException {
        try (SqliteDatabase db = TestDatabases.open(dir.resolve("m.db"))) {
            new Migrator(db.writer(), v -> { }).migrate();
            try (Statement s = db.writer().createStatement()) {
                s.executeUpdate("INSERT INTO schema_version (version, checksum, applied_at) VALUES (99, 'x', 0)");
            }

            assertThatThrownBy(() -> new Migrator(db.writer(), v -> { }).migrate()).isInstanceOf(SchemaException.class);
        }
    }

    // Break: an existing database is migrated in place without a backup first (docs/03 §5).
    @Test
    void pendingMigrationsOnAnExistingSchemaTriggerOneBackupFirst() throws SQLException {
        List<Integer> backups = new ArrayList<>();
        try (SqliteDatabase db = TestDatabases.open(dir.resolve("m.db"))) {
            new Migrator(db.writer(), v -> { }, List.of("V1__init.sql")).migrate();
            Migrator.Result result = new Migrator(db.writer(), backups::add,
                    List.of("V1__init.sql", "/test-migrations/V2__extra.sql")).migrate();

            assertThat(backups).containsExactly(1);
            assertThat(result.applied()).containsExactly(2);
            assertThat(count(db.writer(), "SELECT COUNT(*) FROM extra_table")).isZero();
        }
    }

    // Break: a lexical compare treats "3.100.0" as older than "3.47.0", or an old engine is accepted.
    @ParameterizedTest
    @ValueSource(strings = {"3.47.0", "3.49.1", "3.100.0", "4.0.0"})
    void acceptsSqlite347AndNewer(String version) {
        Migrator.requireMinimumSqlite(version);
    }

    @ParameterizedTest
    @ValueSource(strings = {"3.46.1", "3.9.99", "2.99.0", "garbage"})
    void refusesOlderOrUnreadableSqlite(String version) {
        assertThatThrownBy(() -> Migrator.requireMinimumSqlite(version)).isInstanceOf(StorageVersionException.class);
    }

    @Test
    void theTestDriverIsAtLeastTheMinimum() {
        try (SqliteDatabase db = TestDatabases.open(dir.resolve("m.db"))) {
            Migrator.requireMinimumSqlite(db.sqliteVersion());
        }
    }

    // Break: STRICT is dropped and a value that cannot be converted is stored (text in a number column).
    // Note: STRICT still accepts losslessly convertible values (1.5 into TEXT becomes '1.5').
    @Test
    void strictTablesRejectValuesThatCannotBeConverted() {
        try (SqliteDatabase db = TestDatabases.openMigrated(dir.resolve("m.db"))) {
            assertThatThrownBy(() -> {
                try (Statement s = db.writer().createStatement()) {
                    s.executeUpdate("INSERT INTO item_templates (material, item_bytes, data_version, created_at) "
                            + "VALUES ('minecraft:stone', x'00', 'abc', 0)");
                }
            }).isInstanceOf(SQLException.class).hasMessageContaining("SQLITE_CONSTRAINT_DATATYPE");
        }
    }

    @Test
    void connectionUsesWalFullSyncAndForeignKeys() throws SQLException {
        try (SqliteDatabase db = TestDatabases.open(dir.resolve("m.db"));
             Statement s = db.writer().createStatement()) {
            try (ResultSet rs = s.executeQuery("PRAGMA journal_mode")) {
                rs.next();
                assertThat(rs.getString(1)).isEqualToIgnoringCase("wal");
            }
            assertThat(count(db.writer(), "PRAGMA synchronous")).isEqualTo(2);
            assertThat(count(db.writer(), "PRAGMA foreign_keys")).isEqualTo(1);
        }
    }
}
