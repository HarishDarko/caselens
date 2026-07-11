CREATE TABLE model_invocations (
    id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
    provider VARCHAR(80) NOT NULL,
    model_version VARCHAR(80) NOT NULL,
    prompt_version VARCHAR(80) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ NOT NULL,
    latency_ms BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    sanitized_error_code VARCHAR(80),
    input_tokens BIGINT,
    output_tokens BIGINT,
    CONSTRAINT chk_model_invocation_latency CHECK (latency_ms >= 0),
    CONSTRAINT chk_model_invocation_status CHECK (status IN ('SUCCESS', 'FAILURE')),
    CONSTRAINT chk_model_invocation_input_tokens CHECK (input_tokens IS NULL OR input_tokens >= 0),
    CONSTRAINT chk_model_invocation_output_tokens CHECK (output_tokens IS NULL OR output_tokens >= 0),
    CONSTRAINT chk_model_invocation_timestamps CHECK (ended_at >= started_at)
);

CREATE INDEX idx_model_invocations_ticket_started ON model_invocations (ticket_id, started_at DESC);
