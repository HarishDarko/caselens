CREATE TABLE outbox_event (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES demo_workspaces(id) ON DELETE CASCADE,
    ticket_id UUID NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
    event_type VARCHAR(32) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    publish_attempts INTEGER NOT NULL DEFAULT 0,
    last_error_code VARCHAR(80),
    CONSTRAINT chk_outbox_event_type CHECK (event_type = 'TRIAGE_REQUESTED'),
    CONSTRAINT chk_outbox_publish_attempts CHECK (publish_attempts >= 0)
);

CREATE INDEX idx_outbox_unpublished ON outbox_event (created_at, id) WHERE published_at IS NULL;

CREATE TABLE triage_job (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE REFERENCES outbox_event(id) ON DELETE CASCADE,
    workspace_id UUID NOT NULL REFERENCES demo_workspaces(id) ON DELETE CASCADE,
    ticket_id UUID NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
    content_version INTEGER NOT NULL,
    status VARCHAR(24) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    last_error_code VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    replayed_from_job_id UUID REFERENCES triage_job(id) ON DELETE SET NULL,
    CONSTRAINT chk_triage_job_status CHECK (status IN ('QUEUED', 'PROCESSING', 'COMPLETED', 'RETRYABLE_FAILURE', 'TERMINAL_FAILURE')),
    CONSTRAINT chk_triage_job_attempts CHECK (attempt_count >= 0),
    CONSTRAINT chk_triage_job_timestamps CHECK (updated_at >= created_at AND (completed_at IS NULL OR completed_at >= created_at))
);

CREATE INDEX idx_triage_job_ticket_created ON triage_job (ticket_id, content_version, created_at DESC);
CREATE INDEX idx_triage_job_workspace_status ON triage_job (workspace_id, status, updated_at DESC);
CREATE UNIQUE INDEX uq_triage_job_active_ticket_version
    ON triage_job (workspace_id, ticket_id, content_version)
    WHERE status IN ('QUEUED', 'PROCESSING');

CREATE TABLE triage_attempt (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES triage_job(id) ON DELETE CASCADE,
    attempt_number INTEGER NOT NULL,
    status VARCHAR(24) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    error_code VARCHAR(80),
    CONSTRAINT uq_triage_attempt_job_number UNIQUE (job_id, attempt_number),
    CONSTRAINT chk_triage_attempt_status CHECK (status IN ('PROCESSING', 'COMPLETED', 'RETRYABLE_FAILURE', 'TERMINAL_FAILURE')),
    CONSTRAINT chk_triage_attempt_number CHECK (attempt_number > 0),
    CONSTRAINT chk_triage_attempt_timestamps CHECK (completed_at IS NULL OR completed_at >= started_at)
);

CREATE INDEX idx_triage_attempt_job_started ON triage_attempt (job_id, started_at, attempt_number);

CREATE TABLE triage_result (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE REFERENCES outbox_event(id) ON DELETE CASCADE,
    workspace_id UUID NOT NULL REFERENCES demo_workspaces(id) ON DELETE CASCADE,
    ticket_id UUID NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
    category VARCHAR(32) NOT NULL,
    urgency VARCHAR(16) NOT NULL,
    sla_risk VARCHAR(16) NOT NULL,
    sentiment VARCHAR(16) NOT NULL,
    summary VARCHAR(300) NOT NULL,
    evidence_json TEXT NOT NULL,
    policy_ids_json TEXT NOT NULL,
    explanation VARCHAR(600) NOT NULL,
    recommended_actions_json TEXT NOT NULL,
    suggested_reply VARCHAR(1000) NOT NULL,
    reliability_signal VARCHAR(16) NOT NULL,
    warnings_json TEXT NOT NULL,
    priority_score INTEGER NOT NULL,
    applied_rules_json TEXT NOT NULL,
    decision_source VARCHAR(24) NOT NULL,
    model_version VARCHAR(80) NOT NULL,
    prompt_version VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_triage_result_priority CHECK (priority_score BETWEEN 0 AND 100),
    CONSTRAINT chk_triage_result_source CHECK (decision_source IN ('AI_VALIDATED', 'RULES_FALLBACK'))
);

CREATE INDEX idx_triage_result_workspace_created ON triage_result (workspace_id, created_at DESC);
CREATE INDEX idx_triage_result_ticket_created ON triage_result (ticket_id, created_at DESC);
