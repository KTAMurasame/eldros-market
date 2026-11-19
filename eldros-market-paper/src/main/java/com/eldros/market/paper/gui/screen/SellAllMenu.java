package com.eldros.market.paper.gui.screen;

import com.eldros.market.api.model.OrderSide;
import com.eldros.market.api.model.TemplateId;
import com.eldros.market.core.domain.Money;
import com.eldros.market.core.engine.BookView;
import com.eldros.market.paper.gui.Menu;
import com.eldros.market.paper.gui.Menus;
import com.eldros.market.paper.i18n.Messages;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * "Sell everything": every item in the inventory that has buy orders, with what it would fetch right now.
 * Confirming sells them one after another as instant orders, each limited at its quoted worst price.
 */
public final class SellAllMenu extends Menu {

    /** One item kind the player holds that has buyers right now. */
    private record Entry(TemplateId template, ItemStack sample, long held, BookView.Preview quote) {
    }

    private List<Entry> entries;
    private boolean submitting;

    public SellAllMenu(Menus menus, Player player) {
        super(menus, player, "sell-all");
    }

    @Override
    protected void onOpen() {
        Map<TemplateId, Long> held = new LinkedHashMap<>();
        Map<TemplateId, ItemStack> samples = new LinkedHashMap<>();
        for (ItemStack s : player.getInventory().getStorageContents()) {
            if (s == null || s.getType().isAir()) {
                continue;
            }
            Optional<TemplateId> t = rt.templates().find(s); // only items the market already knows; nothing is created
            if (t.isEmpty() || rt.filter().refusal(s).isPresent()) {
                continue;
            }
            held.merge(t.get(), (long) s.getAmount(), Long::sum);
            samples.putIfAbsent(t.get(), s);
        }
        List<CompletableFuture<Entry>> quotes = new ArrayList<>();
        held.forEach((t, n) -> quotes.add(actions.instantQuote(player, OrderSide.SELL, t, n)
                .thenApply(q -> new Entry(t, samples.get(t), n, q))));
        load(CompletableFuture.allOf(quotes.toArray(CompletableFuture[]::new)).thenApply(v -> quotes.stream()
                .map(CompletableFuture::join)
                .filter(e -> e.quote().fillable() > 0 && e.quote().worstPrice().isPresent())
                .toList()), list -> {
            entries = list;
            redraw();
        });
    }

    @Override
    protected void draw() {
        button("cancel", c -> menus.openMain(player));
        if (entries == null) {
            button("loading", null);
            return;
        }
        if (entries.isEmpty()) {
            button("empty", null);
            return;
        }
        List<Integer> slots = layout.group("items");
        Money total = Money.ZERO;
        long items = 0;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            total = total.plus(e.quote().cost());
            items += e.quote().fillable();
            if (i < slots.size()) {
                TagResolver[] tags = {Messages.text("qty", e.quote().fillable()), Messages.text("held", e.held()),
                        money("total", e.quote().cost()), money("worst", e.quote().worstPrice().get())};
                List<net.kyori.adventure.text.Component> lore = new ArrayList<>(lines("entry.lore", tags));
                if (e.quote().fillable() < e.held()) {
                    lore.addAll(lines("entry.partial", tags));
                }
                set(slots.get(i), menus.icons().ofItem(e.sample(), (int) Math.min(64, e.quote().fillable()), lore), null);
            }
        }
        TagResolver[] sum = {Messages.text("kinds", entries.size()), Messages.text("qty", items), money("total", total)};
        button("summary", null, sum);
        button("confirm", c -> submit(), sum);
    }

    private void submit() {
        if (submitting || entries == null || entries.isEmpty()) {
            return;
        }
        submitting = true;
        player.closeInventory();
        sellNext(new ArrayList<>(entries), 0);
    }

    /** One instant sell at a time; each waits for the previous one, so the player lock is never contended. */
    private void sellNext(List<Entry> list, int index) {
        if (index >= list.size() || !player.isOnline()) {
            return;
        }
        Entry e = list.get(index);
        actions.instant(player, OrderSide.SELL, e.template(), e.quote().fillable(), e.quote().worstPrice().get())
                .thenAccept(ok -> sellNext(list, index + 1));
    }
}
