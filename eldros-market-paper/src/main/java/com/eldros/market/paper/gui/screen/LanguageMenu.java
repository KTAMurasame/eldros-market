package com.eldros.market.paper.gui.screen;

import com.eldros.market.paper.gui.Menus;
import com.eldros.market.paper.gui.PagedMenu;
import com.eldros.market.paper.i18n.Messages;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;

/** Every language file in lang/; the current one is marked. Picking one reopens the main menu in that language. */
public final class LanguageMenu extends PagedMenu<String> {

    public LanguageMenu(Menus menus, Player player) {
        super(menus, player, "language", 0);
    }

    @Override
    protected void onOpen() {
        show(new ArrayList<>(msg.available()));
    }

    @Override
    protected void drawControls() {
        button("back", c -> menus.openMain(player));
    }

    @Override
    protected ItemStack icon(String code) {
        boolean current = code.equals(lang);
        Material material = layout.icon(current ? "current" : "other", Material.PAPER);
        return menus.icons().of(material, msg.render(code, "language-name"),
                lines(current ? "current.lore" : "other.lore", Messages.text("code", code)));
    }

    @Override
    protected void pick(String code, ClickType click) {
        if (actions.setLanguage(player, code)) {
            menus.openMain(player);
        }
    }
}
