package com.eldros.market.paper.gui.screen;

import com.eldros.market.api.model.OrderSide;
import com.eldros.market.api.model.TemplateId;
import com.eldros.market.core.engine.BookView;
import com.eldros.market.paper.gui.Menu;
import com.eldros.market.paper.gui.Menus;
import com.eldros.market.paper.gui.Menus.Draft;
import com.eldros.market.paper.i18n.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Final summary before an order is placed, with a warning when the player's own opposite order is in range. */
public final class ConfirmMenu extends Menu {

    private final Draft draft;
    private final Optional<TemplateId> template;
    private BookView.Preview preview;
    private boolean submitting;

    public ConfirmMenu(Menus menus, Player player, Draft draft) {
        super(menus, player, "confirm");
        this.draft = draft;
        this.template = rt.templates().find(draft.item());
    }

    @Override
    protected void onOpen() {
        if (template.isPresent()) {
            load(actions.preview(player, draft.side(), template.get(), draft.price(), draft.quantity()), p -> {
                preview = p;
                redraw();
            });
        }
    }

    @Override
    protected void draw() {
        boolean buy = draft.side() == OrderSide.BUY;
        TagResolver[] tags = {
                Messages.component("side", msg.render(lang, buy ? "orders.side-buy" : "orders.side-sell")),
                Messages.component("item", draft.item().displayName()),
                Messages.text("qty", draft.quantity()), money("price", draft.price()),
                money("total", draft.price().times(draft.quantity()))};
        List<Component> lore = new ArrayList<>(lines(buy ? "summary.lore-buy" : "summary.lore-sell", tags));
        if (preview != null && preview.fillable() > 0) {
            lore.addAll(lines("summary.fill", Messages.text("fill", preview.fillable()), money("cost", preview.cost())));
        }
        if (preview != null && preview.crossesOwn()) {
            lore.addAll(lines("summary.own-cross"));
        }
        layout.button("summary").ifPresent(b -> set(b.slot(), menus.icons().ofItem(draft.item(), 1, lore), null));
        button("confirm", c -> submit(), tags);
        button("cancel", c -> menus.openCreate(player, draft), tags);
    }

    private void submit() {
        if (submitting) {
            return;
        }
        submitting = true;
        player.closeInventory();
        actions.place(player, draft.side(), draft.item(), draft.quantity(), draft.price()).thenAccept(ok -> {
            // Reopen the book only if the player has not opened something else meanwhile (CRAFTING = no container open).
            if (ok && player.isOnline() && player.getOpenInventory().getType() == org.bukkit.event.inventory.InventoryType.CRAFTING) {
                menus.openBook(player, draft.item());
            }
        });
    }
}
