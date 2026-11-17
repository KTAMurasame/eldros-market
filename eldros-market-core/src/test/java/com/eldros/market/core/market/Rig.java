package com.eldros.market.core.market;

import com.eldros.market.api.model.TemplateId;
import com.eldros.market.core.domain.PlayerId;
import com.eldros.market.core.effect.EffectLog;
import com.eldros.market.core.engine.MatchingEngine;
import com.eldros.market.core.port.FaultInjector;
import com.eldros.market.core.port.FaultInjector.CrashPoint;
import com.eldros.market.core.recovery.RecoveryService;
import com.eldros.market.core.settlement.SettlementService;
import com.eldros.market.core.storage.jdbc.JdbcJournal;
import com.eldros.market.core.storage.jdbc.JdbcLedger;
import com.eldros.market.core.storage.jdbc.SqliteDatabase;
import com.eldros.market.core.storage.jdbc.TestDatabases;
import com.eldros.market.core.storage.jdbc.TransactionRunner;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/** One "server process" for crash tests: real SQLite file and effect log, fake world, all executors inline. */
final class Rig implements AutoCloseable {

    static final class SimulatedCrash extends Error {
        SimulatedCrash(CrashPoint point) {
            super("simulated crash at " + point, null, false, false);
        }
    }

    static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(5_000_000), ZoneOffset.UTC);
    static final TemplateId DIAMOND = new TemplateId(1);

    final Path dir;
    final FakeWorld world;
    final SqliteDatabase db;
    final EffectLog effects;
    final MatchingEngine engine = new MatchingEngine();
    final MarketContext context;
    final MarketOperations ops;
    final RecoveryService recovery;

    private Rig(Path dir, FakeWorld world, CrashPoint crashAt) {
        this.dir = dir;
        this.world = world;
        try {
            java.nio.file.Files.createDirectories(dir);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        this.db = TestDatabases.openMigrated(dir.resolve("market.db"));
        this.effects = new EffectLog(dir.resolve("effects.log"));
        boolean[] fired = {false};
        FaultInjector faults = point -> {
            if (point == crashAt && !fired[0]) {
                fired[0] = true;
                throw new SimulatedCrash(point);
            }
        };
        Executor inline = Runnable::run;
        JdbcLedger ledger = new JdbcLedger();
        this.context = new MarketContext(engine, new SettlementService(engine, ledger, CLOCK), ledger, new JdbcJournal(),
                new TransactionRunner(db.writer()), effects, world, world, world, faults, CLOCK, inline, inline, inline);
        this.ops = new MarketOperations(context);
        this.recovery = new RecoveryService(context);
    }

    /** A fresh market with one template (DIAMOND) already registered. */
    static Rig fresh(Path dir, FakeWorld world, CrashPoint crashAt) {
        Rig rig = new Rig(dir, world, crashAt);
        rig.context.tx().inTransaction(c -> rig.context.ledger().createTemplate(c, "minecraft:diamond", new byte[] {1}, 1, "fp", 0));
        return rig;
    }

    /** Crash: close everything, lose the unsaved world state, start again and run startup recovery. */
    Rig crashAndRestart() {
        close();
        world.crash();
        Rig next = new Rig(dir, world, null);
        next.recovery.recoverAtStartup(EffectLog.read(dir.resolve("effects.log")));
        return next;
    }

    /** A normal start on existing files: open and run startup recovery (which also rebuilds the books). */
    static Rig reopen(Path dir, FakeWorld world) {
        Rig next = new Rig(dir, world, null);
        next.recovery.recoverAtStartup(EffectLog.read(dir.resolve("effects.log")));
        return next;
    }

    /** Player join: reconcile markers and remove the ones recovery says are finished. */
    RecoveryService.ReconcileReport join(PlayerId player) {
        RecoveryService.ReconcileReport report = recovery.reconcilePlayer(player, world.all(player));
        report.removeMarkers().forEach(op -> world.remove(player, op));
        return report;
    }

    List<String> rows(String sql) {
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
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    long number(String sql) {
        return Long.parseLong(rows(sql).getFirst());
    }

    /** Everything that changes during recovery, to prove a repeated run changes nothing. */
    List<String> snapshot() {
        List<String> out = new ArrayList<>(rows("SELECT op_id, state, qty_effective FROM journal ORDER BY op_id"));
        out.addAll(rows("SELECT id, status, qty, amount, claim_op_id FROM mailbox ORDER BY id"));
        out.addAll(rows("SELECT id, status, qty_filled, escrow_money FROM orders ORDER BY id"));
        return out;
    }

    @Override
    public void close() {
        effects.close();
        db.close();
    }
}
