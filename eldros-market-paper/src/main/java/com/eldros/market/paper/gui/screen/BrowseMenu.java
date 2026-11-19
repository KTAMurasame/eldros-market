package com.eldros.market.paper.gui.screen;

import com.eldros.market.core.domain.Money;
import com.eldros.market.core.engine.BookView.MarketSummary;
import com.eldros.market.paper.gui.MarketSort;
import com.eldros.market.paper.gui.Menus;
import com.eldros.market.paper.gui.ItemSearch;
import com.eldros.market.paper.gui.PagedMenu;
import com.eldros.market.paper.i18n.Messages;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Markets with resting orders: best ask/bid and depth. Search by item id or name via chat. */
public final class BrowseMenu extends PagedMenu<MarketSummary> {

    private final String search;

    public BrowseMenu(Menus menus, Player player, String search, int page) {
        super(menus, player, "browse", page);
        this.search = search == null || search.isBlank() ? null : search.toLowerCase(Locale.ROOT);
    }

    @Override
    protected TagResolver[] titleResolvers() {
        return new TagResolver[] {Messages.text("search", search == null ? "" : search)};
    }

    @Override
    protected void onOpen() {
        ItemSearch finder = search == null ? null : ItemSearch.of(search, msg.searchAliases());
        Menus.View view = view();
        load(actions.markets(), list -> show(MarketSort.sort(list.stream()
                .filter(s -> rt.templates().has(s.template()))
                .filter(s -> inCategory(view.category(), rt.templates().stack(s.template(), 1).getType()))
                .filter(s -> finder == null || matches(rt.templates().stack(s.template(), 1), finder))
                .toList(), view.sort(), t -> rt.templates().stack(t, 1).getType().getKey().getKey())));
    }

    private Menus.View view() {
        Menus.View v = menus.browseView(player);
        return new Menus.View(knownCategory(v.category()), v.sort());
    }

    private void change(Menus.View next) {
        menus.browseView(player, next);
        menus.openBrowse(player, search, 0);
    }

    /** Every search word matches the item id (directly or via the language's search words) or its custom name. */
    static boolean matches(ItemStack item, ItemSearch finder) {
        String name = PlainTextComponentSerializer.plainText().serialize(item.displayName());
        return finder.matches(item.getType().getKey().getKey(), name);
    }

    @Override
    protected void drawControls() {
        button("back", c -> menus.openMain(player));
        button("search", c -> {
            menus.input(player, "menu.prompt.search", search == null ? "" : search,
                    text -> menus.openBrowse(player, text, 0), () -> menus.openBrowse(player, search, page));
        }, Messages.text("search", search == null ? "-" : search));
        if (search != null) {
            button("clear-search", c -> menus.openBrowse(player, null, 0));
        }
        button("catalog", c -> menus.openCatalog(player, null, 0));
        Menus.View view = view();
        categoryButton(view.category(), id -> change(new Menus.View(id, view.sort())));
        List<net.kyori.adventure.text.Component> sorts = java.util.Arrays.stream(MarketSort.values())
                .map(s -> text("sort-" + s.name().toLowerCase(Locale.ROOT).replace('_', '-'))).toList();
        choiceButton("sort", null, sorts, view.sort().ordinal(),
                click -> change(new Menus.View(view.category(), click.isRightClick() ? view.sort().previous() : view.sort().next())));
    }

    @Override
    protected ItemStack icon(MarketSummary s) {
        return menus.icons().ofItem(rt.templates().stack(s.template(), 1), 1, lines("entry",
                price("best-ask", s.bestAsk()), price("best-bid", s.bestBid()),
                Messages.text("ask-qty", s.askQuantity()), Messages.text("bid-qty", s.bidQuantity()),
                Messages.text("orders", s.orderCount())));
    }

    private TagResolver price(String name, Optional<Money> price) {
        return price.map(p -> money(name, p)).orElseGet(() -> Messages.component(name, text("none")));
    }

    @Override
    protected void pick(MarketSummary s, ClickType click) {
        menus.openBook(player, rt.templates().stack(s.template(), 1));
    }
}
