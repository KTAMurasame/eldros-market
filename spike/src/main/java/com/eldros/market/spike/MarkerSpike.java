package com.eldros.market.spike;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;

/**
 * S4: does an inventory change + PDC marker become durable together via Player#saveData?
 * Uses DIAMOND only, so the expected state after a hard kill is easy to check.
 * Every step is appended to s4-log.txt with fsync before the next step, so the log survives a kill.
 */
final class MarkerSpike {

    private final JavaPlugin plugin;
    private final Report report;

    MarkerSpike(JavaPlugin plugin, Report report) {
        this.plugin = plugin;
        this.report = report;
    }

    void take(Player player, int n, boolean save) {
        if (!player.getInventory().containsAtLeast(new ItemStack(Material.DIAMOND), n)) {
            report.line("take: player has fewer than " + n + " diamonds");
            return;
        }
        String op = "op_" + UUID.randomUUID().toString().substring(0, 8);
        int before = count(player);
        // Inventory change and marker in the same synchronous block (03 §7.1, 04 R-1).
        player.getInventory().removeItem(new ItemStack(Material.DIAMOND, n));
        player.getPersistentDataContainer().set(key(op), PersistentDataType.INTEGER, n);
        durableLog("TAKE " + op + " n=" + n + " before=" + before + " after=" + count(player) + " save=" + save);
        if (save) {
            long t0 = System.nanoTime();
            player.saveData();
            long ms = (System.nanoTime() - t0) / 1_000;
            durableLog("SAVED " + op + " saveDataMicros=" + ms);
            report.line("take " + op + " n=" + n + " saved in " + ms + "us -> now hard-kill the server to test durability");
        } else {
            report.line("take " + op + " n=" + n + " NOT saved -> hard-kill now (before autosave)");
        }
    }

    void check(Player player) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        StringBuilder markers = new StringBuilder();
        for (NamespacedKey k : pdc.getKeys()) {
            if (k.getNamespace().equals(plugin.getName().toLowerCase()) && k.getKey().startsWith("op_")) {
                markers.append(k.getKey()).append('=').append(pdc.get(k, PersistentDataType.INTEGER)).append(' ');
            }
        }
        String line = "CHECK diamonds=" + count(player) + " markers=[" + markers.toString().trim() + "]";
        durableLog(line);
        report.line(line);
    }

    void clear(Player player) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        int removed = 0;
        for (NamespacedKey k : pdc.getKeys()) {
            if (k.getNamespace().equals(plugin.getName().toLowerCase()) && k.getKey().startsWith("op_")) {
                pdc.remove(k);
                removed++;
            }
        }
        player.saveData();
        report.line("clear removed " + removed + " markers");
    }

    void saveTime(Player player, int n) {
        long[] samples = new long[n];
        for (int i = 0; i < n; i++) {
            long t0 = System.nanoTime();
            player.saveData();
            samples[i] = System.nanoTime() - t0;
        }
        report.line("timing main Player#saveData " + Stats.summarize(samples));
    }

    private NamespacedKey key(String op) {
        return new NamespacedKey(plugin, op);
    }

    private static int count(Player p) {
        int c = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) {
            if (it != null && it.getType() == Material.DIAMOND) {
                c += it.getAmount();
            }
        }
        return c;
    }

    private void durableLog(String text) {
        Path file = plugin.getDataFolder().toPath().resolve("s4-log.txt");
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ch.write(ByteBuffer.wrap((Instant.now() + " " + text + "\n").getBytes(StandardCharsets.UTF_8)));
            ch.force(true);
        } catch (IOException e) {
            report.line("s4 log ERROR " + e);
        }
    }
}
