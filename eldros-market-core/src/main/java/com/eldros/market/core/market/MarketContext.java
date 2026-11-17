package com.eldros.market.core.market;

import com.eldros.market.core.effect.EffectLog;
import com.eldros.market.core.engine.MatchingEngine;
import com.eldros.market.core.port.EconomyGateway;
import com.eldros.market.core.port.FaultInjector;
import com.eldros.market.core.port.InventoryGateway;
import com.eldros.market.core.port.PlayerMarkerStore;
import com.eldros.market.core.settlement.SettlementService;
import com.eldros.market.core.storage.jdbc.JdbcJournal;
import com.eldros.market.core.storage.jdbc.JdbcLedger;
import com.eldros.market.core.storage.jdbc.TransactionRunner;

import java.time.Clock;
import java.util.concurrent.Executor;

/**
 * Everything the market operations and recovery share. Thread rules (docs/03 §4): {@code engineThread} owns the
 * engine and the writer connection; {@code mainThread} owns economy, inventory and markers; {@code effectThread}
 * fsyncs the effect log so the main thread never waits on disk (D7).
 */
public record MarketContext(MatchingEngine engine, SettlementService settlement, JdbcLedger ledger, JdbcJournal journal,
                            TransactionRunner tx, EffectLog effects, EconomyGateway economy, InventoryGateway inventory,
                            PlayerMarkerStore markers, FaultInjector faults, Clock clock,
                            Executor engineThread, Executor mainThread, Executor effectThread) {
}
