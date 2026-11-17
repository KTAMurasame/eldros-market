package com.eldros.market.core.storage.jdbc;

import com.eldros.market.core.storage.StorageException;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * The single writer connection (owned by the engine thread, docs/03 §4) plus factory access for read connections.
 * WAL lets readers run beside the writer; synchronous=FULL makes a commit durable before it returns.
 */
public final class SqliteDatabase implements AutoCloseable {

    private final ConnectionFactory factory;
    private final Connection writer;

    private SqliteDatabase(ConnectionFactory factory, Connection writer) {
        this.factory = factory;
        this.writer = writer;
    }

    public static SqliteDatabase open(ConnectionFactory factory) {
        try {
            Connection writer = factory.open();
            configure(writer);
            try (Statement s = writer.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
            }
            return new SqliteDatabase(factory, writer);
        } catch (SQLException e) {
            throw new StorageException("cannot open market database", e);
        }
    }

    private static void configure(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA synchronous=FULL");
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA busy_timeout=5000");
        }
    }

    public Connection writer() {
        return writer;
    }

    /** A new read connection; the caller closes it. */
    public Connection openReader() {
        try {
            Connection c = factory.open();
            configure(c);
            c.setReadOnly(true);
            return c;
        } catch (SQLException e) {
            throw new StorageException("cannot open read connection", e);
        }
    }

    /** The SQLite engine version, e.g. "3.47.0" (not the driver's). */
    public String sqliteVersion() {
        try (Statement s = writer.createStatement(); ResultSet rs = s.executeQuery("SELECT sqlite_version()")) {
            rs.next();
            return rs.getString(1);
        } catch (SQLException e) {
            throw new StorageException("cannot read sqlite version", e);
        }
    }

    @Override
    public void close() {
        try {
            writer.close();
        } catch (SQLException e) {
            throw new StorageException("cannot close market database", e);
        }
    }
}
