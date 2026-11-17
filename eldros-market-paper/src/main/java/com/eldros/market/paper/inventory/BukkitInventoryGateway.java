package com.eldros.market.paper.inventory;

import com.eldros.market.api.model.TemplateId;
import com.eldros.market.core.domain.PlayerId;
import com.eldros.market.core.port.InventoryGateway;
import com.eldros.market.paper.item.TemplateRegistry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Player inventory in units of an exact template. Main thread. Works on the 36 storage slots only, so delivered items
 * never land in armour or off-hand slots (docs/04 P-10). Matching is {@code isSimilar} against the template.
 */
public final class BukkitInventoryGateway implements InventoryGateway {

    private final TemplateRegistry templates;

    public BukkitInventoryGateway(TemplateRegistry templates) {
        this.templates = templates;
    }

    private static Player online(PlayerId id) {
        return Bukkit.getPlayer(id.value());
    }

    @Override
    public boolean isOnline(PlayerId player) {
        return online(player) != null;
    }

    @Override
    public boolean remove(PlayerId id, TemplateId template, long quantity) {
        Player player = online(id);
        if (player == null || !templates.has(template)) {
            return false;
        }
        ItemStack reference = templates.stack(template, 1);
        PlayerInventory inv = player.getInventory();
        ItemStack[] slots = inv.getStorageContents();
        long have = 0;
        for (ItemStack s : slots) {
            if (s != null && s.isSimilar(reference)) {
                have += s.getAmount();
            }
        }
        if (have < quantity) {
            return false;
        }
        long left = quantity;
        for (int i = 0; i < slots.length && left > 0; i++) {
            ItemStack s = slots[i];
            if (s != null && s.isSimilar(reference)) {
                int take = (int) Math.min(left, s.getAmount());
                left -= take;
                if (take == s.getAmount()) {
                    slots[i] = null;
                } else {
                    s.setAmount(s.getAmount() - take);
                }
            }
        }
        inv.setStorageContents(slots);
        return true;
    }

    @Override
    public long fit(PlayerId id, TemplateId template, long quantity) {
        Player player = online(id);
        if (player == null || !templates.has(template)) {
            return 0;
        }
        ItemStack reference = templates.stack(template, 1);
        int max = reference.getMaxStackSize();
        long free = 0;
        for (ItemStack s : player.getInventory().getStorageContents()) {
            if (s == null || s.getType().isAir()) {
                free += max;
            } else if (s.isSimilar(reference)) {
                free += Math.max(0, max - s.getAmount());
            }
            if (free >= quantity) {
                return quantity;
            }
        }
        return free;
    }

    @Override
    public void add(PlayerId id, TemplateId template, long quantity) {
        Player player = online(id);
        if (player == null) {
            throw new IllegalStateException("player went offline before delivery");
        }
        ItemStack reference = templates.stack(template, 1);
        int max = reference.getMaxStackSize();
        PlayerInventory inv = player.getInventory();
        ItemStack[] slots = inv.getStorageContents();
        long left = quantity;
        for (int i = 0; i < slots.length && left > 0; i++) { // top up existing stacks first
            ItemStack s = slots[i];
            if (s != null && s.isSimilar(reference) && s.getAmount() < max) {
                int put = (int) Math.min(left, max - s.getAmount());
                s.setAmount(s.getAmount() + put);
                left -= put;
            }
        }
        for (int i = 0; i < slots.length && left > 0; i++) {
            if (slots[i] == null || slots[i].getType().isAir()) {
                int put = (int) Math.min(left, max);
                slots[i] = templates.stack(template, put);
                left -= put;
            }
        }
        if (left > 0) {
            throw new IllegalStateException("inventory changed between fit and add");
        }
        inv.setStorageContents(slots);
    }
}
