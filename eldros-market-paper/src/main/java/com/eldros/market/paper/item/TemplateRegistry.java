package com.eldros.market.paper.item;

import com.eldros.market.api.model.TemplateId;
import com.eldros.market.core.storage.jdbc.JdbcLedger.TemplateRow;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Exact item identity (docs/03 §8). A template is the amount-1 reference item of a market. Lookup: fingerprint
 * (sha256 of {@code serializeAsBytes}) as a fast index, then {@code isSimilar} as the deciding comparison — bytes alone
 * never split a market (F0 B8). Main thread only.
 */
public final class TemplateRegistry {

    private final Map<Long, ItemStack> byId = new HashMap<>();
    private final Map<String, Long> byFingerprint = new HashMap<>();
    private final Map<Material, List<Long>> byMaterial = new HashMap<>();

    public void clear() {
        byId.clear();
        byFingerprint.clear();
        byMaterial.clear();
    }

    /** Loads stored templates; rows that no longer deserialize (or are INVALID) are skipped and reported. */
    public void load(List<TemplateRow> rows, Logger logger) {
        clear();
        for (TemplateRow row : rows) {
            if (!"ACTIVE".equals(row.status())) {
                continue;
            }
            try {
                ItemStack item = ItemStack.deserializeBytes(row.bytes());
                if (item.getType().isAir()) {
                    logger.warning("Item template #" + row.id() + " (" + row.material() + ") is empty after loading; its market is frozen");
                    continue;
                }
                register(new TemplateId(row.id()), normalize(item), fingerprint(normalize(item)));
            } catch (RuntimeException e) {
                logger.warning("Item template #" + row.id() + " (" + row.material() + ") cannot be loaded: " + e.getMessage());
            }
        }
    }

    public static ItemStack normalize(ItemStack item) {
        ItemStack copy = item.clone();
        copy.setAmount(1);
        return copy;
    }

    public static String fingerprint(ItemStack normalized) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalized.serializeAsBytes()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** An existing template exactly matching {@code item}, if any. */
    public Optional<TemplateId> find(ItemStack item) {
        ItemStack normalized = normalize(item);
        Long byFp = byFingerprint.get(fingerprint(normalized));
        if (byFp != null && byId.get(byFp).isSimilar(normalized)) {
            return Optional.of(new TemplateId(byFp));
        }
        for (Long id : byMaterial.getOrDefault(normalized.getType(), List.of())) {
            if (byId.get(id).isSimilar(normalized)) {
                return Optional.of(new TemplateId(id));
            }
        }
        return Optional.empty();
    }

    public void register(TemplateId id, ItemStack normalized, String fingerprint) {
        byId.put(id.value(), normalized);
        byFingerprint.put(fingerprint, id.value());
        List<Long> ids = byMaterial.computeIfAbsent(normalized.getType(), m -> new ArrayList<>());
        if (!ids.contains(id.value())) {
            ids.add(id.value());
        }
    }

    public boolean has(TemplateId id) {
        return byId.containsKey(id.value());
    }

    /** A fresh copy of the template with the given amount. */
    public ItemStack stack(TemplateId id, int amount) {
        ItemStack copy = byId.get(id.value()).clone();
        copy.setAmount(amount);
        return copy;
    }

    public int maxStack(TemplateId id) {
        return byId.get(id.value()).getMaxStackSize();
    }

    /** Display name with hover; for unknown templates a plain "#id". */
    public Component name(TemplateId id) {
        ItemStack item = byId.get(id.value());
        return item == null ? Component.text("#" + id.value()) : item.displayName();
    }

    public List<TemplateId> all() {
        return byId.keySet().stream().sorted().map(TemplateId::new).toList();
    }
}
