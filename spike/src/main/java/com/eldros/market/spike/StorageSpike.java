package com.eldros.market.spike;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/** S6: sqlite-jdbc via plugin.yml libraries, WAL + synchronous=FULL, effect-log fsync timing (D7). Runs off the main thread. */
final class StorageSpike {

    private final JavaPlugin plugin;
    private final Report report;

    StorageSpike(JavaPlugin plugin, Report report) {
        this.plugin = plugin;
        this.report = report;
    }

    void run() {
        report.section("S6 storage");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            sqlite();
            effectLog();
        });
    }

    private void sqlite() {
        try {
            Class<?> driver = Class.forName("org.sqlite.JDBC", true, getClass().getClassLoader());
            report.line("driver class loaded from " + driver.getProtectionDomain().getCodeSource().getLocation());
        } catch (Throwable t) {
            report.line("driver class NOT loadable: " + t);
            return;
        }
        Path db = plugin.getDataFolder().toPath().resolve("spike.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath())) {
            report.line("driver " + c.getMetaData().getDriverName() + " " + c.getMetaData().getDriverVersion());
            try (Statement s = c.createStatement()) {
                report.line("journal_mode=" + scalar(s, "PRAGMA journal_mode=WAL"));
                s.execute("PRAGMA synchronous=FULL");
                report.line("synchronous=" + scalar(s, "PRAGMA synchronous") + " (2=FULL)");
                s.execute("CREATE TABLE IF NOT EXISTS t (id INTEGER PRIMARY KEY, v INTEGER NOT NULL)");
            }
            c.setAutoCommit(false);
            long[] tx = new long[1000];
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO t (v) VALUES (?)")) {
                for (int i = 0; i < tx.length; i++) {
                    long t0 = System.nanoTime();
                    ps.setInt(1, i);
                    ps.executeUpdate();
                    c.commit();
                    tx[i] = System.nanoTime() - t0;
                }
            }
            report.line("timing sqlite single-insert commit (WAL, FULL) " + Stats.summarize(tx) + " thread=" + Thread.currentThread().getName());
        } catch (Throwable t) {
            report.line("sqlite ERROR " + t);
        }
    }

    private void effectLog() {
        Path log = plugin.getDataFolder().toPath().resolve("effects-spike.log");
        long[] samples = new long[1000];
        try {
            Files.deleteIfExists(log);
            try (FileChannel ch = FileChannel.open(log, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                for (int i = 0; i < samples.length; i++) {
                    byte[] line = ("00000000-0000-0000-0000-" + String.format("%012d", i) + "|CONFIRMED|crc\n").getBytes(StandardCharsets.UTF_8);
                    long t0 = System.nanoTime();
                    ch.write(ByteBuffer.wrap(line));
                    ch.force(true);
                    samples[i] = System.nanoTime() - t0;
                }
            }
            report.line("timing effect-log append+fsync " + Stats.summarize(samples) + " disk=" + log.toAbsolutePath().getRoot());
        } catch (IOException e) {
            report.line("effect log ERROR " + e);
        }
    }

    private static String scalar(Statement s, String sql) throws Exception {
        try (ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : "<none>";
        }
    }
}
