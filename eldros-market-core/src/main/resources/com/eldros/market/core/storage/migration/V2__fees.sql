-- Eldros Market schema v2: sales tax and buy-order creation fee (0.2.0).
-- Existing orders and trades keep 0 (no tax, no fee): nobody is charged for something they did not agree to.

-- Sales tax rate of a sell order in basis points (250 = 2.5%), decided when the order was placed.
ALTER TABLE orders ADD COLUMN seller_tax_bps INTEGER NOT NULL DEFAULT 0 CHECK (seller_tax_bps BETWEEN 0 AND 10000);

-- Fee paid when a buy order was created (minor units). It left the economy; it is never part of the escrow.
ALTER TABLE orders ADD COLUMN creation_fee INTEGER NOT NULL DEFAULT 0 CHECK (creation_fee >= 0);

-- Tax kept from the seller's proceeds of this trade (minor units).
ALTER TABLE trades ADD COLUMN tax INTEGER NOT NULL DEFAULT 0 CHECK (tax >= 0);
