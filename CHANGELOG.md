# Changelog

All notable changes to Eldros Market. Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [0.1.0] - unreleased

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
- Item filter: blacklist, filled shulker boxes/bundles, blocked PDC namespaces. Per-player order limit permission.
- Optional order expiry (off by default).
- Startup guards: minimum SQLite 3.47, refuses a changed money scale, refuses a database from a newer Minecraft
  version, backup before each schema migration.

### Tested
See [docs/06-qa-report.md](docs/06-qa-report.md). Tested with real players on Paper 1.21.4 (Java 21 and 25) and
26.3 (Java 25) with VaultUnlocked and EssentialsX; startup checked on 1.21.11.
