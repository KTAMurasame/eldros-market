package com.eldros.market.paper.gui.screen;

import com.eldros.market.api.model.OrderSide;
import com.eldros.market.api.model.TemplateId;
import com.eldros.market.core.domain.Money;
import com.eldros.market.core.engine.BookView;
import com.eldros.market.core.service.AmountParser;
import com.eldros.market.core.service.LimitSettings;
import com.eldros.market.paper.gui.Menu;
import com.eldros.market.paper.gui.Menus;
import com.eldros.market.paper.gui.Menus.Draft;
import com.eldros.market.paper.i18n.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Side, quantity (±1/±10/±64 or typed), unit price (±1/±10 or typed), total and the estimated immediate fill. */
public final class CreateOrderMenu extends Menu {

    private Draft draft;
    private final Optional<TemplateId> template;
    private BookView.Preview preview;
    private long previewToken;

    public CreateOrderMenu(Menus menus, Player player, Draft draft) {
        super(menus, player, "create");
        this.draft = draft;
        this.template = rt.templates().find(draft.item());
    }

    @Override
    protected TagResolver[] titleResolvers() {
        return new TagResolver[] {Messages.component("item", draft.item().displayName())};
    }

    @Override
    protected void onOpen() {
        refreshPreview();
    }

    private void change(Draft next) {
        draft = clamp(next);
        refreshPreview();
        redraw();
    }

    private Draft clamp(Draft d) {
        LimitSettings l = rt.settings().limits();
        long qty = Math.max(1, Math.min(d.quantity(), l.maxQuantityPerOrder()));
        Money price = d.price();
        if (price.compareTo(l.minUnitPrice()) < 0) {
            price = l.minUnitPrice();
        }
        if (price.compareTo(l.maxUnitPrice()) > 0) {
            price = l.maxUnitPrice();
        }
        return new Draft(d.item(), d.side(), qty, price);
    }

    private void refreshPreview() {
        long token = ++previewToken;
        preview = null;
        if (template.isEmpty()) {
            preview = new BookView.Preview(0, Money.ZERO, false);
            return;
        }
        load(actions.preview(player, draft.side(), template.get(), draft.price(), draft.quantity()), p -> {
            if (token == previewToken) {
                preview = p;
                redraw();
            }
        });
    }

    private long owned() {
        long n = 0;
        for (ItemStack s : player.getInventory().getStorageContents()) {
            if (s != null && s.isSimilar(draft.item())) {
                n += s.getAmount();
            }
        }
        return n;
    }

    private Money unit() {
        long minor = 1;
        for (int i = 0; i < rt.settings().scale().decimalPlaces(); i++) {
            minor *= 10;
        }
        return Money.ofMinor(minor);
    }

    private Money shift(Money price, long units) {
        long minor = price.minor() + units * unit().minor();
        return Money.ofMinor(Math.max(0, minor));
    }

    @Override
    protected void draw() {
        boolean buy = draft.side() == OrderSide.BUY;
        TagResolver[] tags = {
                Messages.component("side", msg.render(lang, buy ? "orders.side-buy" : "orders.side-sell")),
                Messages.text("qty", draft.quantity()), money("price", draft.price()),
                money("total", draft.price().times(draft.quantity())), Messages.text("owned", owned())};

        List<Component> lore = new ArrayList<>(lines("item.lore", tags));
        if (preview == null) {
            lore.addAll(lines("preview-loading"));
        } else {
            lore.addAll(lines(preview.fillable() > 0 ? "preview" : "preview-none",
                    Messages.text("fill", preview.fillable()), Messages.text("qty", draft.quantity()),
                    money("cost", preview.cost())));
            if (preview.crossesOwn()) {
                lore.addAll(lines("own-cross"));
            }
        }
        if (!buy) {
            lore.addAll(lines("owned", tags));
        }
        layout.button("item").ifPresent(b -> set(b.slot(), menus.icons().ofItem(draft.item(), 1, lore), null));

        button("side", buy ? Material.LIME_DYE : Material.RED_DYE,
                c -> change(draft.withSide(buy ? OrderSide.SELL : OrderSide.BUY)), tags);

        for (long step : new long[] {1, 10, 64}) {
            button("qty-minus-" + step, c -> change(draft.withQuantity(draft.quantity() - step)), tags);
            button("qty-plus-" + step, c -> change(draft.withQuantity(draft.quantity() + step)), tags);
        }
        button("qty-max", c -> change(draft.withQuantity(buy ? rt.settings().limits().maxQuantityPerOrder() : Math.max(1, owned()))), tags);
        button("qty-input", c -> ask("menu.prompt.quantity", text -> {
            AmountParser.Result<Long> q = AmountParser.parseQuantity(text);
            if (!q.isOk()) {
                actions.validation(player, q.error());
                return draft;
            }
            return draft.withQuantity(q.value());
        }), tags);

        for (long step : new long[] {1, 10}) {
            button("price-minus-" + step, c -> change(draft.withPrice(shift(draft.price(), -step))), tags);
            button("price-plus-" + step, c -> change(draft.withPrice(shift(draft.price(), step))), tags);
        }
        button("price-input", c -> ask("menu.prompt.price", text -> {
            AmountParser.Result<Money> m = AmountParser.parseMoney(text, rt.settings().scale());
            if (!m.isOk()) {
                actions.validation(player, m.error());
                return draft;
            }
            return draft.withPrice(m.value());
        }), tags);

        button("confirm", c -> menus.openConfirm(player, draft), tags);
        button("back", c -> menus.openBook(player, draft.item()));
    }

    /** Closes the menu, asks in chat, and reopens with the answer applied (or unchanged on cancel). */
    private void ask(String promptKey, java.util.function.Function<String, Draft> apply) {
        Draft before = draft;
        player.closeInventory();
        msg.send(player, promptKey);
        menus.prompt().ask(player, text -> {
            Draft next = apply.apply(text);
            // A typed value outside the limits is reported, then clamped, so the player knows why it changed.
            var check = rt.limits().validate(next.price(), next.quantity(), 0, Integer.MAX_VALUE);
            if (!check.isOk()) {
                actions.validation(player, check.code());
            }
            menus.openCreate(player, clamp(next));
        }, () -> menus.openCreate(player, before));
    }
}
