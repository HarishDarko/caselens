package com.harishdarko.caselens.triage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "model_invocations")
public class ModelInvocation {
    @Id private UUID id;
    @Column(name = "ticket_id", nullable = false) private UUID ticketId;
    @Column(nullable = false, length = 80) private String provider;
    @Column(name = "model_version", nullable = false, length = 80) private String modelVersion;
    @Column(name = "prompt_version", nullable = false, length = 80) private String promptVersion;
    @Column(name = "started_at", nullable = false) private Instant startedAt;
    @Column(name = "ended_at", nullable = false) private Instant endedAt;
    @Column(name = "latency_ms", nullable = false) private long latencyMs;
    @Column(nullable = false, length = 20) private String status;
    @Column(name = "sanitized_error_code", length = 80) private String sanitizedErrorCode;
    @Column(name = "input_tokens") private Long inputTokens;
    @Column(name = "output_tokens") private Long outputTokens;

    protected ModelInvocation() {}

    private ModelInvocation(ModelInvocationRecord record) {
        this.id = UUID.randomUUID();
        this.ticketId = Objects.requireNonNull(record.ticketId());
        this.provider = required(record.provider());
        this.modelVersion = required(record.modelVersion());
        this.promptVersion = required(record.promptVersion());
        this.startedAt = Objects.requireNonNull(record.startedAt());
        this.endedAt = Objects.requireNonNull(record.endedAt());
        this.latencyMs = record.latencyMs();
        this.status = required(record.status());
        this.sanitizedErrorCode = record.sanitizedErrorCode();
        this.inputTokens = record.inputTokens();
        this.outputTokens = record.outputTokens();
        if (latencyMs < 0 || endedAt.isBefore(startedAt)) throw new IllegalArgumentException("Invalid invocation timing");
    }

    public static ModelInvocation from(ModelInvocationRecord record) { return new ModelInvocation(record); }

    private String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Invocation metadata is incomplete");
        return value;
    }

    public UUID getId() { return id; }
    public UUID getTicketId() { return ticketId; }
    public String getProvider() { return provider; }
    public String getModelVersion() { return modelVersion; }
    public String getPromptVersion() { return promptVersion; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getEndedAt() { return endedAt; }
    public long getLatencyMs() { return latencyMs; }
    public String getStatus() { return status; }
    public String getSanitizedErrorCode() { return sanitizedErrorCode; }
    public Long getInputTokens() { return inputTokens; }
    public Long getOutputTokens() { return outputTokens; }
}
