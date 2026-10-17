-- Schema migration for Nnomm Flash-Deal Claim Service

CREATE TABLE IF NOT EXISTS deals (
    id UUID PRIMARY KEY,
    restaurant_id UUID NOT NULL,
    title VARCHAR(100) NOT NULL,
    price_paise BIGINT NOT NULL CHECK (price_paise >= 1 AND price_paise <= 1000000),
    stock_total INT NOT NULL CHECK (stock_total >= 1 AND stock_total <= 10000),
    stock_available INT NOT NULL CHECK (stock_available >= 0),
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT check_starts_before_ends CHECK (starts_at < ends_at),
    CONSTRAINT check_stock_available_le_total CHECK (stock_available <= stock_total)
);

CREATE TABLE IF NOT EXISTS claims (
    id UUID PRIMARY KEY,
    deal_id UUID NOT NULL REFERENCES deals(id) ON DELETE RESTRICT,
    customer_id UUID NOT NULL,
    quantity INT NOT NULL CHECK (quantity >= 1 AND quantity <= 3),
    amount_paise BIGINT NOT NULL CHECK (amount_paise > 0),
    status VARCHAR(30) NOT NULL CHECK (status IN ('RESERVED', 'PAID', 'EXPIRED', 'NEEDS_REVIEW', 'REFUND_PENDING')),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Crucial concurrency constraint: Enforces at most one active claim (RESERVED, PAID, NEEDS_REVIEW)
-- per customer per deal across all concurrent transactions and instances.
CREATE UNIQUE INDEX IF NOT EXISTS uq_active_customer_claim
ON claims (customer_id, deal_id)
WHERE status IN ('RESERVED', 'PAID', 'NEEDS_REVIEW');

-- Index for the background expiry reaper to locate expired RESERVED claims quickly
CREATE INDEX IF NOT EXISTS idx_claims_status_expires_at
ON claims (status, expires_at)
WHERE status = 'RESERVED';

-- Index for customer claim lookup
CREATE INDEX IF NOT EXISTS idx_claims_customer_id
ON claims (customer_id);

CREATE TABLE IF NOT EXISTS idempotency_keys (
    key VARCHAR(64) NOT NULL,
    customer_id UUID NOT NULL,
    deal_id UUID NOT NULL,
    quantity INT NOT NULL,
    status_code INT NULL,
    response_body TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    locked_until TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (customer_id, key)
);

CREATE TABLE IF NOT EXISTS payment_events (
    event_id VARCHAR(128) PRIMARY KEY,
    claim_id UUID NOT NULL,
    type VARCHAR(50) NOT NULL,
    amount_paise BIGINT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    result_status VARCHAR(50) NOT NULL
);
