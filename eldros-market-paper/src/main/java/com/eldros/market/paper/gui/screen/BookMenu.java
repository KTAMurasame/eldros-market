package com.eldros.market.paper.gui.screen;

import com.eldros.market.api.model.OrderSide;
import com.eldros.market.api.model.TemplateId;
import com.eldros.market.core.domain.Money;
import com.eldros.market.core.engine.BookView.DepthLevel;
import com.eldros.market.core.service.AmountParser;
import com.eldros.market.core.service.ValidationCode;
import com.eldros.market.paper.action.MarketActions;
import com.eldros.market.paper.gui.Menu;
import com.eldros.market.paper.gui.Menus;
import com.eldros.market.paper.i18n.Messages;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Optional;

/**
 * The order book of one item: best asks and bids with the player's own share marked. Clicking a level starts an
 * order against it at that price ("use best price"); the buy/sell buttons start a fresh order.
 */
public final class BookMenu extends Menu {

    private final ItemStack item;
    private final Optional<TemplateId> template;
    private MarketActions.Book book;

    public BookMenu(Menus menus, Player player, ItemStack item) {
        super(menus, player, "book");
        this.item = item.clone();
        this.item.setAmount(1);
        this.template = rt.templates().find(this.item);
    }

    @Override
    protected TagResolver[] titleResolvers() {
        return new TagResolver[] {Messages.component("item", item.displayName())};
    }

    @Override
    protected void onOpen() {
        if (template.isEmpty()) {
            book = new MarketActions.Book(List.of(), List.of()); // nobody traded this exact item yet
            redraw();
            return;
        }
        int levels = Math.max(layout.group("asks").size(), layout.group("bids").size());
        load(actions.book(player, template.get(), levels), b -> {
            book = b;
            redraw();
        });
    }

    private Optional<Money> best(List<DepthLevel> side) {
        return side.isEmpty() ? Optional.empty() : Optional.of(side.get(0).price());
    }

    @Override
    protected void draw() {
        button("back", c -> menus.openBrowse(player, null, 0));
        button("refresh", c -> menus.openBook(player, item));
        if (book == null) {
            button("loading", null);
            return;
        }
        Optional<Money> bestAsk = best(book.asks());
        Optional<Money> bestBid = best(book.bids());
        layout.button("item").ifPresent(b -> set(b.slot(), menus.icons().ofItem(item, 1,
                lines("item.lore", price("best-ask", bestAsk), price("best-bid", bestBid))), null));
        levels(book.asks(), layout.group("asks"), "ask", OrderSide.BUY);
        levels(book.bids(), layout.group("bids"), "bid", OrderSide.SELL);
        Money fallback = rt.limitsFor(item.getType()).minUnitPrice();
        button("buy", c -> menus.openCreate(player, new Menus.Draft(item, OrderSide.BUY, 1,
                bestAsk.or(() -> bestBid).orElse(fallback))));
        button("sell", c -> menus.openCreate(player, new Menus.Draft(item, OrderSide.SELL, 1,
                bestBid.or(() -> bestAsk).orElse(fallback))));
        if (template.isPresent()) {
            TemplateId t = template.get();
            button("buy-now", c -> askBuyNow(t), price("best-ask", bestAsk));
            button("sell-now", c -> {
                long held = owned();
                if (held <= 0) {
                    msg.send(player, "instant.none-held", Messages.component("item", item.displayName()));
                    return;
                }
                menus.openInstant(player, item, t, OrderSide.SELL, held);
            }, price("best-bid", bestBid), Messages.text("held", owned()));
        }
        if (book.asks().isEmpty() && book.bids().isEmpty()) {
            button("empty", null);
        }
    }

    /** Asks in chat how many to buy instantly, then shows the instant-trade confirmation. */
    private void askBuyNow(TemplateId t) {
        menus.input(player, "menu.prompt.instant-quantity", "1", text -> {
            AmountParser.Result<Long> q = AmountParser.parseQuantity(text);
            if (!q.isOk() || q.value() <= 0) {
                actions.validation(player, q.isOk() ? ValidationCode.QUANTITY_INVALID : q.error());
                menus.openBook(player, item);
                return;
            }
            menus.openInstant(player, item, t, OrderSide.BUY, q.value());
        }, () -> menus.openBook(player, item));
    }

    private long owned() {
        long n = 0;
        for (ItemStack s : player.getInventory().getStorageContents()) {
            if (s != null && s.isSimilar(item)) {
                n += s.getAmount();
            }
        }
        return n;
    }

    /** One side; clicking a level opens an order of {@code clickSide} at that level's price for its quantity. */
    private void levels(List<DepthLevel> levels, List<Integer> slots, String kind, OrderSide clickSide) {
        Material material = layout.icon(kind, kind.equals("ask") ? Material.RED_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE);
        for (int i = 0; i < slots.size() && i < levels.size(); i++) {
            DepthLevel l = levels.get(i);
            TagResolver[] tags = {money("price", l.price()), Messages.text("qty", l.quantity()),
                    Messages.text("orders", l.orders()), Messages.text("own", l.ownQuantity())};
            List<net.kyori.adventure.text.Component> lore = new java.util.ArrayList<>(lines(kind + ".lore", tags));
            if (l.ownQuantity() > 0) {
                lore.addAll(lines("own.lore", tags));
            }
            long qty = Math.max(1, Math.min(l.quantity(), rt.settings().limits().maxQuantityPerOrder()));
            set(slots.get(i), menus.icons().of(material, text(kind + ".name", tags), lore),
                    c -> menus.openCreate(player, new Menus.Draft(item, clickSide, qty, l.price())));
        }
    }

    private TagResolver price(String name, Optional<Money> price) {
        return price.map(p -> money(name, p)).orElseGet(() -> Messages.component(name, text("none")));
    }
}
