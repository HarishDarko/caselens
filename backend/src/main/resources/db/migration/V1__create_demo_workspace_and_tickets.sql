CREATE TABLE demo_workspaces (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_demo_workspace_expiry CHECK (expires_at > created_at)
);

CREATE INDEX idx_demo_workspaces_expires_at ON demo_workspaces (expires_at);

CREATE TABLE tickets (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES demo_workspaces(id) ON DELETE CASCADE,
    display_id VARCHAR(9) NOT NULL,
    subject VARCHAR(160) NOT NULL,
    message VARCHAR(5000) NOT NULL,
    channel VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    scenario_key VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_ticket_display_id CHECK (display_id ~ '^CL-[0-9A-F]{6}$'),
    CONSTRAINT chk_ticket_subject_length CHECK (char_length(subject) BETWEEN 5 AND 160),
    CONSTRAINT chk_ticket_message_length CHECK (char_length(message) BETWEEN 20 AND 5000),
    CONSTRAINT chk_ticket_channel CHECK (channel IN ('WEB', 'EMAIL', 'CHAT', 'IMPORT')),
    CONSTRAINT chk_ticket_status CHECK (status IN ('NEW', 'QUEUED', 'PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT chk_ticket_timestamps CHECK (updated_at >= created_at)
);

CREATE UNIQUE INDEX uq_tickets_workspace_display_id ON tickets (workspace_id, display_id);
CREATE INDEX idx_tickets_workspace_created ON tickets (workspace_id, created_at DESC, id DESC);
CREATE INDEX idx_tickets_created_at ON tickets (created_at DESC);
