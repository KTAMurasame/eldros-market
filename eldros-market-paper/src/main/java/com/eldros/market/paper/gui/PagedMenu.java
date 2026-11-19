package com.eldros.market.paper.gui;

import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * A screen that lists entries in the layout's content slots with previous/next buttons. Subclasses load the
 * entries (async) and call {@link #show}; until then a "loading" icon is drawn.
 */
public abstract class PagedMenu<T> extends Menu {

    protected int page;
    private List<T> entries;

    protected PagedMenu(Menus menus, Player player, String screen, int page) {
        super(menus, player, screen);
        this.page = Math.max(0, page);
    }

    protected final void show(List<T> loaded) {
        this.entries = loaded;
        int pages = Math.max(1, (loaded.size() + perPage() - 1) / perPage());
        page = Math.min(page, pages - 1);
        redraw();
    }

    protected final int perPage() {
        return Math.max(1, layout.contentSlots().size());
    }

    @Override
    protected final void draw() {
        drawControls();
        if (entries == null) {
            button("loading", null);
            return;
        }
        if (entries.isEmpty()) {
            button("empty", null);
            return;
        }
        List<Integer> slots = layout.contentSlots();
        int from = page * perPage();
        for (int i = 0; i < slots.size() && from + i < entries.size(); i++) {
            T entry = entries.get(from + i);
            set(slots.get(i), icon(entry), click -> pick(entry, click));
        }
        int pages = (entries.size() + perPage() - 1) / perPage();
        TagResolver pageTags = TagResolver.resolver(com.eldros.market.paper.i18n.Messages.text("page", page + 1),
                com.eldros.market.paper.i18n.Messages.text("pages", pages));
        if (page > 0) {
            button("previous", c -> {
                page--;
                redraw();
            }, pageTags);
        }
        if (page + 1 < pages) {
            button("next", c -> {
                page++;
                redraw();
            }, pageTags);
        }
    }

    // --- categories (browse and catalog) ------------------------------------------------------------

    /** "all", the configured categories in file order, then "other". */
    protected final List<String> categoryChoices() {
        List<String> ids = new java.util.ArrayList<>();
        ids.add(Categories.ALL);
        ids.addAll(menus.configs().categories().ids());
        ids.add(Categories.OTHER);
        return ids;
    }

    /** {@code wanted} if it still exists (a reload may have removed it), else "all". */
    protected final String knownCategory(String wanted) {
        return categoryChoices().contains(wanted) ? wanted : Categories.ALL;
    }

    protected final boolean inCategory(String category, org.bukkit.Material material) {
        return category.equals(Categories.ALL)
                || menus.configs().categories().categoryOf(material.getKey().toString()).equals(category);
    }

    /** The category button: left click next, right click previous; {@code change} gets the new id. */
    protected final void categoryButton(String current, java.util.function.Consumer<String> change) {
        List<String> ids = categoryChoices();
        Categories cats = menus.configs().categories();
        List<net.kyori.adventure.text.Component> names = new java.util.ArrayList<>();
        for (String id : ids) {
            names.add(switch (id) {
                case Categories.ALL -> text("category-all");
                case Categories.OTHER -> text("category-other");
                default -> net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(cats.name(id, lang));
            });
        }
        int at = Math.max(0, ids.indexOf(current));
        org.bukkit.Material icon = null;
        for (Categories.Category c : cats.all()) {
            if (c.id().equalsIgnoreCase(current)) {
                icon = org.bukkit.Material.matchMaterial(c.icon());
            }
        }
        choiceButton("category", icon != null && icon.isItem() ? icon : null, names, at, click -> {
            int n = ids.size();
            change.accept(ids.get(click.isRightClick() ? (at + n - 1) % n : (at + 1) % n));
        });
    }

    /** Buttons other than the list (back, filters, …). */
    protected abstract void drawControls();

    protected abstract ItemStack icon(T entry);

    protected abstract void pick(T entry, ClickType click);
}
