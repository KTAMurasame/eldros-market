package com.eldros.market.paper.gui.screen;

import com.eldros.market.api.model.OrderSide;
import com.eldros.market.core.storage.jdbc.JdbcLedger.TradeRow;
import com.eldros.market.paper.gui.Menus;
import com.eldros.market.paper.gui.PagedMenu;
import com.eldros.market.paper.i18n.Messages;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** The player's trades, newest first, filterable by bought/sold. Shows the most recent {@value #LIMIT}. */
public final class HistoryMenu extends PagedMenu<TradeRow> {

    private static final int LIMIT = 500;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final OrderSide filter;

    public HistoryMenu(Menus menus, Player player, OrderSide filter, int page) {
        super(menus, player, "history", page);
        this.filter = filter;
    }

    @Override
    protected void onOpen() {
        load(actions.history(player, filter, LIMIT, 0), this::show);
    }

    @Override
    protected void drawControls() {
        button("back", c -> menus.openMain(player));
        String current = filter == null ? "all" : filter == OrderSide.BUY ? "bought" : "sold";
        OrderSide next = filter == null ? OrderSide.BUY : filter == OrderSide.BUY ? OrderSide.SELL : null;
        button("filter", c -> menus.openHistory(player, next, 0),
                Messages.component("filter", text("filters." + current)));
    }

    @Override
    protected ItemStack icon(TradeRow t) {
        ItemStack base = rt.templates().has(t.template()) ? rt.templates().stack(t.template(), 1) : new ItemStack(Material.BARRIER);
        return menus.icons().ofItem(base, (int) Math.min(t.quantity(), 64), lines(t.bought() ? "entry-bought" : "entry-sold",
                Messages.text("qty", t.quantity()), money("price", t.unitPrice()),
                money("total", t.unitPrice().times(t.quantity())),
                Messages.text("time", TIME.format(Instant.ofEpochMilli(t.executedAt())))));
    }

    @Override
    protected void pick(TradeRow t, ClickType click) {
        if (rt.templates().has(t.template())) {
            menus.openBook(player, rt.templates().stack(t.template(), 1));
        }
    }
}
