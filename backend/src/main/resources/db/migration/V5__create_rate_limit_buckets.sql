CREATE TABLE rate_limit_buckets (
    rate_key VARCHAR(255) PRIMARY KEY,
    window_started_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    request_count INTEGER NOT NULL CHECK (request_count > 0)
);

CREATE INDEX idx_rate_limit_buckets_expires_at ON rate_limit_buckets (expires_at);
