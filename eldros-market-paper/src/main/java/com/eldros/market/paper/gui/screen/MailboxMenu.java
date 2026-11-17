package com.eldros.market.paper.gui.screen;

import com.eldros.market.core.storage.jdbc.JdbcJournal.MailboxRow;
import com.eldros.market.paper.gui.Menus;
import com.eldros.market.paper.gui.PagedMenu;
import com.eldros.market.paper.i18n.Messages;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Claimable entries, then entries under admin review (docs/03 §7.3). Entries under review are shown but can
 * never be claimed here, so nothing can be paid twice.
 */
public final class MailboxMenu extends PagedMenu<MailboxRow> {

    private List<MailboxRow> available = List.of();
    private boolean busy;

    public MailboxMenu(Menus menus, Player player, int page) {
        super(menus, player, "mailbox", page);
    }

    @Override
    protected void onOpen() {
        load(actions.mailbox(player).thenCombine(actions.inReview(player), Loaded::new), loaded -> {
            available = loaded.available();
            List<MailboxRow> all = new ArrayList<>(loaded.available());
            all.addAll(loaded.review());
            show(all);
        });
    }

    private record Loaded(List<MailboxRow> available, List<MailboxRow> review) {
    }

    @Override
    protected void drawControls() {
        button("back", c -> menus.openMain(player));
        button("claim-all", c -> claim(available), Messages.text("count", available.size()));
    }

    private String reasonText(String reason) {
        String key = "mailbox.reasons." + reason;
        return msg.has(lang, key) ? msg.raw(lang, key) : reason;
    }

    @Override
    protected ItemStack icon(MailboxRow r) {
        boolean review = "QUARANTINED".equals(r.status());
        if ("MONEY".equals(r.kind())) {
            return menus.icons().of(layout.icon(review ? "review" : "money", review ? Material.CLOCK : Material.GOLD_INGOT),
                    text(review ? "review.name" : "money.name", money("amount", r.amount())),
                    lines(review ? "review.lore" : "money.lore", money("amount", r.amount()),
                            Messages.text("reason", reasonText(r.reason())), Messages.text("id", r.id())));
        }
        ItemStack base = rt.templates().has(r.template()) ? rt.templates().stack(r.template(), 1) : new ItemStack(Material.BARRIER);
        return menus.icons().ofItem(base, (int) Math.min(r.qty(), 64), lines(review ? "review.lore" : "item.lore",
                Messages.text("qty", r.qty()), Messages.text("reason", reasonText(r.reason())), Messages.text("id", r.id()),
                Messages.text("amount", "")));
    }

    @Override
    protected void pick(MailboxRow r, ClickType click) {
        if ("QUARANTINED".equals(r.status())) {
            msg.send(player, "reason.quarantined");
            return;
        }
        claim(List.of(r));
    }

    private void claim(List<MailboxRow> rows) {
        if (busy) {
            return;
        }
        busy = true;
        actions.claim(player, rows).thenAccept(ok -> {
            busy = false;
            if (isShown()) {
                menus.openMailbox(player, page);
            }
        });
    }
}
