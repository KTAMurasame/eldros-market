package com.eldros.market.core.storage.jdbc;

import java.nio.file.Path;
import java.sql.DriverManager;

/** Test helper: a file-backed SQLite database (WAL needs a real file) opened like production does. */
public final class TestDatabases {

    private TestDatabases() {
    }

    public static SqliteDatabase open(Path file) {
        return SqliteDatabase.open(() -> DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath()));
    }

    public static SqliteDatabase openMigrated(Path file) {
        SqliteDatabase db = open(file);
        new Migrator(db.writer(), from -> { }).migrate();
        return db;
    }
}
