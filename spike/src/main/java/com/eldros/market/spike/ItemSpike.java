package com.eldros.market.spike;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.ShulkerBox;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** S3 (item identity) and S7 (export/import across versions). */
final class ItemSpike {

    private final JavaPlugin plugin;
    private final Report report;
    private final String mcVersion;

    ItemSpike(JavaPlugin plugin, Report report, String mcVersion) {
        this.plugin = plugin;
        this.report = report;
        this.mcVersion = mcVersion;
    }

    /** Fixed, ordered sample set. Index = identity across versions for export/import. */
    Map<String, ItemStack> samples() {
        NamespacedKey tag = new NamespacedKey(plugin, "tag");
        Map<String, ItemStack> m = new LinkedHashMap<>();
        m.put("01_stone", new ItemStack(Material.STONE));
        m.put("02_diamond", new ItemStack(Material.DIAMOND));
        m.put("03_sword_fresh", new ItemStack(Material.DIAMOND_SWORD));
        m.put("04_sword_damaged", edit(new ItemStack(Material.DIAMOND_SWORD), i -> i.editMeta(Damageable.class, d -> d.setDamage(10))));
        m.put("05_sword_ench_sharp_then_unb", edit(new ItemStack(Material.DIAMOND_SWORD), i -> i.editMeta(meta -> {
            meta.addEnchant(Enchantment.SHARPNESS, 3, true);
            meta.addEnchant(Enchantment.UNBREAKING, 2, true);
        })));
        m.put("06_sword_ench_unb_then_sharp", edit(new ItemStack(Material.DIAMOND_SWORD), i -> i.editMeta(meta -> {
            meta.addEnchant(Enchantment.UNBREAKING, 2, true);
            meta.addEnchant(Enchantment.SHARPNESS, 3, true);
        })));
        m.put("07_sword_named", edit(new ItemStack(Material.DIAMOND_SWORD), i -> i.editMeta(meta -> meta.displayName(Component.text("Eldros")))));
        m.put("08_sword_lore", edit(new ItemStack(Material.DIAMOND_SWORD), i -> i.editMeta(meta -> meta.lore(List.of(Component.text("line"))))));
        m.put("09_pdc_a", edit(new ItemStack(Material.PAPER), i -> i.editMeta(meta -> meta.getPersistentDataContainer().set(tag, PersistentDataType.STRING, "a"))));
        m.put("10_pdc_b", edit(new ItemStack(Material.PAPER), i -> i.editMeta(meta -> meta.getPersistentDataContainer().set(tag, PersistentDataType.STRING, "b"))));
        m.put("11_custom_model_data", edit(new ItemStack(Material.PAPER), i -> i.editMeta(meta -> meta.setCustomModelData(1001))));
        m.put("12_potion_swiftness", edit(new ItemStack(Material.POTION), i -> i.editMeta(PotionMeta.class, p -> p.setBasePotionType(PotionType.SWIFTNESS))));
        m.put("13_potion_healing", edit(new ItemStack(Material.POTION), i -> i.editMeta(PotionMeta.class, p -> p.setBasePotionType(PotionType.HEALING))));
        m.put("14_book_sharp5", edit(new ItemStack(Material.ENCHANTED_BOOK), i -> i.editMeta(EnchantmentStorageMeta.class, b -> b.addStoredEnchant(Enchantment.SHARPNESS, 5, true))));
        m.put("15_book_sharp4", edit(new ItemStack(Material.ENCHANTED_BOOK), i -> i.editMeta(EnchantmentStorageMeta.class, b -> b.addStoredEnchant(Enchantment.SHARPNESS, 4, true))));
        m.put("16_shulker_empty", new ItemStack(Material.SHULKER_BOX));
        m.put("17_shulker_with_items", edit(new ItemStack(Material.SHULKER_BOX), i -> i.editMeta(BlockStateMeta.class, bsm -> {
            ShulkerBox box = (ShulkerBox) bsm.getBlockState();
            box.getInventory().addItem(new ItemStack(Material.DIAMOND, 5));
            bsm.setBlockState(box);
        })));
        m.put("18_player_head", new ItemStack(Material.PLAYER_HEAD));
        m.put("19_leather_red", edit(new ItemStack(Material.LEATHER_CHESTPLATE), i -> i.editMeta(LeatherArmorMeta.class, l -> l.setColor(Color.RED))));
        m.put("20_ender_pearl", new ItemStack(Material.ENDER_PEARL));
        return m;
    }

    private static ItemStack edit(ItemStack item, java.util.function.Consumer<ItemStack> editor) {
        editor.accept(item);
        return item;
    }

    void runAll() {
        report.section("S3 items @ " + mcVersion);
        dataVersion();
        Map<String, ItemStack> samples = samples();
        roundTrip(samples);
        stability(samples);
        similarityMatrix(samples);
        serializeTiming(samples);
        catalog();
        offMainDeserialize(samples);
    }

    void dataVersion() {
        try {
            @SuppressWarnings("deprecation")
            int dv = Bukkit.getUnsafe().getDataVersion();
            report.line("dataVersion=" + dv + " (UnsafeValues#getDataVersion)");
        } catch (Throwable t) {
            report.line("dataVersion UNAVAILABLE: " + t);
        }
    }

    private void roundTrip(Map<String, ItemStack> samples) {
        int ok = 0;
        for (Map.Entry<String, ItemStack> e : samples.entrySet()) {
            try {
                byte[] bytes = e.getValue().serializeAsBytes();
                ItemStack back = ItemStack.deserializeBytes(bytes);
                boolean similar = back.isSimilar(e.getValue());
                boolean equal = back.equals(e.getValue());
                if (similar && equal) {
                    ok++;
                }
                report.line("roundtrip " + e.getKey() + " bytes=" + bytes.length + " isSimilar=" + similar + " equals=" + equal);
            } catch (Throwable t) {
                report.line("roundtrip " + e.getKey() + " ERROR " + t);
            }
        }
        report.line("roundtrip summary ok=" + ok + "/" + samples.size());
    }

    private void stability(Map<String, ItemStack> samples) {
        Path file = plugin.getDataFolder().toPath().resolve("stability-" + mcVersion + ".txt");
        Map<String, String> previous = readKeyValue(file);
        List<String> lines = new ArrayList<>();
        int unstableInProcess = 0;
        int changedAcrossRestart = 0;
        for (Map.Entry<String, ItemStack> e : samples.entrySet()) {
            Set<String> hashes = new HashSet<>();
            for (int i = 0; i < 1000; i++) {
                hashes.add(sha256(e.getValue().clone().serializeAsBytes()));
            }
            ItemStack fresh = samples().get(e.getKey());
            hashes.add(sha256(fresh.serializeAsBytes()));
            String hash = hashes.iterator().next();
            if (hashes.size() > 1) {
                unstableInProcess++;
                report.line("stability " + e.getKey() + " UNSTABLE in-process distinct=" + hashes.size());
            }
            String prev = previous.get(e.getKey());
            if (prev != null && !prev.equals(hash)) {
                changedAcrossRestart++;
                report.line("stability " + e.getKey() + " CHANGED across restart");
            }
            lines.add(e.getKey() + "=" + hash);
        }
        writeLines(file, lines);
        report.line("stability summary unstableInProcess=" + unstableInProcess
                + " changedAcrossRestart=" + (previous.isEmpty() ? "n/a (first run)" : changedAcrossRestart));
    }

    private void similarityMatrix(Map<String, ItemStack> s) {
        pair(s, "03_sword_fresh", "04_sword_damaged", false);
        pair(s, "05_sword_ench_sharp_then_unb", "06_sword_ench_unb_then_sharp", null);
        pair(s, "03_sword_fresh", "07_sword_named", false);
        pair(s, "03_sword_fresh", "08_sword_lore", false);
        pair(s, "09_pdc_a", "10_pdc_b", false);
        pair(s, "09_pdc_a", "11_custom_model_data", false);
        pair(s, "12_potion_swiftness", "13_potion_healing", false);
        pair(s, "14_book_sharp5", "15_book_sharp4", false);
        pair(s, "16_shulker_empty", "17_shulker_with_items", false);
        boolean freshVsNew = s.get("03_sword_fresh").isSimilar(new ItemStack(Material.DIAMOND_SWORD, 7));
        report.line("isSimilar sword_fresh vs new(amount=7) = " + freshVsNew + " expected=true");
    }

    private void pair(Map<String, ItemStack> s, String a, String b, Boolean expected) {
        boolean ab = s.get(a).isSimilar(s.get(b));
        boolean ba = s.get(b).isSimilar(s.get(a));
        String verdict = expected == null ? "observe" : (ab == expected && ba == expected ? "as-expected" : "UNEXPECTED");
        report.line("isSimilar " + a + " vs " + b + " = " + ab + "/" + ba + " expected=" + expected + " -> " + verdict);
    }

    private void serializeTiming(Map<String, ItemStack> samples) {
        List<ItemStack> items = new ArrayList<>(samples.values());
        long[] ser = new long[10_000];
        long[] sim = new long[10_000];
        for (int i = 0; i < ser.length; i++) {
            ItemStack it = items.get(i % items.size());
            long t0 = System.nanoTime();
            byte[] bytes = it.serializeAsBytes();
            sha256(bytes);
            long t1 = System.nanoTime();
            it.isSimilar(items.get((i + 1) % items.size()));
            long t2 = System.nanoTime();
            ser[i] = t1 - t0;
            sim[i] = t2 - t1;
        }
        report.line("timing main serialize+sha256 " + Stats.summarize(ser));
        report.line("timing main isSimilar " + Stats.summarize(sim));
    }

    private void catalog() {
        World world = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        int items = 0;
        int disabled = 0;
        int failed = 0;
        boolean featureApi = true;
        long t0 = System.nanoTime();
        for (Material mat : Material.values()) {
            if (mat.name().startsWith("LEGACY_") || !mat.isItem() || mat.isAir()) {
                continue;
            }
            // compat-26 compile showed this method is gone in 26.3; record the runtime outcome instead of crashing.
            if (world != null && featureApi) {
                try {
                    if (!mat.isEnabledByFeature(world)) {
                        disabled++;
                        continue;
                    }
                } catch (NoSuchMethodError e) {
                    featureApi = false;
                    report.line("catalog Material#isEnabledByFeature(World) RUNTIME " + e);
                }
            }
            try {
                new ItemStack(mat).serializeAsBytes();
                items++;
            } catch (Throwable t) {
                failed++;
                report.line("catalog FAIL " + mat + " " + t);
            }
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        report.line("catalog items=" + items + " disabledByFeature=" + disabled + " failed=" + failed + " buildAndSerializeMs=" + ms);
    }

    private void offMainDeserialize(Map<String, ItemStack> samples) {
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        samples.forEach((k, v) -> bytes.put(k, v.serializeAsBytes()));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<String, ItemStack> back = new LinkedHashMap<>();
            List<String> errors = new ArrayList<>();
            String thread = Thread.currentThread().getName();
            bytes.forEach((k, b) -> {
                try {
                    back.put(k, ItemStack.deserializeBytes(b));
                } catch (Throwable t) {
                    errors.add(k + ":" + t);
                }
            });
            Bukkit.getScheduler().runTask(plugin, () -> {
                long similar = back.entrySet().stream().filter(e -> e.getValue().isSimilar(samples.get(e.getKey()))).count();
                report.line("offMain deserialize thread=" + thread + " ok=" + back.size() + " errors=" + errors
                        + " similarAfter=" + similar + "/" + samples.size() + " (observation only)");
            });
        });
    }

    void export() {
        Path file = plugin.getDataFolder().toPath().resolve("export-" + mcVersion + ".txt");
        List<String> lines = new ArrayList<>();
        samples().forEach((k, v) -> {
            byte[] b = v.serializeAsBytes();
            lines.add(k + "|" + Base64.getEncoder().encodeToString(b) + "|" + sha256(b));
        });
        writeLines(file, lines);
        report.line("export wrote " + lines.size() + " items to " + file.getFileName());
    }

    void importFrom(String fileName) {
        Path file = plugin.getDataFolder().toPath().resolve(fileName);
        report.section("S7 import " + fileName + " @ " + mcVersion);
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            report.line("import cannot read " + file + ": " + e);
            return;
        }
        Map<String, ItemStack> local = samples();
        int similar = 0;
        int sameBytes = 0;
        int failed = 0;
        for (String line : lines) {
            String[] p = line.split("\\|");
            try {
                byte[] old = Base64.getDecoder().decode(p[1]);
                ItemStack upgraded = ItemStack.deserializeBytes(old);
                boolean sim = upgraded.isSimilar(local.get(p[0]));
                boolean bytesEqual = sha256(upgraded.serializeAsBytes()).equals(sha256(local.get(p[0]).serializeAsBytes()));
                similar += sim ? 1 : 0;
                sameBytes += bytesEqual ? 1 : 0;
                report.line("import " + p[0] + " type=" + upgraded.getType() + " isSimilarToLocal=" + sim + " reserializedBytesEqualLocal=" + bytesEqual);
            } catch (Throwable t) {
                failed++;
                report.line("import " + p[0] + " ERROR " + t);
            }
        }
        report.line("import summary similar=" + similar + "/" + lines.size() + " sameBytes=" + sameBytes + " failed=" + failed);
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, String> readKeyValue(Path file) {
        Map<String, String> map = new LinkedHashMap<>();
        if (!Files.exists(file)) {
            return map;
        }
        try {
            for (String l : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int i = l.indexOf('=');
                if (i > 0) {
                    map.put(l.substring(0, i), l.substring(i + 1));
                }
            }
        } catch (IOException e) {
            report.line("cannot read " + file + ": " + e);
        }
        return map;
    }

    private void writeLines(Path file, List<String> lines) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            report.line("cannot write " + file + ": " + e);
        }
    }
}
