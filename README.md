# Eldros Market

A player-driven market for Paper servers: **buy orders and sell orders on an order book**, matched by price and time,
with escrow, a mailbox, full history and crash recovery that never pays anything twice.

> Türkçe: [docs/README.tr.md](docs/README.tr.md)

## Why another market plugin

- **Two-sided order book.** Players post sell offers *and* buy requests. A new order trades against the best opposite
  orders at the resting price; whatever is left waits in the book. Partial fills, price improvement refunds and
  cancellation with full refund are built in.
- **Nothing is ever given twice.** Every money or item movement is written to a journal *before* it happens. After a
  crash or `kill -9` the plugin recovers on its own where the outcome is certain, and puts the operation in
  quarantine for an admin where it is not (it never guesses with money). Tested on real servers in 24 crash runs,
  see [docs/06-qa-report.md](docs/06-qa-report.md).
- **Your own orders never match each other** (self-trade prevention), without losing their place in the queue.
- **Exact items.** Enchanted, renamed or otherwise special items are their own market; only identical items match.
- **Menus and commands**, English and Turkish, per-player language, every menu layout configurable.
- **Trade now or wait.** Buy now / Sell now in the order book, `/market sell` (the stack in hand) and `/market sell all`,
  each with a confirmation. Open orders can be re-priced or topped up.
- **Easy to use.** Categories and sorting in the market list, prices and quantities typed in an anvil window, `2.5k`
  style amounts, merged notifications and a "while you were away" summary.
- **Admin controls.** Sales tax and buy-order fee (off by default), per-item price ranges, closed worlds and game
  modes, a trading freeze for maintenance, PlaceholderAPI placeholders.
- **`/marketadmin verify`** checks the ledger invariants on your live data whenever you want.

## Requirements and tested setups

| | Requirement |
|---|---|
| Server | Paper **1.21.4 or newer** (one jar for 1.21.x and 26.x) |
| Java | 21 or newer |
| Economy | [Vault](https://www.spigotmc.org/resources/vault.34315/) or VaultUnlocked, plus an economy plugin |
| Storage | SQLite, built in (uses the server's SQLite driver, minimum 3.47) |

Only what was actually run is listed as tested:

| Paper | Java | Bridge + economy | Status |
|---|---|---|---|
| 1.21.4 (build 232) | 21 and 25 | VaultUnlocked 2.20.3 + EssentialsX 2.22.0 | **Tested**: real client and bot players, every feature, 20 crash runs (crash tests on Java 25); PlaceholderAPI 2.12.3 |
| 26.3 (build 253) | 25 | VaultUnlocked 2.20.3 + EssentialsX 2.22.0 | **Tested**: real client: menus, anvil input, sell order, cancel, mailbox; upgrade of an older database. 4 item crash runs on an earlier build |
| 1.21.11 (build 132) | 25 | Vault 1.7.3 + EssentialsX 2.22.0 | **Tested** with bot players: orders and matching, categories, sorting, anvil search, mailbox, freeze; upgrade of an older database |
| Anything else | | | Not tested yet |

The API calls in the jar were checked against 1.21.4, 1.21.11 and 26.3: none missing.

## Installation

1. Put `EldrosMarket-<version>.jar` in `plugins/` next to Vault (or VaultUnlocked) and your economy plugin.
2. Start the server once. `plugins/EldrosMarket/` gets `config.yml`, `lang/` and `menus/`.
3. **Set `economy.decimal-places` before players trade** (2 = cents, 0 = whole coins). It is locked once the
   database has data, because every stored price depends on it.
4. `/marketadmin reload` after editing anything else.

## Commands

Every player subcommand has an English and a Turkish name; both work for everyone.

| Command | What it does |
|---|---|
| `/market` | Opens the menu |
| `/market sell <qty> <price>` · `sat` | Sell order for the item in your main hand (items leave your inventory now) |
| `/market sell` · `sat` | Sell the stack in your hand right now to the best buy orders (confirm screen first) |
| `/market sell all` · `sat hepsi` | Sell everything in your inventory that has buyers (confirm screen first) |
| `/market buy <qty> <price> [item]` · `al` | Buy order for the held item or a named one, e.g. `diamond` (money is reserved now) |
| `/market orders` · `emirler` | Your active orders |
| `/market cancel <id>` · `iptal` | Cancel an order; what is left goes to your mailbox |
| `/market mailbox` · `posta` | What is waiting for you |
| `/market claim [all\|id]` · `topla [hepsi\|no]` | Collect from the mailbox |
| `/market lang <en_US\|tr_TR>` · `dil` | Change your language |
| `/market alerts` · `bildirim` | Mute or unmute live market messages (the mailbox is not affected) |
| `/market help` · `yardım` | Command list |

Aliases: `/pazar`, `/em`. Prices are per item; `12.50` uses a dot as decimal separator.

| Admin command (`/pazaradmin`) | What it does |
|---|---|
| `/marketadmin reload` | Reloads config, languages and menus |
| `/marketadmin verify` | Checks the ledger invariants (escrow = open orders, no item counted twice, …) |
| `/marketadmin safemode [on\|off]` | Shows or switches safe mode (no new orders or claims, everything stays safe) |
| `/marketadmin freeze [on\|off]` | Freezes trading for maintenance: no new orders, instant trades or edits; cancelling and the mailbox still work. Stays on across restarts |
| `/marketadmin recovery list` | Operations waiting for an admin decision |
| `/marketadmin recovery resolve <op> <applied\|not-applied> [qty]` | Decides one of them (see below) |

## Permissions

| Permission | Default | Meaning |
|---|---|---|
| `eldrosmarket.use` | everyone | Open the market and browse |
| `eldrosmarket.buy` | everyone | Create buy orders |
| `eldrosmarket.sell` | everyone | Create sell orders |
| `eldrosmarket.limit.orders.<n>` | — | Up to `n` active orders (highest wins; otherwise `limits.default-max-active-orders`) |
| `eldrosmarket.tax.<tier>` | — | Sales tax rate of `economy.sales-tax.tiers.<tier>` (lowest wins; must be granted explicitly) |
| `eldrosmarket.fee.exempt` | nobody | No buy-order fee (not even ops unless granted) |
| `eldrosmarket.bypass.restrictions` | nobody | Use the market in disabled worlds and game modes |
| `eldrosmarket.admin` | op | All admin commands |

## Configuration

`config.yml` (all keys are commented in the file):

| Key | Default | |
|---|---|---|
| `language.default` | `en_US` | Language for players who have not chosen one |
| `language.use-client-locale` | `true` | Use the client language if a file exists for it |
| `economy.decimal-places` | `2` | Fixed once the database has data |
| `limits.min-unit-price` / `max-unit-price` | `0.01` / `1000000` | Price per item |
| `limits.max-quantity-per-order` | `2304` | Items per order |
| `limits.default-max-active-orders` | `10` | Per player |
| `limits.per-item` | `{}` | Own min/max price per item, e.g. `minecraft:diamond: {min: "50", max: "5000"}` |
| `economy.sales-tax.percent` / `tiers` | `"0"` / `{}` | Kept from every sale; the rate is fixed when the sell order is placed |
| `economy.buy-order-fee.flat` / `percent` / `max` | `"0"` | Fee for placing a buy order, never refunded (`max` `"0"` = no cap) |
| `restrictions.disabled-worlds` | `[]` | World folder names where the market is closed |
| `restrictions.disabled-gamemodes` | `[creative, spectator]` | Game modes where the market is closed |
| `orders.expiry.enabled` / `days` | `false` / `14` | Expired orders return their rest to the mailbox |
| `items.blacklist` | command blocks, barrier, bedrock, … | Never tradable |
| `items.reject-filled-containers` | `true` | Shulker boxes / bundles with content are refused |
| `items.blocked-pdc-namespaces` | `[]` | Refuse items carrying data of these plugins (e.g. backpacks) |
| `performance.item-ops-per-second` | `10` | Item operations per second server-wide (each saves player data once) |
| `input.method` | `anvil` | How menus ask for prices, quantities and searches: `anvil` window or `chat` |
| `mailbox.auto-collect-money` | `false` | Money received while online goes straight to the balance (same crash-safe claim as `/market claim`; items stay in the mailbox) |
| `notifications.sound` / `join-summary-max-lines` | orb pickup / `8` | Sound for market messages (`""` = silent); length of the join summary |
| `placeholders.refresh-seconds` / `none` | `5` / `"-"` | How often placeholder values refresh; text when there is no value |

**Languages:** `lang/en_US.yml` and `lang/tr_TR.yml` (MiniMessage). Copy one to add a language; missing keys fall back
to English, and new keys are added to your files automatically on update. Item names from players are never parsed
as formatting. Vanilla item names are shown in each player's own Minecraft language (the client translates them);
menu search takes English item ids (`diamond sword`) and, through `search-aliases` in the language files, local words: the Turkish file maps about 200 words (`elmas kılıç`, `açık mavi yün`, also without Turkish letters: `kilic`).

**Categories:** `categories.yml` groups items for the market list and the catalog (ores and minerals, farming,
mob drops, tools, combat, redstone, decoration, nature, building blocks; the rest is "Other"). Each category lists
item id patterns (`*_ore`); the first matching category wins, so the order in the file matters. Names per language
file. The market list can also be sorted: most orders, name, most wanted, most offered, cheapest, best paying.

**Menus:** `menus/<screen>.yml` sets rows, filler, slots and icon materials for the ten screens (main, browse,
catalog, book, create, confirm, orders, history, mailbox, language). Texts are in the language files under `menu.`.
`slot: -1` hides a button. A wrong value is reported in the console with its file and key, and the bundled default is
used for that key only.

## Placeholders

With [PlaceholderAPI](https://hangar.papermc.io/HelpChat/PlaceholderAPI) installed (optional; tested with 2.12.3 on
Paper 1.21.4), these work in scoreboards, tab lists, holograms and so on. Values are refreshed in the background every
`placeholders.refresh-seconds`, so a placeholder never waits for the database.

| Placeholder | Value |
|---|---|
| `%eldrosmarket_best_buy_<item>%` | Highest buy order price, in the player's number format (`1,234.50` / `1.234,50`) |
| `%eldrosmarket_best_sell_<item>%` | Lowest sell order price |
| `%eldrosmarket_best_buy_<item>_raw%`, `%eldrosmarket_best_sell_<item>_raw%` | The same as a plain number (`1234.50`) |
| `%eldrosmarket_demand_<item>%` / `%eldrosmarket_supply_<item>%` | Items wanted by buy orders / offered by sell orders |
| `%eldrosmarket_mailbox%` | Entries waiting in the player's mailbox |
| `%eldrosmarket_orders%` / `%eldrosmarket_orders_max%` | The player's active orders / their limit |
| `%eldrosmarket_frozen%` | `true` while trading is frozen |

`<item>` is an item id such as `diamond` or `minecraft:diamond` and means the plain item (no custom name,
enchantments or other data). With no value (no orders, player offline) the `placeholders.none` text is shown.

## When the server crashes: what admins need to know

Most interrupted operations finish or roll back by themselves at the next start or when the player joins. An
operation goes to **quarantine** only when the plugin cannot know whether the economy plugin or the player's
inventory already changed. Players see such mailbox entries as "under review"; they cannot be claimed.

1. `/marketadmin recovery list` shows the operation, player and amount.
2. **Check the real state yourself** (the player's balance, e.g. `/bal <player>`, or their inventory).
3. Decide:

| Operation | `applied` means | `not-applied` means |
|---|---|---|
| `BUY_CREATE` | The money **was** taken → it is refunded to the mailbox | The money was not taken → nothing to do |
| `CLAIM_MONEY` | The money **was** paid out → entry closed | Not paid → the entry becomes claimable again |
| `SELL_CREATE` | Items **did** leave the inventory → `qty` items refunded to the mailbox | Items are still there → nothing to do |
| `CLAIM_ITEM` | `qty` items **were** delivered → entry closed for that amount | Not delivered → the entry becomes claimable again |

A decision is final; resolving the same operation twice is refused.

**Economy plugins and crashes.** Eldros keeps its own records crash-safe, but it cannot make your economy plugin
write its balances to disk. In our tests EssentialsX 2.22.0 lost a balance change made a moment before a hard crash;
Eldros put those operations in quarantine, and the correct decision was `not-applied`. This is why step 2 matters.

## Not supported

- Restoring player data from a backup, or inventory sync plugins: the plugin cannot see such changes.
- MySQL, BungeeCord/Velocity networks, Folia, Spigot.

## How it was made

Most of the code, tests and documentation were written with an AI assistant (Claude), directed by the author. Every
feature was tested on real Paper servers as described in the [QA report](docs/06-qa-report.md).

## Support

Bugs and questions: [GitHub Issues](https://github.com/KTAMurasame/eldros-market/issues). Please include your Paper
version, the plugin version, your economy plugin and the console log (`logs/latest.log`). If a crash was involved,
also add the output of `/marketadmin verify` and `/marketadmin recovery list`.

## Building

```
mvn clean verify
```

JDK 25 to build (the jar targets Java 21). `mvn -Pcrashtest clean package` builds a **test-only** jar with crash
points and `/eldrosdebug`; never run that jar on a live server.

## License

Copyright © 2026 Eldros. Licensed under the [GNU General Public License v3.0](LICENSE) (GPL-3.0-only).
External contributions are not accepted for now.
