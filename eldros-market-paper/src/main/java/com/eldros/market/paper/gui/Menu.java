package com.eldros.market.paper.gui;

import com.eldros.market.paper.MarketRuntime;
import com.eldros.market.paper.action.MarketActions;
import com.eldros.market.paper.i18n.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * One open screen for one player. Subclasses draw into slots and register click handlers. Data is loaded
 * asynchronously; a result is drawn only if this menu is still the one the player looks at ({@link #isShown()}),
 * which is the session-token rule of docs/03 §11.
 */
public abstract class Menu {

    protected final Menus menus;
    protected final MarketActions actions;
    protected final MarketRuntime rt;
    protected final Messages msg;
    protected final Player player;
    protected final String lang;
    protected final MenuLayout layout;

    private final MenuHolder holder = new MenuHolder(this);
    private final Map<Integer, Consumer<ClickType>> handlers = new HashMap<>();
    private Inventory inventory;

    protected Menu(Menus menus, Player player, String screen) {
        this.menus = menus;
        this.actions = menus.actions();
        this.rt = actions.runtime();
        this.msg = rt.messages();
        this.player = player;
        this.lang = msg.languageOf(player);
        this.layout = menus.configs().layout(screen);
    }

    /** Title placeholders; override to fill e.g. {@code <item>} or {@code <page>}. */
    protected TagResolver[] titleResolvers() {
        return new TagResolver[0];
    }

    public final void open() {
        inventory = Bukkit.createInventory(holder, layout.size(), msg.render(lang, "menu." + layout.screen() + ".title", titleResolvers()));
        holder.bind(inventory);
        redraw();
        player.openInventory(inventory);
        onOpen();
    }

    /** Called after the inventory is shown; start async loads here. */
    protected void onOpen() {
    }

    /** Clears and draws everything again. */
    protected final void redraw() {
        handlers.clear();
        inventory.clear();
        if (layout.filler() != null) {
            ItemStack fill = menus.icons().of(layout.filler(), Component.space(), List.of());
            for (int i = 0; i < inventory.getSize(); i++) {
                inventory.setItem(i, fill);
            }
        }
        draw();
    }

    protected abstract void draw();

    public final boolean isShown() {
        return player.isOnline() && player.getOpenInventory().getTopInventory().getHolder() == holder;
    }

    final void click(int slot, ClickType type) {
        Consumer<ClickType> h = handlers.get(slot);
        if (h != null) {
            h.accept(type);
        }
    }

    // --- drawing helpers ---------------------------------------------------------------------------------

    protected final void set(int slot, ItemStack icon, Consumer<ClickType> onClick) {
        if (slot < 0 || slot >= inventory.getSize()) {
            return;
        }
        inventory.setItem(slot, icon);
        if (onClick != null) {
            handlers.put(slot, onClick);
        } else {
            handlers.remove(slot);
        }
    }

    /** Draws the layout button {@code id} with texts from {@code menu.<screen>.<id>} (or {@code menu.common.<id>}). */
    protected final void button(String id, Consumer<ClickType> onClick, TagResolver... resolvers) {
        button(id, null, onClick, resolvers);
    }

    protected final void button(String id, Material override, Consumer<ClickType> onClick, TagResolver... resolvers) {
        layout.button(id).ifPresent(b -> set(b.slot(), menus.icons().of(override != null ? override : b.material(),
                text(id + ".name", resolvers), lines(id + ".lore", resolvers)), onClick));
    }

    /**
     * A button that cycles through {@code options} (left click: next, right click: previous). Its lore lists every
     * option with the selected one marked, under the button's own {@code lore} lines.
     */
    protected final void choiceButton(String id, Material override, List<Component> options, int selected,
                                      Consumer<ClickType> onClick) {
        layout.button(id).ifPresent(b -> {
            Component current = options.get(selected);
            TagResolver cur = com.eldros.market.paper.i18n.Messages.component("current", current);
            List<Component> lore = new java.util.ArrayList<>(lines(id + ".lore", cur));
            for (int i = 0; i < options.size(); i++) {
                TagResolver n = com.eldros.market.paper.i18n.Messages.component("name", options.get(i));
                lore.add(text(i == selected ? "option-selected" : "option", n));
            }
            lore.addAll(lines("cycle-hint"));
            set(b.slot(), menus.icons().of(override != null ? override : b.material(), text(id + ".name", cur), lore), onClick);
        });
    }

    /** Text of {@code menu.<screen>.<key>}, falling back to {@code menu.common.<key>}. */
    protected final Component text(String key, TagResolver... resolvers) {
        return msg.render(lang, resolveKey(key), resolvers);
    }

    protected final List<Component> lines(String key, TagResolver... resolvers) {
        return msg.renderList(lang, resolveKey(key), resolvers);
    }

    private String resolveKey(String key) {
        String own = "menu." + layout.screen() + "." + key;
        if (msg.has(lang, own) || !msg.rawList(lang, own).equals(List.of(own))) {
            return own;
        }
        return "menu.common." + key;
    }

    /** Runs {@code then} on the main thread with the result, only if this menu is still shown. */
    protected final <T> void load(CompletableFuture<T> future, Consumer<T> then) {
        future.whenComplete((value, error) -> {
            if (error != null) {
                actions.fail(player, error);
                return;
            }
            if (isShown()) {
                then.accept(value);
            }
        });
    }

    protected final TagResolver money(String name, com.eldros.market.core.domain.Money amount) {
        return msg.money(lang, name, amount, rt.settings().scale());
    }
}
