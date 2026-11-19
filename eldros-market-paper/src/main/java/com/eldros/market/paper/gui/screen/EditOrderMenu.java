package com.eldros.market.paper.gui.screen;

import com.eldros.market.api.model.OrderSide;
import com.eldros.market.core.domain.Money;
import com.eldros.market.core.service.AmountParser;
import com.eldros.market.core.service.LimitSettings;
import com.eldros.market.core.storage.jdbc.JdbcLedger.OrderRow;
import com.eldros.market.paper.gui.Menu;
import com.eldros.market.paper.gui.Menus;
import com.eldros.market.paper.i18n.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Changes one active order: a new unit price and/or more items. Shows what that costs (a higher bid pays the
 * difference, added sell items leave the inventory) or returns (a lower bid's surplus goes to the mailbox).
 */
public final class EditOrderMenu extends Menu {

    private final OrderRow order;
    private final long remaining;
    private Money price;
    private long add;
    private boolean submitting;

    public EditOrderMenu(Menus menus, Player player, OrderRow order, Money price, long add) {
        super(menus, player, "edit");
        this.order = order;
        this.remaining = order.quantity() - order.filled();
        this.price = price;
        this.add = add;
    }

    private boolean buy() {
        return order.side() == OrderSide.BUY;
    }

    private ItemStack item() {
        return rt.templates().has(order.template()) ? rt.templates().stack(order.template(), 1) : new ItemStack(Material.BARRIER);
    }

    @Override
    protected TagResolver[] titleResolvers() {
        return new TagResolver[] {Messages.component("item", item().displayName())};
    }

    private Money unit() {
        long minor = 1;
        for (int i = 0; i < rt.settings().scale().decimalPlaces(); i++) {
            minor *= 10;
        }
        return Money.ofMinor(minor);
    }

    private void change(Money newPrice, long newAdd) {
        LimitSettings l = rt.limitsFor(order.template());
        if (newPrice.compareTo(l.minUnitPrice()) < 0) {
            actions.validation(player, com.eldros.market.core.service.ValidationCode.PRICE_TOO_LOW, l);
        } else if (newPrice.compareTo(l.maxUnitPrice()) > 0) {
            actions.validation(player, com.eldros.market.core.service.ValidationCode.PRICE_TOO_HIGH, l);
        }
        Money p = newPrice.compareTo(l.minUnitPrice()) < 0 ? l.minUnitPrice() : newPrice;
        p = p.compareTo(l.maxUnitPrice()) > 0 ? l.maxUnitPrice() : p;
        long maxAdd = Math.max(0, l.maxQuantityPerOrder() - remaining);
        price = p;
        add = Math.max(0, Math.min(newAdd, maxAdd));
        redraw();
    }

    @Override
    protected void draw() {
        long total = remaining + add;
        TagResolver[] tags = {
                Messages.component("side", msg.render(lang, buy() ? "orders.side-buy" : "orders.side-sell")),
                money("old-price", order.unitPrice()), money("price", price),
                Messages.text("remaining", remaining), Messages.text("total", total), Messages.text("add", add)};
        List<Component> lore = new ArrayList<>(lines("item.lore", tags));
        boolean changed = add > 0 || !price.equals(order.unitPrice());
        if (buy()) {
            long diff = price.times(total).minor() - order.unitPrice().times(remaining).minor();
            if (diff > 0) {
                lore.addAll(lines("cost.pay", money("amount", Money.ofMinor(diff))));
            } else if (diff < 0) {
                lore.addAll(lines("cost.refund", money("amount", Money.ofMinor(-diff))));
            }
        } else if (add > 0) {
            lore.addAll(lines("cost.items", tags));
        }
        if (changed) {
            lore.addAll(lines("queue"));
        }
        layout.button("item").ifPresent(b -> set(b.slot(), menus.icons().ofItem(item(), (int) Math.min(64, Math.max(1, total)), lore), null));

        for (long step : new long[] {1, 10}) {
            button("price-minus-" + step, c -> change(Money.ofMinor(Math.max(0, price.minor() - step * unit().minor())), add), tags);
            button("price-plus-" + step, c -> change(Money.ofMinor(price.minor() + step * unit().minor()), add), tags);
        }
        button("price-input", c -> ask("menu.prompt.price", menus.plain(price), text -> {
            AmountParser.Result<Money> m = AmountParser.parseMoney(text, rt.settings().scale());
            if (!m.isOk()) {
                actions.validation(player, m.error());
                return null;
            }
            return new long[] {m.value().minor(), add};
        }), tags);
        for (long step : new long[] {1, 10, 64}) {
            button("qty-plus-" + step, c -> change(price, add + step), tags);
        }
        for (long step : new long[] {1, 10}) {
            button("qty-minus-" + step, c -> change(price, add - step), tags);
        }
        button("qty-input", c -> ask("menu.prompt.add-quantity", Long.toString(Math.max(1, add)), text -> {
            AmountParser.Result<Long> q = AmountParser.parseQuantity(text);
            if (!q.isOk()) {
                actions.validation(player, q.error());
                return null;
            }
            return new long[] {price.minor(), q.value()};
        }), tags);
        button("back", c -> menus.openOrders(player, 0));
        if (changed) {
            button("confirm", c -> submit(total), tags);
        }
    }

    /** Asks for a value (anvil or chat); the parser returns {price minor, add} or null to keep the current values. */
    private void ask(String promptKey, String initial, Function<String, long[]> parse) {
        Money keepPrice = price;
        long keepAdd = add;
        menus.input(player, promptKey, initial, text -> {
            long[] v = parse.apply(text);
            EditOrderMenu next = new EditOrderMenu(menus, player, order, keepPrice, keepAdd);
            next.open();
            if (v != null) {
                next.change(Money.ofMinor(v[0]), v[1]);
            }
        }, () -> new EditOrderMenu(menus, player, order, keepPrice, keepAdd).open());
    }

    private void submit(long total) {
        if (submitting) {
            return;
        }
        submitting = true;
        player.closeInventory();
        actions.edit(player, order.id(), order.template(), order.side(), price, total, add).thenAccept(ok -> {
            if (player.isOnline() && player.getOpenInventory().getType() == org.bukkit.event.inventory.InventoryType.CRAFTING) {
                menus.openOrders(player, 0);
            }
        });
    }
}
