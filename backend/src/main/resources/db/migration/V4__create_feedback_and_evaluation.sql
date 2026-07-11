CREATE TABLE triage_feedback (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES demo_workspaces(id) ON DELETE CASCADE,
    ticket_id UUID NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
    triage_result_id UUID NOT NULL REFERENCES triage_result(id) ON DELETE CASCADE,
    corrected_category VARCHAR(32),
    corrected_urgency VARCHAR(16),
    corrected_sla_risk VARCHAR(16),
    note VARCHAR(500) NOT NULL,
    submitted_by VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_feedback_has_correction CHECK (
        corrected_category IS NOT NULL OR corrected_urgency IS NOT NULL OR corrected_sla_risk IS NOT NULL
    ),
    CONSTRAINT chk_feedback_category CHECK (
        corrected_category IS NULL OR corrected_category IN ('BILLING', 'CHARGING_SESSION', 'CHARGER_HARDWARE', 'CONNECTIVITY', 'ACCOUNT_ACCESS', 'REFUND', 'GENERAL')
    ),
    CONSTRAINT chk_feedback_urgency CHECK (
        corrected_urgency IS NULL OR corrected_urgency IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')
    ),
    CONSTRAINT chk_feedback_sla_risk CHECK (
        corrected_sla_risk IS NULL OR corrected_sla_risk IN ('LOW', 'MEDIUM', 'HIGH')
    )
);

CREATE INDEX idx_triage_feedback_workspace_created ON triage_feedback (workspace_id, created_at DESC, id DESC);
CREATE INDEX idx_triage_feedback_result_created ON triage_feedback (triage_result_id, created_at DESC, id DESC);

CREATE TABLE evaluation_ground_truth (
    scenario_key VARCHAR(80) PRIMARY KEY,
    expected_category VARCHAR(32) NOT NULL,
    expected_urgency VARCHAR(16) NOT NULL,
    CONSTRAINT chk_ground_truth_category CHECK (
        expected_category IN ('BILLING', 'CHARGING_SESSION', 'CHARGER_HARDWARE', 'CONNECTIVITY', 'ACCOUNT_ACCESS', 'REFUND', 'GENERAL')
    ),
    CONSTRAINT chk_ground_truth_urgency CHECK (
        expected_urgency IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')
    )
);

INSERT INTO evaluation_ground_truth (scenario_key, expected_category, expected_urgency) VALUES
    ('payment-captured-session-not-started', 'CHARGING_SESSION', 'HIGH'),
    ('charger-offline-site-wide', 'CONNECTIVITY', 'CRITICAL'),
    ('connector-fault-single-port', 'CHARGER_HARDWARE', 'MEDIUM'),
    ('ocpp-heartbeat-timeout', 'CONNECTIVITY', 'HIGH'),
    ('duplicate-charge-refund', 'REFUND', 'HIGH'),
    ('account-rfid-authorization', 'ACCOUNT_ACCESS', 'MEDIUM'),
    ('slow-charging-performance', 'CHARGING_SESSION', 'MEDIUM'),
    ('receipt-request-general', 'GENERAL', 'LOW');
