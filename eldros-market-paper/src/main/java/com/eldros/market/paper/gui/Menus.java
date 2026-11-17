package com.eldros.market.paper.gui;

import com.eldros.market.api.model.OrderSide;
import com.eldros.market.core.domain.Money;
import com.eldros.market.paper.action.MarketActions;
import com.eldros.market.paper.gui.screen.BookMenu;
import com.eldros.market.paper.gui.screen.BrowseMenu;
import com.eldros.market.paper.gui.screen.CatalogMenu;
import com.eldros.market.paper.gui.screen.ConfirmMenu;
import com.eldros.market.paper.gui.screen.CreateOrderMenu;
import com.eldros.market.paper.gui.screen.HistoryMenu;
import com.eldros.market.paper.gui.screen.LanguageMenu;
import com.eldros.market.paper.gui.screen.MailboxMenu;
import com.eldros.market.paper.gui.screen.MainMenu;
import com.eldros.market.paper.gui.screen.MyOrdersMenu;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Entry points of every screen, plus the shared services the screens need. Main thread only. */
public final class Menus {

    /** An order being composed in the menus. {@code item} is the exact item (a template copy or a catalog stack). */
    public record Draft(ItemStack item, OrderSide side, long quantity, Money price) {

        public Draft withSide(OrderSide s) {
            return new Draft(item, s, quantity, price);
        }

        public Draft withQuantity(long q) {
            return new Draft(item, side, q, price);
        }

        public Draft withPrice(Money p) {
            return new Draft(item, side, quantity, p);
        }
    }

    private final MarketActions actions;
    private final MenuConfigs configs;
    private final Icons icons;
    private final ChatPrompt prompt;

    public Menus(MarketActions actions, MenuConfigs configs, Icons icons, ChatPrompt prompt) {
        this.actions = actions;
        this.configs = configs;
        this.icons = icons;
        this.prompt = prompt;
    }

    public MarketActions actions() {
        return actions;
    }

    public MenuConfigs configs() {
        return configs;
    }

    public Icons icons() {
        return icons;
    }

    public ChatPrompt prompt() {
        return prompt;
    }

    /** The main menu; refuses (with a message) while the market is still loading. Safe mode still opens it read-only. */
    public void openMain(Player p) {
        if (!actions.runtime().isLoaded()) {
            actions.runtime().messages().send(p, "general.loading");
            return;
        }
        if (!p.hasPermission("eldrosmarket.use")) {
            actions.runtime().messages().send(p, "general.no-permission");
            return;
        }
        new MainMenu(this, p).open();
    }

    public void openBrowse(Player p, String search, int page) {
        new BrowseMenu(this, p, search, page).open();
    }

    public void openCatalog(Player p, String search, int page) {
        new CatalogMenu(this, p, search, page).open();
    }

    public void openBook(Player p, ItemStack item) {
        new BookMenu(this, p, item).open();
    }

    public void openCreate(Player p, Draft draft) {
        new CreateOrderMenu(this, p, draft).open();
    }

    public void openConfirm(Player p, Draft draft) {
        new ConfirmMenu(this, p, draft).open();
    }

    public void openOrders(Player p, int page) {
        new MyOrdersMenu(this, p, page).open();
    }

    public void openHistory(Player p, OrderSide filter, int page) {
        new HistoryMenu(this, p, filter, page).open();
    }

    public void openMailbox(Player p, int page) {
        new MailboxMenu(this, p, page).open();
    }

    public void openLanguage(Player p) {
        new LanguageMenu(this, p).open();
    }
}
