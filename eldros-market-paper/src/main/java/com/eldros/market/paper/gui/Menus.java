package com.eldros.market.paper.gui;

import com.eldros.market.api.model.OrderSide;
import com.eldros.market.core.domain.Money;
import com.eldros.market.paper.action.MarketActions;
import com.eldros.market.paper.gui.screen.BookMenu;
import com.eldros.market.paper.gui.screen.BrowseMenu;
import com.eldros.market.paper.gui.screen.CatalogMenu;
import com.eldros.market.paper.gui.screen.ConfirmMenu;
import com.eldros.market.paper.gui.screen.CreateOrderMenu;
import com.eldros.market.paper.gui.screen.EditOrderMenu;
import com.eldros.market.paper.gui.screen.HistoryMenu;
import com.eldros.market.paper.gui.screen.InstantMenu;
import com.eldros.market.paper.gui.screen.LanguageMenu;
import com.eldros.market.paper.gui.screen.MailboxMenu;
import com.eldros.market.paper.gui.screen.MainMenu;
import com.eldros.market.paper.gui.screen.MyOrdersMenu;
import com.eldros.market.paper.gui.screen.SellAllMenu;
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

    /** What a player last chose in a list screen; kept until they leave. */
    public record View(String category, MarketSort sort) {
        public static final View DEFAULT = new View(Categories.ALL, MarketSort.ACTIVE);
    }

    private final java.util.Map<java.util.UUID, View> browseViews = new java.util.HashMap<>();
    private final java.util.Map<java.util.UUID, View> catalogViews = new java.util.HashMap<>();

    public View browseView(Player p) {
        return browseViews.getOrDefault(p.getUniqueId(), View.DEFAULT);
    }

    public void browseView(Player p, View v) {
        browseViews.put(p.getUniqueId(), v);
    }

    public View catalogView(Player p) {
        return catalogViews.getOrDefault(p.getUniqueId(), View.DEFAULT);
    }

    public void catalogView(Player p, View v) {
        catalogViews.put(p.getUniqueId(), v);
    }

    /** Drops a leaving player's view state. */
    public org.bukkit.event.Listener quitListener() {
        return new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
            public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
                browseViews.remove(e.getPlayer().getUniqueId());
                catalogViews.remove(e.getPlayer().getUniqueId());
            }
        };
    }

    private final MarketActions actions;
    private final MenuConfigs configs;
    private final Icons icons;
    private final ChatPrompt prompt;
    private final AnvilPrompt anvil;

    public Menus(MarketActions actions, MenuConfigs configs, Icons icons, ChatPrompt prompt, AnvilPrompt anvil) {
        this.actions = actions;
        this.configs = configs;
        this.icons = icons;
        this.prompt = prompt;
        this.anvil = anvil;
    }

    /**
     * Asks the player for a value: in an anvil window whose text field starts with {@code initial} (default), or in
     * chat when {@code input.method} is chat or the server cannot open anvil windows. {@code promptKey} is the chat
     * prompt (menu.prompt.x); the anvil title is menu.input.x.
     */
    public void input(Player p, String promptKey, String initial, java.util.function.Consumer<String> onAnswer, Runnable onCancel) {
        var rt = actions.runtime();
        var msg = rt.messages();
        if (rt.settings().anvilInput()) {
            String lang = msg.languageOf(p);
            String kind = promptKey.substring(promptKey.lastIndexOf('.') + 1);
            if (anvil.ask(p, msg.render(lang, "menu.input." + kind), initial, msg.renderList(lang, "menu.input.hint"),
                    msg.render(lang, "menu.input.confirm"), onAnswer, onCancel)) {
                return;
            }
        }
        p.closeInventory();
        msg.send(p, promptKey);
        prompt.ask(p, onAnswer, onCancel);
    }

    /** A money amount as typed (no grouping), e.g. 1234.50. */
    public String plain(Money m) {
        return java.math.BigDecimal.valueOf(m.minor(), actions.runtime().settings().scale().decimalPlaces()).toPlainString();
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
        if (!actions.allowedHere(p)) {
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

    /** Confirmation of an instant trade of up to {@code wanted} items against the best orders. */
    public void openInstant(Player p, ItemStack item, com.eldros.market.api.model.TemplateId template, OrderSide side, long wanted) {
        new InstantMenu(this, p, item, template, side, wanted).open();
    }

    public void openEdit(Player p, com.eldros.market.core.storage.jdbc.JdbcLedger.OrderRow order) {
        new EditOrderMenu(this, p, order, order.unitPrice(), 0).open();
    }

    public void openSellAll(Player p) {
        new SellAllMenu(this, p).open();
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
