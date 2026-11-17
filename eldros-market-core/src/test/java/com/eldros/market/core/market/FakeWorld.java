package com.eldros.market.core.market;

import com.eldros.market.api.model.TemplateId;
import com.eldros.market.core.domain.Money;
import com.eldros.market.core.domain.PlayerId;
import com.eldros.market.core.port.EconomyGateway;
import com.eldros.market.core.port.InventoryGateway;
import com.eldros.market.core.port.PlayerMarkerStore;

import java.util.HashMap;
import java.util.Map;

/**
 * Test double for the server side (docs/04 §3.0). Economy balances are durable at once (we do not model provider
 * durability, 04 §3.7). Inventory and markers have a live and a durable copy: {@link #save} copies live to durable,
 * {@link #crash} throws the live copy away, exactly the semantics F0 B11 verified on real servers.
 */
final class FakeWorld implements EconomyGateway, InventoryGateway, PlayerMarkerStore {

    enum ProviderMode { NORMAL, FAIL, THROW_BEFORE, THROW_AFTER }

    final Map<PlayerId, Long> balances = new HashMap<>();
    int providerCalls;
    boolean lastCallApplied;
    ProviderMode mode = ProviderMode.NORMAL;

    private Map<PlayerId, Map<TemplateId, Long>> liveInventory = new HashMap<>();
    private Map<PlayerId, Map<TemplateId, Long>> durableInventory = new HashMap<>();
    private Map<PlayerId, Map<String, Long>> liveMarkers = new HashMap<>();
    private Map<PlayerId, Map<String, Long>> durableMarkers = new HashMap<>();
    long capacity = Long.MAX_VALUE;
    boolean online = true;

    // --- economy -----------------------------------------------------------------------------------

    @Override
    public EconomyResult withdraw(PlayerId player, Money amount) {
        return call(player, -amount.minor());
    }

    @Override
    public EconomyResult deposit(PlayerId player, Money amount) {
        return call(player, amount.minor());
    }

    private EconomyResult call(PlayerId player, long delta) {
        providerCalls++;
        lastCallApplied = false;
        if (mode == ProviderMode.THROW_BEFORE) {
            throw new IllegalStateException("provider failed before applying");
        }
        long balance = balances.getOrDefault(player, 0L);
        if (mode == ProviderMode.FAIL || balance + delta < 0) {
            return EconomyResult.failed("refused");
        }
        balances.put(player, balance + delta);
        lastCallApplied = true;
        if (mode == ProviderMode.THROW_AFTER) {
            throw new IllegalStateException("provider failed after applying");
        }
        return EconomyResult.ok();
    }

    // --- inventory ---------------------------------------------------------------------------------

    void give(PlayerId player, TemplateId template, long qty) {
        liveInventory.computeIfAbsent(player, p -> new HashMap<>()).merge(template, qty, Long::sum);
        durableInventory.computeIfAbsent(player, p -> new HashMap<>()).merge(template, qty, Long::sum);
    }

    long durableItems(PlayerId player, TemplateId template) {
        return durableInventory.getOrDefault(player, Map.of()).getOrDefault(template, 0L);
    }

    @Override
    public boolean isOnline(PlayerId player) {
        return online;
    }

    @Override
    public boolean remove(PlayerId player, TemplateId template, long quantity) {
        Map<TemplateId, Long> inv = liveInventory.computeIfAbsent(player, p -> new HashMap<>());
        long have = inv.getOrDefault(template, 0L);
        if (!online || have < quantity) {
            return false;
        }
        inv.put(template, have - quantity);
        return true;
    }

    @Override
    public long fit(PlayerId player, TemplateId template, long quantity) {
        long used = liveInventory.getOrDefault(player, Map.of()).values().stream().mapToLong(Long::longValue).sum();
        return Math.max(0, Math.min(quantity, capacity - used));
    }

    @Override
    public void add(PlayerId player, TemplateId template, long quantity) {
        liveInventory.computeIfAbsent(player, p -> new HashMap<>()).merge(template, quantity, Long::sum);
    }

    // --- markers -----------------------------------------------------------------------------------

    @Override
    public void put(PlayerId player, String opId, long quantity) {
        liveMarkers.computeIfAbsent(player, p -> new HashMap<>()).put(opId, quantity);
    }

    @Override
    public Map<String, Long> all(PlayerId player) {
        return Map.copyOf(liveMarkers.getOrDefault(player, Map.of()));
    }

    @Override
    public void remove(PlayerId player, String opId) {
        liveMarkers.getOrDefault(player, new HashMap<>()).remove(opId);
    }

    @Override
    public void save(PlayerId player) {
        durableInventory.put(player, new HashMap<>(liveInventory.getOrDefault(player, Map.of())));
        durableMarkers.put(player, new HashMap<>(liveMarkers.getOrDefault(player, Map.of())));
    }

    /** Process crash: everything not saved is lost. */
    void crash() {
        liveInventory = deepCopy(durableInventory);
        liveMarkers = deepCopy(durableMarkers);
    }

    /** For U-2 style tests: tamper with a durable marker as a buggy plugin or a manual edit might. */
    void setDurableMarker(PlayerId player, String opId, long qty) {
        durableMarkers.computeIfAbsent(player, p -> new HashMap<>()).put(opId, qty);
        liveMarkers.computeIfAbsent(player, p -> new HashMap<>()).put(opId, qty);
    }

    private static <K, V> Map<PlayerId, Map<K, V>> deepCopy(Map<PlayerId, Map<K, V>> source) {
        Map<PlayerId, Map<K, V>> out = new HashMap<>();
        source.forEach((k, v) -> out.put(k, new HashMap<>(v)));
        return out;
    }
}
