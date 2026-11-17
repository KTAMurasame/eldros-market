package com.eldros.market.spike;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Throwaway F0 spike entry point. Results go to plugins/EldrosSpike/report-<mc>.txt. */
public final class SpikePlugin extends JavaPlugin {

    private Report report;
    private ItemSpike items;
    private EconomySpike economy;
    private StorageSpike storage;
    private MarkerSpike markers;

    @Override
    public void onEnable() {
        String mc = Bukkit.getMinecraftVersion();
        report = new Report(getDataFolder().toPath(), mc, getLogger());
        items = new ItemSpike(this, report, mc);
        economy = new EconomySpike(this, report);
        storage = new StorageSpike(this, report);
        markers = new MarkerSpike(this, report);
        report.section("S2 enable");
        report.line("minecraft=" + mc + " bukkit=" + Bukkit.getBukkitVersion() + " server=" + Bukkit.getVersion());
        report.line("java=" + System.getProperty("java.version") + " vendor=" + System.getProperty("java.vendor"));
        report.line("plugin api-version=" + getPluginMeta().getAPIVersion() + " classMajor=" + classMajor());
        items.dataVersion();
    }

    private static int classMajor() {
        try (var in = SpikePlugin.class.getResourceAsStream("SpikePlugin.class")) {
            byte[] h = in.readNBytes(8);
            return ((h[6] & 0xff) << 8) | (h[7] & 0xff);
        } catch (Exception e) {
            return -1;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            return false;
        }
        switch (args[0]) {
            case "items" -> items.runAll();
            case "export" -> items.export();
            case "import" -> {
                if (args.length < 2) {
                    return false;
                }
                items.importFrom(args[1]);
            }
            case "eco" -> economy.run();
            case "testeco" -> economy.testEconomy(args.length > 1 ? args[1] : "normal");
            case "storage" -> storage.run();
            case "take", "take-nosave", "check", "clear", "savetime" -> {
                // Console form: /spike <sub> [n] @<player>  (lets the operator drive S4 while the tester only stays online)
                Player player = sender instanceof Player p ? p : null;
                if (args[args.length - 1].startsWith("@")) {
                    player = Bukkit.getPlayerExact(args[args.length - 1].substring(1));
                    args = java.util.Arrays.copyOf(args, args.length - 1);
                }
                if (player == null) {
                    sender.sendMessage("player not found / player only");
                    return true;
                }
                switch (args[0]) {
                    case "take" -> markers.take(player, intArg(args, 1, 10), true);
                    case "take-nosave" -> markers.take(player, intArg(args, 1, 10), false);
                    case "check" -> markers.check(player);
                    case "clear" -> markers.clear(player);
                    default -> markers.saveTime(player, intArg(args, 1, 200));
                }
            }
            default -> {
                return false;
            }
        }
        sender.sendMessage("spike " + args[0] + " done; see report file");
        return true;
    }

    private static int intArg(String[] args, int index, int fallback) {
        try {
            return args.length > index ? Integer.parseInt(args[index]) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
