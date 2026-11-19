# Changelog

All notable changes to Eldros Market. Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [0.1.0] - 2026-11-19

First public build (Free edition).

### Added
- Order book with buy and sell orders, price-time priority, trades at the resting price, partial fills,
  price-improvement refunds and cancel with full refund. Self-trade prevention that keeps queue priority.
- Escrow for both sides, mailbox for everything a player receives, trade history.
- Crash safety: journal before every money/item movement, effect log with checksum and fsync, player-data markers
  for items, startup and join recovery, quarantine with admin resolution, safe mode on storage failure.
- Menus: main, browse (with search), catalog, order book, create order (typed quantity and price), confirm,
  my orders, history (with filter), mailbox, language. Layouts in `menus/*.yml`, texts in the language files.
- Commands `/market` (`/pazar`, `/em`) and `/marketadmin` (`/pazaradmin`) with English and Turkish subcommands.
- English and Turkish, per-player language, client-locale detection, new keys merged into existing files.
- Menu search by words; language files can map local words to item ids (`search-aliases`, about 200 Turkish words).
  Turkish letters are optional when searching.
- Item filter: blacklist, filled shulker boxes/bundles, blocked PDC namespaces. Per-player order limit permission.
- Optional order expiry (off by default).
- Typed values in an anvil window (`input.method: anvil`, the default): the text field starts with the current
  price, quantity or search, the player edits it and clicks the result; Esc cancels. The anvil is virtual and its
  paper can never be taken. `input.method: chat` keeps the chat prompt, which is also used automatically if a server
  cannot open anvil windows. (Dialog windows need Minecraft 1.21.6+, so they are not used.)
- Updating keeps language files current: a message still exactly as an older version shipped it is replaced by the
  new default (`lang/retired.yml` lists the old texts); messages you changed are kept. New keys are added as before.
- The +/- price buttons say why they stop at the price limit instead of changing the price silently.
- Optional automatic money collection (`mailbox.auto-collect-money`, off by default): money from sales and refunds
  goes to an online player's balance within a few seconds, and on join after the "while you were away" summary.
  Every deposit is a normal mailbox claim (journal, effect log, recovery), so a crash mid-deposit is handled exactly
  like a manual claim (crash-tested in game at the deposit window). Items stay in the mailbox; players in a disabled
  world or game mode collect later; a failed deposit waits a minute before the next try.
- Categories and sorting in the market list (category and sort buttons; left click next, right click previous) and
  categories in the catalog. Categories come from the new `categories.yml` (item id patterns, first match wins, names
  per language); sorts: most orders, name, most wanted, most offered, cheapest, best paying. The choice is kept while
  the player is online. Existing `menus/*.yml` files get the new buttons from the bundled defaults.
- PlaceholderAPI placeholders (optional, `softdepend`): best buy/sell price per item (formatted and `_raw`), demand and
  supply, the player's mailbox entries and active orders/limit, trading freeze. Refreshed in the background
  (`placeholders.refresh-seconds`), so a request never waits for the database. Survives `/papi reload`.
- Per-item price range (`limits.per-item`, e.g. diamonds 50–5000): replaces the global min/max for that item in
  orders, edits, instant trades and menus; messages show the item's own limit. Inconsistent rules stop startup naming
  the item. Orders already in the book are not changed.
- Market closed in chosen worlds (`restrictions.disabled-worlds`) and game modes (`restrictions.disabled-gamemodes`,
  **creative and spectator by default**, since creative players can create items). `eldrosmarket.bypass.restrictions`
  (not given to ops) skips it.
- Trading freeze for maintenance: `/marketadmin freeze on|off`. New orders, instant trades and edits are refused;
  cancelling and the mailbox keep working. Online players are told; it stays on across restarts.
- Sales tax and buy-order fee (both off by default), `economy.sales-tax` and `economy.buy-order-fee` in config.yml.
  The tax is kept from the seller's proceeds of every trade at the rate fixed when the sell order was placed (lower
  rates by explicitly granted `eldrosmarket.tax.<tier>`; edits keep the rate). The fee (flat + percent, optional cap)
  is taken together with a buy order's escrow and never refunded; not charged for instant buys or edits;
  `eldrosmarket.fee.exempt` (not given to ops) skips it. Both leave the economy. Confirmations and messages show them.
  Schema v2 adds `orders.seller_tax_bps`, `orders.creation_fee`, `trades.tax`; a v1 database is backed up and
  upgraded on start (old orders keep no tax and no fee).
- Order editing: click an order in **My orders** to change its unit price and/or add items. A higher bid pays only
  the difference, added sell items leave the inventory, a lower bid's surplus goes to the mailbox. The changed order
  joins the back of the queue at its price. If the order traded while being edited, it is left as it was and anything
  taken for the change is returned. Crash-tested in every buy/sell crash window (it reuses those journal flows).
- Instant trading: **Buy now** / **Sell now** in the order book, `/market sell` (the stack in hand) and
  `/market sell all` (everything that has buyers), each with a confirmation showing quantity, total and the worst
  price accepted. Instant orders never rest in the book: they trade at prices no worse than the quoted one, and
  whatever cannot trade any more (the book moved) is returned to the mailbox in the same transaction. Covered by the
  crash-recovery tests for every buy/sell crash window.
- Notifications: fills of the same item at the same price arrive as one line ("Sold 12× Diamond" instead of
  twelve lines), a short "While you were away" summary on join (`notifications.join-summary-max-lines`), one
  configurable sound per batch (`notifications.sound`, empty for silence), and `/market alerts`
  (`/pazar bildirim`) to mute live alerts per player. Muting never touches the mailbox.
- Typed prices and quantities accept `k`, `m` and `b` shorthands (`2.5k`, `1m`); the result must still fit the
  economy's decimal places and is never rounded.
- Startup guards: minimum SQLite 3.47, refuses a changed money scale, refuses a database from a newer Minecraft
  version, backup before each schema migration.

### Tested
See [docs/06-qa-report.md](docs/06-qa-report.md). Paper 1.21.4 (Java 21 and 25): every feature with a real client and
bot players, 20 crash runs, PlaceholderAPI 2.12.3. Paper 26.3 (Java 25): real client (menus, anvil input, orders,
mailbox) and upgrade of an older database. Paper 1.21.11 (Java 25, Vault 1.7.3): bot players and upgrade of an older
database. EssentialsX 2.22.0 as the economy.
