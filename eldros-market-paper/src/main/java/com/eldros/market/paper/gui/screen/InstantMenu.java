package com.eldros.market.paper.gui.screen;

import com.eldros.market.api.model.OrderSide;
import com.eldros.market.api.model.TemplateId;
import com.eldros.market.core.engine.BookView;
import com.eldros.market.paper.gui.Menu;
import com.eldros.market.paper.gui.Menus;
import com.eldros.market.paper.i18n.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Confirmation of an instant buy or sell: what trades right now, for how much, and the worst price the player
 * accepts. Confirming executes an immediate-or-cancel order limited at that price; nothing is left in the book.
 */
public final class InstantMenu extends Menu {

    private final ItemStack item;
    private final TemplateId template;
    private final OrderSide side;
    private final long wanted;
    private BookView.Preview quote;
    private boolean submitting;

    public InstantMenu(Menus menus, Player player, ItemStack item, TemplateId template, OrderSide side, long wanted) {
        super(menus, player, "instant");
        this.item = item.clone();
        this.item.setAmount(1);
        this.template = template;
        this.side = side;
        this.wanted = wanted;
    }

    @Override
    protected TagResolver[] titleResolvers() {
        return new TagResolver[] {Messages.component("item", item.displayName())};
    }

    @Override
    protected void onOpen() {
        load(actions.instantQuote(player, side, template, wanted), q -> {
            quote = q;
            redraw();
        });
    }

    @Override
    protected void draw() {
        boolean sell = side == OrderSide.SELL;
        button("cancel", c -> menus.openBook(player, item));
        if (quote == null) {
            button("loading", null);
            return;
        }
        if (quote.fillable() == 0 || quote.worstPrice().isEmpty()) {
            layout.button("summary").ifPresent(b -> set(b.slot(), menus.icons().ofItem(item, 1,
                    lines(sell ? "summary.no-buyers" : "summary.no-sellers")), null));
            return;
        }
        TagResolver[] tags = {Messages.component("item", item.displayName()), Messages.text("qty", quote.fillable()),
                Messages.text("wanted", wanted), money("total", quote.cost()), money("worst", quote.worstPrice().get())};
        List<Component> lore = new ArrayList<>(lines(sell ? "summary.lore-sell" : "summary.lore-buy", tags));
        if (quote.fillable() < wanted) {
            lore.addAll(lines("summary.partial", tags));
        }
        if (quote.crossesOwn()) {
            lore.addAll(lines("summary.own-skipped", tags));
        }
        int bps = sell ? actions.taxRate(player) : 0;
        if (bps > 0) {
            lore.addAll(lines("summary.tax", Messages.text("rate", java.math.BigDecimal.valueOf(bps, 2).stripTrailingZeros().toPlainString()),
                    money("tax", com.eldros.market.core.service.Fees.tax(quote.cost(), bps))));
        }
        layout.button("summary").ifPresent(b -> set(b.slot(), menus.icons().ofItem(item, (int) Math.min(64, quote.fillable()), lore), null));
        button("confirm", c -> submit(), tags);
    }

    private void submit() {
        if (submitting || quote == null || quote.worstPrice().isEmpty()) {
            return;
        }
        submitting = true;
        player.closeInventory();
        actions.instant(player, side, template, quote.fillable(), quote.worstPrice().get()).thenAccept(ok -> {
            if (ok && player.isOnline() && player.getOpenInventory().getType() == InventoryType.CRAFTING) {
                menus.openBook(player, item);
            }
        });
    }
}
