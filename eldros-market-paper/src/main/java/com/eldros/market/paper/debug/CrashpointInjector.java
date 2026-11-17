package com.eldros.market.paper.debug;

import com.eldros.market.core.port.FaultInjector;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * TEST BUILD ONLY (excluded from release jars by the shade filter). When the server runs with
 * {@code -Deldros.debug.crashpoint=<CrashPoint>}, the process is halted the moment that point is reached: no shutdown
 * hooks, no plugin disable, no flush of anything still in JVM memory, which is what {@code kill -9} does to the
 * process (docs/04 §3.8). OS page cache survives in both cases; a power cut is not simulated.
 */
public final class CrashpointInjector implements FaultInjector {

    private final CrashPoint target;
    private final AtomicInteger skip;
    private final Logger logger;

    public CrashpointInjector(Logger logger) {
        this.logger = logger;
        this.target = CrashPoint.valueOf(System.getProperty("eldros.debug.crashpoint"));
        this.skip = new AtomicInteger(Integer.getInteger("eldros.debug.crashpoint.skip", 0));
        logger.warning("CRASHPOINT ARMED: " + target + " (skip " + skip.get() + "). This is a test build.");
    }

    @Override
    public void at(CrashPoint point) {
        if (point != target || skip.getAndDecrement() > 0) {
            return;
        }
        logger.severe("CRASHPOINT " + point + " reached on thread " + Thread.currentThread().getName() + " - halting now");
        System.err.flush();
        Runtime.getRuntime().halt(137);
    }
}
