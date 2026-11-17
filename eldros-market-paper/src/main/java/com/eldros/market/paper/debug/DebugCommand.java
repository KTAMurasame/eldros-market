package com.eldros.market.paper.debug;

import com.eldros.market.api.model.OrderSide;
import com.eldros.market.core.domain.Money;
import com.eldros.market.core.domain.NewOrder;
import com.eldros.market.core.domain.PlayerId;
import com.eldros.market.core.journal.JournalEntry;
import com.eldros.market.core.market.OpResult;
import com.eldros.market.core.service.AmountParser;
import com.eldros.market.core.storage.jdbc.JdbcJournal.MailboxRow;
import com.eldros.market.paper.MarketRuntime;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * TEST BUILD ONLY. Console command {@code /eldrosdebug} that drives market operations without a client, so crash
 * points can be hit from a script: {@code buy|sell <player> <material> <qty> <price>}, {@code claim <player>},
 * {@code state <player>}. Sell and item claims need the player online (they touch the inventory).
 */
public final class DebugCommand extends Command {

    private final MarketRuntime rt;

    public DebugCommand(MarketRuntime runtime) {
        super("eldrosdebug");
        this.rt = runtime;
        setDescription("Eldros Market crash-test driver (test build only)");
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) {
            sender.sendMessage("console only");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("eldrosdebug buy|sell <player> <material> <qty> <price> | claim <player> | state <player>");
            return true;
        }
        @SuppressWarnings("deprecation")
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        PlayerId id = new PlayerId(target.getUniqueId());
        switch (args[0]) {
            case "buy", "sell" -> order(sender, args, id, args[0].equals("buy") ? OrderSide.BUY : OrderSide.SELL);
            case "claim" -> claim(sender, id);
            case "cancelall" -> rt.onEngine(() -> rt.tx().inTransaction(c -> rt.ledger().activeOrdersOf(c, id)))
                    .thenAccept(list -> list.forEach(o -> rt.ops().cancel(id, o.id())
                            .whenComplete((r, e) -> report(sender, "CANCEL #" + o.id() + " ", r, e))));
            case "state" -> state(sender, target, id);
            default -> sender.sendMessage("unknown");
        }
        return true;
    }

    private void order(CommandSender out, String[] args, PlayerId id, OrderSide side) {
        Material m = Material.matchMaterial(args[2]);
        long qty = AmountParser.parseQuantity(args[3]).value();
        Money price = AmountParser.parseMoney(args[4], rt.settings().scale()).value();
        rt.resolveTemplate(new ItemStack(m)).thenCompose(t -> {
            NewOrder spec = new NewOrder(id, side, t, price, qty, null);
            return side == OrderSide.BUY ? rt.ops().buy(spec) : rt.ops().sell(spec);
        }).whenComplete((r, e) -> report(out, side + " ", r, e));
    }

    private void claim(CommandSender out, PlayerId id) {
        rt.onEngine(() -> rt.tx().inTransaction(c -> rt.journal().availableFor(c, id))).thenCompose(rows -> {
            if (rows.isEmpty()) {
                return CompletableFuture.completedFuture((OpResult) null);
            }
            MailboxRow row = rows.get(0);
            out.sendMessage("DEBUG claiming mailbox #" + row.id() + " " + row.kind());
            return rt.ops().claim(id, row.id());
        }).whenComplete((r, e) -> report(out, "CLAIM ", r, e));
    }

    private void report(CommandSender out, String what, OpResult r, Throwable e) {
        String line = e != null ? "DEBUG " + what + "FAILED " + e : r == null ? "DEBUG " + what + "nothing" :
                "DEBUG " + what + r.status() + " op=" + r.opId() + " reason=" + r.reason();
        rt.main().execute(() -> out.sendMessage(line));
    }

    private void state(CommandSender out, OfflinePlayer target, PlayerId id) {
        rt.onEngine(() -> rt.tx().inTransaction(c -> {
            List<MailboxRow> mail = rt.journal().availableFor(c, id);
            List<MailboxRow> review = rt.journal().inReviewFor(c, id);
            int orders = rt.ledger().countActiveOrders(c, id);
            List<JournalEntry> pending = rt.journal().pendingForPlayer(c, id);
            return "orders=" + orders + " mailbox=" + mail.stream().map(r -> r.kind() + ":" + (r.kind().equals("MONEY")
                    ? r.amount().minor() : r.qty())).toList() + " review=" + review.size() + " pending=" + pending.size();
        })).whenComplete((s, e) -> rt.main().execute(() -> {
            Player online = target.getPlayer();
            String inv = online == null ? "offline" : "diamonds=" + count(online, Material.DIAMOND);
            out.sendMessage("DEBUG STATE " + target.getName() + " " + inv + " " + (e != null ? e : s));
        }));
    }

    private static int count(Player p, Material m) {
        int n = 0;
        for (ItemStack s : p.getInventory().getStorageContents()) {
            if (s != null && s.getType() == m) {
                n += s.getAmount();
            }
        }
        return n;
    }
}
