package com.eldros.market.paper.integration;

import com.eldros.market.core.domain.Money;
import com.eldros.market.core.domain.MoneyScale;
import com.eldros.market.core.service.PriceRules;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves {@code %eldrosmarket_<params>%} from an immutable snapshot, so a placeholder request (main thread, or a
 * scoreboard plugin's own thread) never waits for the database. Pure Java: no Bukkit types.
 *
 * <ul>
 *   <li>{@code best_buy_<item>}, {@code best_sell_<item>}: highest buy order / lowest sell order, in the player's
 *       number format; {@code ..._raw} as a plain decimal ("1234.50")</li>
 *   <li>{@code demand_<item>}, {@code supply_<item>}: items wanted by buy orders / offered by sell orders</li>
 *   <li>{@code mailbox}, {@code orders}, {@code orders_max}: the viewing player's counts</li>
 *   <li>{@code frozen}: {@code true} while an admin has frozen trading</li>
 * </ul>
 * {@code <item>} is a plain (no custom name, enchantment or other data) item id such as {@code diamond} or
 * {@code minecraft:diamond}. A missing value gives the configured "none" text; an unknown placeholder gives null.
 */
public final class PlaceholderValues {

    public record ItemQuote(Optional<Money> bestBuy, Optional<Money> bestSell, long demand, long supply) {
    }

    public record PlayerStats(int mailbox, int orders, int ordersMax) {
    }

    public record Snapshot(Map<String, ItemQuote> items, Map<UUID, PlayerStats> players, boolean frozen) {
        public static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of(), false);
    }

    /** Money in the viewing player's language format. */
    @FunctionalInterface
    public interface Format {
        String money(Money money);
    }

    private PlaceholderValues() {
    }

    public static String resolve(Snapshot s, UUID player, String params, Format format, MoneyScale scale, String none) {
        String p = params == null ? "" : params.toLowerCase(Locale.ROOT).trim();
        switch (p) {
            case "frozen":
                return Boolean.toString(s.frozen());
            case "mailbox", "orders", "orders_max": {
                PlayerStats st = player == null ? null : s.players().get(player);
                if (st == null) {
                    return none;
                }
                return Integer.toString(switch (p) {
                    case "mailbox" -> st.mailbox();
                    case "orders" -> st.orders();
                    default -> st.ordersMax();
                });
            }
            default:
                break;
        }
        boolean raw = p.endsWith("_raw");
        String body = raw ? p.substring(0, p.length() - "_raw".length()) : p;
        if (body.startsWith("best_buy_") || body.startsWith("best_sell_")) {
            boolean buy = body.startsWith("best_buy_");
            String item = body.substring(buy ? "best_buy_".length() : "best_sell_".length());
            if (item.isEmpty()) {
                return null;
            }
            ItemQuote q = s.items().get(PriceRules.normalise(item));
            Optional<Money> price = q == null ? Optional.empty() : buy ? q.bestBuy() : q.bestSell();
            if (price.isEmpty()) {
                return none;
            }
            return raw ? BigDecimal.valueOf(price.get().minor(), scale.decimalPlaces()).toPlainString() : format.money(price.get());
        }
        if (!raw && (p.startsWith("demand_") || p.startsWith("supply_"))) {
            boolean demand = p.startsWith("demand_");
            String item = p.substring("demand_".length()); // same length as "supply_"
            if (item.isEmpty()) {
                return null;
            }
            ItemQuote q = s.items().get(PriceRules.normalise(item));
            return Long.toString(q == null ? 0 : demand ? q.demand() : q.supply());
        }
        return null;
    }
}
