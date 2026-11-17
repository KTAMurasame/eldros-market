package com.eldros.market.core.storage.jdbc;

import com.eldros.market.core.storage.SchemaException;
import com.eldros.market.core.storage.StorageException;
import com.eldros.market.core.storage.StorageVersionException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies the bundled SQL migrations in order (docs/03 §5). Each migration runs in its own transaction together with
 * its {@code schema_version} row. Refuses an edited migration (checksum) and a schema written by a newer plugin.
 * Before migrating a database that already has a schema, the backup hook is called once with its current version.
 */
public final class Migrator {

    public static final List<String> MIGRATIONS = List.of("V1__init.sql");

    private static final String BASE = "/com/eldros/market/core/storage/migration/";
    private static final Pattern NAME = Pattern.compile("V(\\d+)__[^/]*\\.sql$");
    private static final int[] MINIMUM_SQLITE = {3, 47, 0};

    /** Called before an existing schema is changed, with the version it is at. Copies the database file. */
    @FunctionalInterface
    public interface BackupHook {
        void backup(int fromVersion);
    }

    public record Result(int fromVersion, List<Integer> applied) {
    }

    private final Connection connection;
    private final BackupHook backupHook;
    private final List<String> migrations;

    public Migrator(Connection connection, BackupHook backupHook) {
        this(connection, backupHook, MIGRATIONS);
    }

    Migrator(Connection connection, BackupHook backupHook, List<String> migrations) {
        this.connection = connection;
        this.backupHook = backupHook;
        this.migrations = migrations;
    }

    public Result migrate() {
        try {
            createVersionTable();
            Map<Integer, String> applied = appliedChecksums();
            Map<Integer, String> bundled = new TreeMap<>();
            for (String name : migrations) {
                bundled.put(versionOf(name), name);
            }
            int known = bundled.isEmpty() ? 0 : ((TreeMap<Integer, String>) bundled).lastKey();
            for (int version : applied.keySet()) {
                if (version > known) {
                    throw new SchemaException("database schema v" + version + " is newer than this plugin (v" + known + "); "
                            + "install the newer Eldros Market or restore a backup");
                }
                String expected = checksum(read(bundled.get(version)));
                if (!expected.equals(applied.get(version))) {
                    throw new SchemaException("migration v" + version + " differs from the one applied to this database");
                }
            }
            int current = applied.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
            List<Integer> done = new ArrayList<>();
            boolean backedUp = false;
            for (Map.Entry<Integer, String> m : bundled.entrySet()) {
                if (applied.containsKey(m.getKey())) {
                    continue;
                }
                if (current > 0 && !backedUp) {
                    backupHook.backup(current);
                    backedUp = true;
                }
                apply(m.getKey(), read(m.getValue()));
                done.add(m.getKey());
            }
            return new Result(current, List.copyOf(done));
        } catch (SQLException e) {
            throw new StorageException("migration failed", e);
        }
    }

    /** D10: refuse SQLite engines older than 3.47. Compares numerically, never as text. */
    public static void requireMinimumSqlite(String version) {
        String[] parts = version == null ? new String[0] : version.trim().split("\\.");
        try {
            for (int i = 0; i < MINIMUM_SQLITE.length; i++) {
                int have = i < parts.length ? Integer.parseInt(parts[i]) : 0;
                if (have != MINIMUM_SQLITE[i]) {
                    if (have > MINIMUM_SQLITE[i]) {
                        return;
                    }
                    throw new StorageVersionException("SQLite " + version + " is older than the required 3.47.0");
                }
            }
        } catch (NumberFormatException e) {
            throw new StorageVersionException("unreadable SQLite version: " + version);
        }
    }

    private void createVersionTable() throws SQLException {
        try (Statement s = connection.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER PRIMARY KEY, checksum TEXT NOT NULL, "
                    + "applied_at INTEGER NOT NULL) STRICT");
        }
    }

    private Map<Integer, String> appliedChecksums() throws SQLException {
        Map<Integer, String> out = new TreeMap<>();
        try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery("SELECT version, checksum FROM schema_version")) {
            while (rs.next()) {
                out.put(rs.getInt(1), rs.getString(2));
            }
        }
        return out;
    }

    private void apply(int version, String sql) throws SQLException {
        boolean auto = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement s = connection.createStatement()) {
            for (String statement : statements(sql)) {
                s.execute(statement);
            }
            try (PreparedStatement p = connection.prepareStatement("INSERT INTO schema_version (version, checksum, applied_at) VALUES (?, ?, ?)")) {
                p.setInt(1, version);
                p.setString(2, checksum(sql));
                p.setLong(3, System.currentTimeMillis());
                p.executeUpdate();
            }
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(auto);
        }
    }

    /** Splits a migration into statements. Migrations hold no triggers or string literals containing ';'. */
    static List<String> statements(String sql) {
        StringBuilder withoutComments = new StringBuilder();
        for (String line : sql.split("\\R")) {
            if (!line.stripLeading().startsWith("--")) {
                withoutComments.append(line).append('\n');
            }
        }
        List<String> out = new ArrayList<>();
        for (String part : withoutComments.toString().split(";")) {
            if (!part.isBlank()) {
                out.add(part.strip());
            }
        }
        return out;
    }

    private static int versionOf(String name) {
        Matcher m = NAME.matcher(name);
        if (!m.find()) {
            throw new IllegalArgumentException("not a migration name: " + name);
        }
        return Integer.parseInt(m.group(1));
    }

    private static String read(String name) {
        String path = name.startsWith("/") ? name : BASE + name;
        try (InputStream in = Migrator.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new StorageException("missing migration resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new StorageException("cannot read migration " + path, e);
        }
    }

    private static String checksum(String sql) {
        try {
            // Normalise line endings so a checkout with CRLF does not look like an edited migration.
            byte[] bytes = sql.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
