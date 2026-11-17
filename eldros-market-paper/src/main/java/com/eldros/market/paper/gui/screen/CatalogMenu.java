package com.eldros.market.paper.gui.screen;

import com.eldros.market.paper.gui.Menus;
import com.eldros.market.paper.gui.PagedMenu;
import com.eldros.market.paper.i18n.Messages;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Vanilla item catalog (docs/03 §8.4): every item type in its default form, minus air, blacklisted items and items
 * whose default form is meaningless without data (potions, enchanted books, heads, …). Those are traded by holding
 * the real item ("held item" button).
 */
public final class CatalogMenu extends PagedMenu<Material> {

    /** Item ids whose plain default stack is not what players trade. Compared by key so it works on every version. */
    private static final Set<String> NEEDS_DATA = Set.of(
            "potion", "splash_potion", "lingering_potion", "tipped_arrow", "enchanted_book", "firework_rocket",
            "firework_star", "player_head", "written_book", "filled_map", "suspicious_stew", "goat_horn",
            "ominous_bottle", "bundle", "writable_book", "knowledge_book", "debug_stick");

    private static volatile List<Material> catalog;

    private final String search;

    public CatalogMenu(Menus menus, Player player, String search, int page) {
        super(menus, player, "catalog", page);
        this.search = search == null || search.isBlank() ? null : search.toLowerCase(Locale.ROOT);
    }

    @Override
    protected TagResolver[] titleResolvers() {
        return new TagResolver[] {Messages.text("search", search == null ? "" : search)};
    }

    private List<Material> catalog() {
        List<Material> c = catalog;
        if (c == null) {
            c = Arrays.stream(Material.values())
                    .filter(m -> !m.isLegacy() && m.isItem() && !m.isAir())
                    .filter(m -> !NEEDS_DATA.contains(m.getKey().getKey()))
                    .toList();
            catalog = c;
        }
        return c;
    }

    @Override
    protected void onOpen() {
        show(catalog().stream()
                .filter(m -> rt.filter().refusal(new ItemStack(m)).isEmpty())
                .filter(m -> search == null || m.getKey().getKey().replace('_', ' ').contains(search.replace('_', ' ')))
                .toList());
    }

    @Override
    protected void drawControls() {
        button("back", c -> menus.openMain(player));
        button("search", c -> {
            player.closeInventory();
            msg.send(player, "menu.prompt.search");
            menus.prompt().ask(player, text -> menus.openCatalog(player, text, 0), () -> menus.openCatalog(player, search, page));
        }, Messages.text("search", search == null ? "-" : search));
        if (search != null) {
            button("clear-search", c -> menus.openCatalog(player, null, 0));
        }
        button("held-item", c -> {
            ItemStack hand = player.getInventory().getItemInMainHand();
            Optional<String> refusal = rt.filter().refusal(hand);
            if (refusal.isPresent()) {
                msg.send(player, refusal.get());
                return;
            }
            ItemStack one = hand.clone();
            one.setAmount(1);
            menus.openBook(player, one);
        });
    }

    @Override
    protected ItemStack icon(Material m) {
        return menus.icons().ofItem(new ItemStack(m), 1, lines("entry"));
    }

    @Override
    protected void pick(Material m, ClickType click) {
        menus.openBook(player, new ItemStack(m));
    }
}
