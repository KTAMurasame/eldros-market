package com.eldros.market.paper.gui.screen;

import com.eldros.market.paper.gui.Menu;
import com.eldros.market.paper.gui.Menus;
import com.eldros.market.paper.i18n.Messages;
import org.bukkit.entity.Player;

/** Shortcuts, mailbox counter and the safe-mode warning. */
public final class MainMenu extends Menu {

    private int mailboxCount = -1;

    public MainMenu(Menus menus, Player player) {
        super(menus, player, "main");
    }

    @Override
    protected void onOpen() {
        load(actions.mailbox(player), rows -> {
            mailboxCount = rows.size();
            redraw();
        });
    }

    @Override
    protected void draw() {
        button("browse", c -> menus.openBrowse(player, null, 0));
        button("create", c -> menus.openCatalog(player, null, 0));
        button("orders", c -> menus.openOrders(player, 0));
        button("history", c -> menus.openHistory(player, null, 0));
        button("mailbox", c -> menus.openMailbox(player, 0),
                Messages.text("count", mailboxCount < 0 ? "…" : String.valueOf(mailboxCount)));
        button("language", c -> menus.openLanguage(player), Messages.text("language", msg.raw(lang, "language-name")));
        button("close", c -> player.closeInventory());
        if (rt.safeMode().isActive()) {
            button("safe-mode", null);
        }
    }
}
