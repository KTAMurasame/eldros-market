package com.eldros.market.core.market;

import com.eldros.market.api.model.OrderSide;
import com.eldros.market.api.model.TemplateId;
import com.eldros.market.core.domain.Money;
import com.eldros.market.core.domain.NewOrder;
import com.eldros.market.core.domain.Order;
import com.eldros.market.core.domain.PlayerId;
import com.eldros.market.core.engine.MatchingEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import java.util.UUID;

/**
 * Timing harness for docs/04 §7 (not JMH: plain System.nanoTime with warm-up, fixed seeds). Off by default; run with
 * {@code mvn -pl eldros-market-core test -Dtest=PerfHarness -Deldros.perf=true}. Prints p50/p95/p99/max per metric.
 */
@EnabledIfSystemProperty(named = "eldros.perf", matches = "true")
class PerfHarness {

    private static final long SEED = 20261118L;

    @TempDir
    Path dir;

    private static PlayerId player(int n) {
        return new PlayerId(new UUID(0, n + 1));
    }

    private static void report(String name, long[] nanos) {
        long[] s = nanos.clone();
        Arrays.sort(s);
        System.out.printf("PERF %-48s n=%d p50=%.3fms p95=%.3fms p99=%.3fms max=%.3fms%n", name, s.length,
                s[(int) (s.length * 0.50)] / 1e6, s[(int) (s.length * 0.95)] / 1e6, s[(int) (s.length * 0.99)] / 1e6,
                s[s.length - 1] / 1e6);
    }

    /** D-M style book: price = base ± 10 %, side random, no immediate crossing because asks sit above bids. */
    private static MatchingEngine book(Random r, int orders, int templates, int players, long[] nextId) {
        MatchingEngine engine = new MatchingEngine();
        for (int i = 0; i < orders; i++) {
            boolean buy = r.nextBoolean();
            long base = 1_000;
            long price = buy ? base - 1 - r.nextInt(100) : base + 1 + r.nextInt(100);
            long id = nextId[0]++;
            engine.place(Order.create(id, player(r.nextInt(players)), buy ? OrderSide.BUY : OrderSide.SELL,
                    new TemplateId(1 + r.nextInt(templates)), Money.ofMinor(price), 1 + r.nextInt(64), id, null));
        }
        return engine;
    }

    // §7.1 row 1: matching in memory, 10k active orders (D-M: 500 templates, 1,000 players).
    @Test
    void matchingInMemory() {
        Random r = new Random(SEED);
        long[] nextId = {1};
        MatchingEngine engine = book(r, 10_000, 500, 1_000, nextId);
        int warm = 20_000;
        int n = 200_000;
        long[] t = new long[n];
        for (int i = -warm; i < n; i++) {
            boolean buy = r.nextBoolean();
            long price = 900 + r.nextInt(201); // about half of the incoming orders cross
            long id = nextId[0]++;
            Order incoming = Order.create(id, player(r.nextInt(1_000)), buy ? OrderSide.BUY : OrderSide.SELL,
                    new TemplateId(1 + r.nextInt(500)), Money.ofMinor(price), 1 + r.nextInt(64), id, null);
            long start = System.nanoTime();
            engine.plan(incoming);
            long took = System.nanoTime() - start;
            if (i >= 0) {
                t[i] = took;
            }
        }
        report("matching plan, 10k book (D-M)", t);
    }

    // §7.1 row 3: self-trade worst case: the incoming player owns everything at the top of the book.
    @Test
    void selfTradeWorstCase() {
        for (int own : new int[] {10, 1_000}) {
            Random r = new Random(SEED);
            long[] nextId = {1};
            MatchingEngine engine = book(r, 10_000, 50, 200, nextId);
            PlayerId me = player(999_999);
            TemplateId t1 = new TemplateId(1);
            for (int i = 0; i < own; i++) {
                long id = nextId[0]++;
                engine.place(Order.create(id, me, OrderSide.SELL, t1, Money.ofMinor(500 + i % 50), 1, id, null));
            }
            int n = 20_000;
            long[] t = new long[n];
            for (int i = -2_000; i < n; i++) {
                long id = nextId[0]++;
                Order incoming = Order.create(id, me, OrderSide.BUY, t1, Money.ofMinor(1_200), 64, id, null);
                long start = System.nanoTime();
                engine.plan(incoming);
                long took = System.nanoTime() - start;
                if (i >= 0) {
                    t[i] = took;
                }
            }
            report("self-trade plan, " + own + " own orders on top (D-ST)", t);
        }
    }

    // §7.1 row 2 (end-to-end variant): a whole BUY_CREATE with real SQLite (WAL, synchronous=FULL) and the effect
    // log fsync, executors inline. Includes the PENDING commit, fsync and the settlement commit.
    @Test
    void buyCreateEndToEnd() {
        for (int run = 1; run <= 3; run++) {
            FakeWorld world = new FakeWorld();
            Random r = new Random(SEED + run);
            int players = 1_000;
            for (int p = 0; p < players; p++) {
                world.balances.put(player(p), Long.MAX_VALUE / 4);
            }
            try (Rig rig = Rig.fresh(dir.resolve("run" + run), world, null)) {
                int warm = 500;
                int n = 5_000;
                long[] t = new long[n];
                for (int i = -warm; i < n; i++) {
                    NewOrder spec = new NewOrder(player(r.nextInt(players)), OrderSide.BUY, Rig.DIAMOND,
                            Money.ofMinor(900 + r.nextInt(100)), 1 + r.nextInt(64), null);
                    long start = System.nanoTime();
                    rig.ops.buy(spec).join();
                    long took = System.nanoTime() - start;
                    if (i >= 0) {
                        t[i] = took;
                    }
                }
                report("BUY_CREATE end-to-end, run " + run + " (disk of " + dir.getRoot() + ")", t);
            }
        }
    }

    // §7.1 row "50k orders at startup": load and replay of active orders.
    @Test
    void startupWith50kOrders() {
        FakeWorld world = new FakeWorld();
        Path d = dir.resolve("load");
        Random r = new Random(SEED);
        try (Rig rig = Rig.fresh(d, world, null)) {
            rig.context.tx().inTransaction(c -> {
                for (int i = 0; i < 50_000; i++) {
                    boolean buy = r.nextBoolean();
                    long price = buy ? 999 - r.nextInt(100) : 1_001 + r.nextInt(100);
                    rig.context.ledger().insertOrder(c, new NewOrder(player(r.nextInt(5_000)), buy ? OrderSide.BUY : OrderSide.SELL,
                            Rig.DIAMOND, Money.ofMinor(price), 1 + r.nextInt(64), null), null, 0);
                }
                return null;
            });
        }
        long[] t = new long[3];
        for (int i = 0; i < 3; i++) {
            long start = System.nanoTime();
            Rig restarted = Rig.reopen(d, world);
            t[i] = System.nanoTime() - start;
            long loaded = restarted.engine.summaries().stream().mapToLong(s -> s.orderCount()).sum();
            restarted.close();
            if (loaded != 50_000) {
                throw new AssertionError("expected 50000 orders in the book after start, got " + loaded);
            }
        }
        report("startup with 50k active orders (open + recovery)", t);
    }
}
