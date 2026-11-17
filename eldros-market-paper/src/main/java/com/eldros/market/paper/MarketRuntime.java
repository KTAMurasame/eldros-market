package com.eldros.market.paper;

import com.eldros.market.api.model.OrderStatus;
import com.eldros.market.api.model.TemplateId;
import com.eldros.market.core.domain.Money;
import com.eldros.market.core.domain.PlayerId;
import com.eldros.market.core.effect.EffectLog;
import com.eldros.market.core.engine.MatchingEngine;
import com.eldros.market.core.market.MarketContext;
import com.eldros.market.core.market.MarketOperations;
import com.eldros.market.core.port.FaultInjector;
import com.eldros.market.core.recovery.RecoveryService;
import com.eldros.market.core.safety.SafeMode;
import com.eldros.market.core.service.LimitPolicy;
import com.eldros.market.core.settlement.SettlementService;
import com.eldros.market.core.storage.SchemaException;
import com.eldros.market.core.storage.jdbc.JdbcJournal;
import com.eldros.market.core.storage.jdbc.JdbcLedger;
import com.eldros.market.core.storage.jdbc.JdbcLedger.Notification;
import com.eldros.market.core.storage.jdbc.Migrator;
import com.eldros.market.core.storage.jdbc.SqliteDatabase;
import com.eldros.market.core.storage.jdbc.TransactionRunner;
import com.eldros.market.paper.config.PluginSettings;
import com.eldros.market.paper.i18n.Messages;
import com.eldros.market.paper.integration.VaultEconomyGateway;
import com.eldros.market.paper.inventory.BukkitInventoryGateway;
import com.eldros.market.paper.inventory.PdcMarkerStore;
import com.eldros.market.paper.item.ItemFilter;
import com.eldros.market.paper.item.TemplateRegistry;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.scheduler.BukkitTask;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Wires core to the server and owns the threads (docs/03 §4): one engine thread (engine + writer connection), one
 * effect thread (effect log fsync), and the server main thread for everything Bukkit. Commands are refused until
 * startup recovery has finished.
 */
public final class MarketRuntime {

    private final EldrosMarketPlugin plugin;
    private final Logger log;
    private final Messages messages;
    private final Clock clock = Clock.systemUTC();
    private final ExecutorService engineExecutor = Executors.newSingleThreadExecutor(r -> daemon(r, "EldrosMarket-engine"));
    private final ExecutorService effectExecutor = Executors.newSingleThreadExecutor(r -> daemon(r, "EldrosMarket-effect"));
    private final Executor mainExecutor;
    private final SafeMode safeMode = new SafeMode();
    private final TemplateRegistry templates = new TemplateRegistry();
    private final JdbcLedger ledger = new JdbcLedger();
    private final JdbcJournal journal = new JdbcJournal();
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    private final List<BukkitTask> tasks = new ArrayList<>();

    private volatile PluginSettings settings;
    private volatile boolean ready;
    private SqliteDatabase db;
    private TransactionRunner tx;
    private EffectLog effects;
    private Path effectFile;
    private VaultEconomyGateway economy;
    private BukkitInventoryGateway inventory;
    private PdcMarkerStore markers;
    private volatile MarketOperations ops;
    private volatile RecoveryService recovery;
    private volatile MatchingEngine engine;
    private volatile SettlementService settlement;
    private volatile ItemFilter filter;
    private volatile LimitPolicy limits;
    private long opsWindowStart;
    private int opsInWindow;

    MarketRuntime(EldrosMarketPlugin plugin, PluginSettings settings, Messages messages) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.settings = settings;
        this.messages = messages;
        this.mainExecutor = r -> {
            if (Bukkit.isPrimaryThread()) {
                r.run();
            } else if (plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, r);
            }
        };
        this.filter = new ItemFilter(settings);
        this.limits = new LimitPolicy(settings.limits());
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    // --- lifecycle ---------------------------------------------------------------------------------

    /** Opens storage and starts recovery. Throws if the market must stay closed (the plugin then disables itself). */
    void start() throws Exception {
        Path folder = plugin.getDataFolder().toPath();
        Files.createDirectories(folder);
        Path dbFile = folder.resolve("market.db");
        Class.forName("org.sqlite.JDBC", true, plugin.getClass().getClassLoader());
        db = SqliteDatabase.open(() -> DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath()));
        String sqlite = db.sqliteVersion();
        Migrator.requireMinimumSqlite(sqlite);
        log.info("Storage: SQLite " + sqlite + " (" + db.writer().getMetaData().getDriverVersion() + " driver)");
        Migrator.Result migrated = new Migrator(db.writer(), from -> backup(dbFile, "schema-v" + from)).migrate();
        if (!migrated.applied().isEmpty()) {
            log.info("Database schema migrated: " + migrated.applied());
        }
        tx = new TransactionRunner(db.writer());
        checkMoneyScale();

        upgradeTemplatesIfNeeded(dbFile);
        templates.load(tx.inTransaction(ledger::loadTemplates), log);

        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            throw new IllegalStateException("No Vault economy provider found. Install Vault (or VaultUnlocked) and an economy plugin.");
        }
        economy = new VaultEconomyGateway(rsp.getProvider(), settings.scale());
        int digits = economy.providerFractionalDigits();
        if (digits < 0) {
            log.info("Economy '" + economy.providerName() + "' does not report its decimal places; using "
                    + settings.scale().decimalPlaces() + " from economy.decimal-places.");
        } else if (digits != settings.scale().decimalPlaces()) {
            log.warning("Economy '" + economy.providerName() + "' reports " + digits + " decimal places; Eldros Market uses "
                    + settings.scale().decimalPlaces() + " from economy.decimal-places.");
        }
        inventory = new BukkitInventoryGateway(templates);
        markers = new PdcMarkerStore(plugin);
        effectFile = folder.resolve("effects.log");
        effects = new EffectLog(effectFile);
        buildCore();

        onEngine(() -> recovery.recoverAtStartup(EffectLog.read(effectFile))).whenComplete((report, error) -> {
            if (error != null) {
                log.log(Level.SEVERE, "Startup recovery failed; the market stays closed. Restore market.db from a backup or ask for support.", error);
                safeMode.enter("startup recovery failed");
                return;
            }
            log.info("Recovery: " + report.recovered() + " recovered, " + report.aborted() + " aborted, " + report.quarantined()
                    + " quarantined, " + report.replayedFills() + " fills replayed.");
            if (report.quarantined() > 0) {
                log.warning(report.quarantined() + " operation(s) need review: /marketadmin recovery list");
            }
            mainExecutor.execute(() -> {
                ready = true;
                Bukkit.getOnlinePlayers().forEach(this::onJoin);
            });
        });
        scheduleTasks();
    }

    private void buildCore() {
        engine = new MatchingEngine();
        settlement = new SettlementService(engine, ledger, clock);
        MarketContext context = new MarketContext(engine, settlement, ledger, journal, tx, effects,
                economy, inventory, markers, FaultInjector.NONE, clock, engineExecutor, mainExecutor, effectExecutor);
        ops = new MarketOperations(context, safeMode, MarketRuntime::sleep);
        recovery = new RecoveryService(context);
    }

    void stop() {
        ready = false;
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
        engineExecutor.shutdown();
        effectExecutor.shutdown();
        try {
            if (!engineExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warning("Engine thread did not finish in 10s; pending work will be recovered at next start.");
            }
            effectExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (effects != null) {
            effects.close();
        }
        if (db != null) {
            db.close();
        }
    }

    private void backup(Path dbFile, String label) {
        try (Statement s = db.writer().createStatement()) {
            s.execute("PRAGMA wal_checkpoint(FULL)");
            Path target = dbFile.resolveSibling("market.db.bak-" + label + "-" + System.currentTimeMillis());
            Files.copy(dbFile, target, StandardCopyOption.COPY_ATTRIBUTES);
            log.info("Database backup: " + target.getFileName());
        } catch (Exception e) {
            throw new IllegalStateException("Could not back up market.db before changing it; nothing was changed", e);
        }
    }

    /** Amounts are stored in minor units of the configured scale; another scale would silently rescale every amount. */
    private void checkMoneyScale() {
        String configured = Integer.toString(settings.scale().decimalPlaces());
        Optional<String> stored = tx.inTransaction(c -> ledger.meta(c, "money_scale"));
        if (stored.isPresent() && !stored.get().equals(configured)) {
            throw new SchemaException("economy.decimal-places is " + configured + " but market.db was created with " + stored.get()
                    + ". Set it back to " + stored.get() + "; changing it would change every stored price.");
        }
        if (stored.isEmpty()) {
            tx.inTransaction(c -> {
                ledger.setMeta(c, "money_scale", configured);
                return null;
            });
        }
    }

    /** docs/03 §8.5: re-serialise templates after a Minecraft version change; refuse a downgrade. Main thread, startup. */
    @SuppressWarnings("deprecation")
    private void upgradeTemplatesIfNeeded(Path dbFile) {
        int current = Bukkit.getUnsafe().getDataVersion();
        Optional<String> stored = tx.inTransaction(c -> ledger.meta(c, "server_data_version"));
        if (stored.isPresent() && Integer.parseInt(stored.get()) > current) {
            throw new SchemaException("market.db was used by a newer Minecraft version (data version " + stored.get()
                    + " > " + current + "). Downgrades are not supported; restore a backup made on this version.");
        }
        List<JdbcLedger.TemplateRow> rows = tx.inTransaction(ledger::loadTemplates);
        boolean needsUpgrade = rows.stream().anyMatch(r -> "ACTIVE".equals(r.status()) && r.dataVersion() != current);
        if (needsUpgrade) {
            backup(dbFile, "dataversion-" + stored.orElse("unknown"));
            int upgraded = 0, invalid = 0;
            for (JdbcLedger.TemplateRow row : rows) {
                if (!"ACTIVE".equals(row.status()) || row.dataVersion() == current) {
                    continue;
                }
                TemplateId id = new TemplateId(row.id());
                try {
                    ItemStack item = ItemStack.deserializeBytes(row.bytes());
                    if (item.getType().isAir()) {
                        throw new IllegalStateException("empty after upgrade");
                    }
                    ItemStack normalized = TemplateRegistry.normalize(item);
                    byte[] bytes = normalized.serializeAsBytes();
                    String fp = TemplateRegistry.fingerprint(normalized);
                    tx.inTransaction(c -> {
                        ledger.upgradeTemplate(c, id, row.dataVersion(), bytes, current, clock.millis());
                        ledger.addFingerprint(c, fp, id);
                        return null;
                    });
                    upgraded++;
                } catch (RuntimeException e) {
                    tx.inTransaction(c -> {
                        ledger.markTemplateInvalid(c, id);
                        return null;
                    });
                    log.warning("Item template #" + row.id() + " (" + row.material() + ") could not be upgraded and was frozen: " + e.getMessage());
                    invalid++;
                }
            }
            log.info("Item templates upgraded to data version " + current + ": " + upgraded + " upgraded, " + invalid + " frozen.");
        }
        tx.inTransaction(c -> {
            ledger.setMeta(c, "server_data_version", Integer.toString(current));
            return null;
        });
    }

    private void scheduleTasks() {
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::deliverNotifications, 60L, 60L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::sweepExpired, 1_200L, 1_200L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::tryLeaveSafeMode, 600L, 600L));
    }

    // --- engine access -------------------------------------------------------------------------------

    public <T> CompletableFuture<T> onEngine(Callable<T> work) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return work.call();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, engineExecutor);
    }

    public EldrosMarketPlugin plugin() {
        return plugin;
    }

    public Executor main() {
        return mainExecutor;
    }

    public boolean isReady() {
        return ready && !safeMode.isActive();
    }

    public boolean isLoaded() {
        return ready;
    }

    public SafeMode safeMode() {
        return safeMode;
    }

    public MarketOperations ops() {
        return ops;
    }

    public RecoveryService recovery() {
        return recovery;
    }

    public MatchingEngine engine() {
        return engine;
    }

    public TransactionRunner tx() {
        return tx;
    }

    public JdbcLedger ledger() {
        return ledger;
    }

    public JdbcJournal journal() {
        return journal;
    }

    public TemplateRegistry templates() {
        return templates;
    }

    public PluginSettings settings() {
        return settings;
    }

    public Messages messages() {
        return messages;
    }

    public ItemFilter filter() {
        return filter;
    }

    public LimitPolicy limits() {
        return limits;
    }

    void applySettings(PluginSettings next) {
        settings = next;
        filter = new ItemFilter(next);
        limits = new LimitPolicy(next.limits());
    }

    /** One market action per player at a time (no double clicks racing each other). */
    public boolean tryLock(UUID player) {
        return busy.add(player);
    }

    public void unlock(UUID player) {
        busy.remove(player);
    }

    /** Server-wide rate of item operations, each of which saves player data once (D9). Main thread. */
    public boolean tryItemOperation() {
        long now = System.currentTimeMillis();
        if (now - opsWindowStart >= 1_000) {
            opsWindowStart = now;
            opsInWindow = 0;
        }
        if (opsInWindow >= settings.itemOpsPerSecond()) {
            return false;
        }
        opsInWindow++;
        return true;
    }

    /** Finds or creates the template of an item. Main thread in, main thread out. */
    public CompletableFuture<TemplateId> resolveTemplate(ItemStack item) {
        Optional<TemplateId> known = templates.find(item);
        if (known.isPresent()) {
            return CompletableFuture.completedFuture(known.get());
        }
        ItemStack normalized = TemplateRegistry.normalize(item);
        byte[] bytes = normalized.serializeAsBytes();
        String fp = TemplateRegistry.fingerprint(normalized);
        @SuppressWarnings("deprecation")
        int dataVersion = Bukkit.getUnsafe().getDataVersion();
        String material = normalized.getType().getKey().toString();
        return onEngine(() -> tx.inTransaction(c -> {
            Optional<TemplateId> existing = ledger.templateByFingerprint(c, fp);
            return existing.isPresent() ? existing.get() : ledger.createTemplate(c, material, bytes, dataVersion, fp, clock.millis());
        })).thenApplyAsync(id -> {
            templates.register(id, normalized, fp);
            return id;
        }, mainExecutor);
    }

    public long now() {
        return clock.millis();
    }

    // --- join, notifications, expiry, safe mode -----------------------------------------------------

    public void onJoin(Player player) {
        if (!ready) {
            return;
        }
        PlayerId id = new PlayerId(player.getUniqueId());
        onEngine(() -> tx.inTransaction(c -> ledger.playerLocale(c, id)))
                .thenAcceptAsync(locale -> locale.ifPresent(l -> messages.setPreference(player.getUniqueId(), l)), mainExecutor);
        Map<String, Long> found = markers.all(id);
        onEngine(() -> recovery.reconcilePlayer(id, found)).thenAcceptAsync(report -> {
            report.removeMarkers().forEach(op -> markers.remove(id, op));
            if (!report.orphanMarkers().isEmpty()) {
                log.warning("Player " + player.getName() + " carries " + report.orphanMarkers().size()
                        + " market marker(s) with no journal entry: " + report.orphanMarkers() + ". Check backups before removing.");
            }
        }, mainExecutor).exceptionally(e -> {
            log.log(Level.SEVERE, "Reconciling " + player.getName() + " failed", e);
            return null;
        });
        onEngine(() -> tx.inTransaction(c -> journal.availableFor(c, id).size())).thenAcceptAsync(count -> {
            if (count > 0 && player.isOnline()) {
                messages.send(player, "mailbox.waiting", Messages.text("count", count));
            }
        }, mainExecutor);
    }

    private void deliverNotifications() {
        if (!ready) {
            return;
        }
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (online.isEmpty()) {
            return;
        }
        onEngine(() -> {
            Map<UUID, List<Notification>> pending = new HashMap<>();
            for (Player p : online) {
                List<Notification> list = tx.inTransaction(c -> ledger.pendingNotifications(c, new PlayerId(p.getUniqueId())));
                if (!list.isEmpty()) {
                    pending.put(p.getUniqueId(), list);
                }
            }
            return pending;
        }).thenAcceptAsync(pending -> {
            List<Long> delivered = new ArrayList<>();
            pending.forEach((uuid, list) -> {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null) {
                    return;
                }
                for (Notification n : list) {
                    messages.send(p, n.messageKey(), notificationArgs(p, n));
                    delivered.add(n.id());
                }
            });
            if (!delivered.isEmpty()) {
                onEngine(() -> tx.inTransaction(c -> {
                    ledger.markDelivered(c, delivered, clock.millis());
                    return null;
                }));
            }
        }, mainExecutor);
    }

    /** Numeric outbox args → placeholders (see SettlementService / RecoveryService for each key's args). */
    private TagResolver[] notificationArgs(Player p, Notification n) {
        String lang = messages.languageOf(p);
        List<Long> a = n.args();
        return switch (n.messageKey()) {
            case "notify.trade.sold", "notify.trade.bought" -> new TagResolver[] {
                    Messages.component("item", templates.name(new TemplateId(a.get(0)))), Messages.text("qty", a.get(1)),
                    messages.money(lang, "price", Money.ofMinor(a.get(2)), settings.scale())};
            case "notify.order.cancelled", "notify.order.expired", "notify.recovery.items" -> new TagResolver[] {
                    Messages.component("item", templates.name(new TemplateId(a.get(0)))), Messages.text("qty", a.get(1))};
            case "notify.recovery.refund" -> new TagResolver[] {messages.money(lang, "amount", Money.ofMinor(a.get(0)), settings.scale())};
            default -> new TagResolver[0];
        };
    }

    private void sweepExpired() {
        if (!ready || !settings.expiryEnabled() || safeMode.isActive()) {
            return;
        }
        onEngine(() -> {
            MatchingEngine current = engine;
            for (var order : current.expiredOrders(clock.millis(), true)) {
                // Expiry only moves escrow into the mailbox; no Vault or inventory effect, so no journal entry.
                settlement.cancel(tx, order.id(), OrderStatus.EXPIRED);
            }
            return null;
        }).exceptionally(e -> {
            log.log(Level.WARNING, "Expiry sweep failed; will retry", e);
            return null;
        });
    }

    private void tryLeaveSafeMode() {
        if (!ready || !safeMode.isActive()) {
            return;
        }
        leaveSafeMode().thenAccept(left -> {
            if (left) {
                log.info("Safe mode left automatically: storage writes work again, market reloaded and recovered.");
            }
        });
    }

    /** Probes storage; if writable, rebuilds the core and runs startup recovery, then reconciles online players. */
    public CompletableFuture<Boolean> leaveSafeMode() {
        return onEngine(() -> {
            if (!safeMode.probe(tx, clock.millis())) {
                return false;
            }
            buildCore();
            recovery.recoverAtStartup(EffectLog.read(effectFile));
            return true;
        }).thenApplyAsync(ok -> {
            if (ok) {
                safeMode.exit();
                Bukkit.getOnlinePlayers().forEach(this::onJoin);
            }
            return ok;
        }, mainExecutor);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
