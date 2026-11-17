-- Eldros Market schema v1 (docs/03 §5). SQLite >= 3.47 (D10). Money in minor units, times in epoch millis.
-- STRICT tables: wrong value types are rejected by the database itself.

CREATE TABLE meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
) STRICT;

CREATE TABLE players (
    uuid      TEXT PRIMARY KEY,
    last_name TEXT,
    locale    TEXT
) STRICT;

CREATE TABLE item_templates (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    material     TEXT    NOT NULL,
    item_bytes   BLOB    NOT NULL,
    data_version INTEGER NOT NULL,
    status       TEXT    NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INVALID')),
    created_at   INTEGER NOT NULL
) STRICT;

CREATE TABLE item_template_fingerprints (
    fingerprint TEXT    PRIMARY KEY,
    template_id INTEGER NOT NULL REFERENCES item_templates (id)
) STRICT;

CREATE TABLE item_template_history (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    template_id      INTEGER NOT NULL REFERENCES item_templates (id),
    old_bytes        BLOB    NOT NULL,
    old_data_version INTEGER NOT NULL,
    migrated_at      INTEGER NOT NULL
) STRICT;

CREATE TABLE item_template_merges (
    from_id   INTEGER NOT NULL,
    to_id     INTEGER NOT NULL REFERENCES item_templates (id),
    merged_at INTEGER NOT NULL,
    PRIMARY KEY (from_id)
) STRICT;

CREATE TABLE journal (
    op_id               TEXT    PRIMARY KEY,
    type                TEXT    NOT NULL CHECK (type IN ('BUY_CREATE', 'SELL_CREATE', 'CLAIM_MONEY', 'CLAIM_ITEM')),
    player_uuid         TEXT    NOT NULL,
    state               TEXT    NOT NULL CHECK (state IN ('PENDING', 'APPLIED', 'ABORTED', 'RECOVERED', 'QUARANTINED',
                                                          'RESOLVED_APPLIED', 'RESOLVED_NOT_APPLIED')),
    template_id         INTEGER,
    qty                 INTEGER,
    qty_effective       INTEGER,
    unit_price          INTEGER,
    amount              INTEGER,
    mailbox_id          INTEGER,
    order_id            INTEGER,
    balance_before      REAL,
    quarantine_reason   TEXT,
    observed_marker_qty INTEGER,
    attempts            INTEGER NOT NULL DEFAULT 0,
    created_at          INTEGER NOT NULL,
    updated_at          INTEGER NOT NULL,
    resolution          TEXT,
    resolved_qty        INTEGER,
    resolved_by         TEXT,
    resolved_at         INTEGER
) STRICT;

CREATE INDEX idx_journal_state ON journal (state, type);
CREATE INDEX idx_journal_player ON journal (player_uuid, state);

CREATE TABLE orders (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    owner_uuid   TEXT    NOT NULL,
    side         TEXT    NOT NULL CHECK (side IN ('BUY', 'SELL')),
    template_id  INTEGER NOT NULL REFERENCES item_templates (id),
    unit_price   INTEGER NOT NULL CHECK (unit_price > 0),
    qty_total    INTEGER NOT NULL CHECK (qty_total > 0),
    qty_filled   INTEGER NOT NULL DEFAULT 0 CHECK (qty_filled >= 0 AND qty_filled <= qty_total),
    escrow_money INTEGER NOT NULL CHECK (escrow_money >= 0),
    status       TEXT    NOT NULL CHECK (status IN ('ACTIVE', 'FILLED', 'CANCELLED', 'EXPIRED', 'FROZEN')),
    seq          INTEGER NOT NULL UNIQUE,
    origin_op_id TEXT    UNIQUE REFERENCES journal (op_id),
    created_at   INTEGER NOT NULL,
    updated_at   INTEGER NOT NULL,
    expires_at   INTEGER
) STRICT;

CREATE INDEX idx_orders_book ON orders (template_id, side, status, unit_price, seq);
CREATE INDEX idx_orders_owner ON orders (owner_uuid, status);

CREATE TABLE order_events (
    id       INTEGER PRIMARY KEY AUTOINCREMENT,
    order_id INTEGER NOT NULL REFERENCES orders (id),
    type     TEXT    NOT NULL CHECK (type IN ('CREATED', 'FILLED_PART', 'FILLED', 'CANCELLED', 'EXPIRED', 'RECOVERED', 'FROZEN')),
    qty      INTEGER NOT NULL,
    ref_id   INTEGER,
    at       INTEGER NOT NULL
) STRICT;

CREATE INDEX idx_order_events_order ON order_events (order_id);

CREATE TABLE trades (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    template_id   INTEGER NOT NULL REFERENCES item_templates (id),
    buy_order_id  INTEGER NOT NULL REFERENCES orders (id),
    sell_order_id INTEGER NOT NULL REFERENCES orders (id),
    buyer_uuid    TEXT    NOT NULL,
    seller_uuid   TEXT    NOT NULL,
    qty           INTEGER NOT NULL CHECK (qty > 0),
    unit_price    INTEGER NOT NULL CHECK (unit_price > 0),
    executed_at   INTEGER NOT NULL,
    CHECK (buyer_uuid <> seller_uuid)
) STRICT;

CREATE INDEX idx_trades_buyer ON trades (buyer_uuid, executed_at);
CREATE INDEX idx_trades_seller ON trades (seller_uuid, executed_at);
CREATE INDEX idx_trades_template ON trades (template_id, executed_at);

CREATE TABLE mailbox (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    owner_uuid  TEXT    NOT NULL,
    kind        TEXT    NOT NULL CHECK (kind IN ('ITEM', 'MONEY')),
    template_id INTEGER REFERENCES item_templates (id),
    qty         INTEGER NOT NULL DEFAULT 0 CHECK (qty >= 0),
    amount      INTEGER NOT NULL DEFAULT 0 CHECK (amount >= 0),
    reason      TEXT    NOT NULL,
    source_type TEXT    NOT NULL,
    source_ref  TEXT    NOT NULL,
    status      TEXT    NOT NULL CHECK (status IN ('AVAILABLE', 'CLAIMING', 'CLAIMED', 'QUARANTINED')),
    claim_op_id TEXT,
    version     INTEGER NOT NULL DEFAULT 0,
    created_at  INTEGER NOT NULL,
    updated_at  INTEGER NOT NULL,
    UNIQUE (source_type, source_ref, reason),
    CHECK ((kind = 'ITEM' AND template_id IS NOT NULL AND qty > 0 AND amount = 0)
        OR (kind = 'MONEY' AND template_id IS NULL AND qty = 0 AND amount > 0))
) STRICT;

CREATE INDEX idx_mailbox_owner ON mailbox (owner_uuid, status);

CREATE TABLE notifications (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    player_uuid  TEXT    NOT NULL,
    message_key  TEXT    NOT NULL,
    args         TEXT    NOT NULL,
    created_at   INTEGER NOT NULL,
    delivered_at INTEGER
) STRICT;

CREATE INDEX idx_notifications_pending ON notifications (player_uuid, delivered_at);
