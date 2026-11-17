package com.eldros.market.paper.gui.screen;

import com.eldros.market.api.model.OrderSide;
import com.eldros.market.core.storage.jdbc.JdbcLedger.OrderRow;
import com.eldros.market.paper.gui.Menus;
import com.eldros.market.paper.gui.PagedMenu;
import com.eldros.market.paper.i18n.Messages;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

/** Active orders with fill ratio. Shift-click cancels (a plain click only explains how), so a stray click never cancels. */
public final class MyOrdersMenu extends PagedMenu<OrderRow> {

    public MyOrdersMenu(Menus menus, Player player, int page) {
        super(menus, player, "orders", page);
    }

    @Override
    protected void onOpen() {
        load(actions.activeOrders(player), this::show);
    }

    @Override
    protected void drawControls() {
        button("back", c -> menus.openMain(player));
        button("refresh", c -> menus.openOrders(player, page));
    }

    @Override
    protected ItemStack icon(OrderRow o) {
        boolean buy = o.side() == OrderSide.BUY;
        long remaining = o.quantity() - o.filled();
        int percent = (int) (o.filled() * 100 / o.quantity());
        ItemStack base = rt.templates().has(o.template()) ? rt.templates().stack(o.template(), 1)
                : new ItemStack(org.bukkit.Material.BARRIER);
        return menus.icons().ofItem(base, (int) Math.min(remaining, 64), lines("entry",
                Messages.text("id", o.id()),
                Messages.component("side", msg.render(lang, buy ? "orders.side-buy" : "orders.side-sell")),
                Messages.text("remaining", remaining), Messages.text("qty", o.quantity()),
                Messages.text("filled", o.filled()), Messages.text("percent", percent),
                money("price", o.unitPrice()),
                buy ? money("reserved", o.unitPrice().times(remaining)) : Messages.text("reserved", remaining)));
    }

    @Override
    protected void pick(OrderRow o, ClickType click) {
        if (!click.isShiftClick()) {
            msg.send(player, "menu.orders.shift-to-cancel");
            return;
        }
        actions.cancel(player, o.id()).thenAccept(ok -> {
            if (isShown()) {
                menus.openOrders(player, page);
            }
        });
    }
}
