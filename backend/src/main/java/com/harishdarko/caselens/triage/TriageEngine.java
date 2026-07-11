package com.harishdarko.caselens.triage;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class TriageEngine {
    private static final String FALLBACK_WARNING = "AI provider result unavailable or invalid";
    private final TicketRedactor redactor;
    private final TriageProvider primaryProvider;
    private final TriageProvider fallbackProvider;
    private final SemanticValidator validator;
    private final PriorityScorer priorityScorer;
    private final ModelInvocationRecorder invocationRecorder;
    private final Clock clock;

    public TriageEngine(TriageProvider primaryProvider, TriageProvider fallbackProvider,
            SemanticValidator validator, PriorityScorer priorityScorer) {
        this(new TicketRedactor(), primaryProvider, fallbackProvider, validator, priorityScorer,
                invocation -> {}, Clock.systemUTC());
    }

    public TriageEngine(TicketRedactor redactor, TriageProvider primaryProvider, TriageProvider fallbackProvider,
            SemanticValidator validator, PriorityScorer priorityScorer, ModelInvocationRecorder invocationRecorder,
            Clock clock) {
        this.redactor = Objects.requireNonNull(redactor);
        this.primaryProvider = Objects.requireNonNull(primaryProvider);
        this.fallbackProvider = Objects.requireNonNull(fallbackProvider);
        this.validator = Objects.requireNonNull(validator);
        this.priorityScorer = Objects.requireNonNull(priorityScorer);
        this.invocationRecorder = Objects.requireNonNull(invocationRecorder);
        this.clock = Objects.requireNonNull(clock);
    }

    public TriageDecision triage(TriageRequest request) {
        Objects.requireNonNull(request);
        RedactedTicket redacted = redactor.redact(request.subject(), request.message());
        Instant started = clock.instant();
        try {
            ProviderCall providerCall = primaryProvider.analyze(request, redacted);
            ProviderTriageResult providerResult = providerCall.result();
            ValidatedProviderResult validated = validator.validate(providerResult, redacted);
            Instant ended = clock.instant();
            recordSafely(request, primaryProvider.providerName(), providerResult.modelVersion(), providerResult.promptVersion(), started, ended, "SUCCESS", null,
                    providerCall.inputTokens(), providerCall.outputTokens());
            return decision(request, validated.providerResult(), validated.reliabilitySignal(), DecisionSource.AI_VALIDATED);
        } catch (RuntimeException failure) {
            Instant ended = clock.instant();
            recordSafely(request, primaryProvider.providerName(), primaryProvider.modelVersion(), primaryProvider.promptVersion(),
                    started, ended, "FAILURE", safeErrorCode(failure), null, null);
            ProviderTriageResult fallbackResult = fallbackProvider.analyze(request, redacted).result();
            List<String> warnings = new ArrayList<>(fallbackResult.warnings());
            warnings.add(FALLBACK_WARNING);
            ProviderTriageResult withWarning = new ProviderTriageResult(fallbackResult.category(), fallbackResult.urgency(),
                    fallbackResult.slaRisk(), fallbackResult.sentiment(), fallbackResult.summary(), fallbackResult.evidence(),
                    fallbackResult.policyIds(), fallbackResult.explanation(), fallbackResult.recommendedActions(),
                    fallbackResult.suggestedReply(), warnings, fallbackResult.modelVersion(), fallbackResult.promptVersion());
            return decision(request, withWarning, ReliabilitySignal.LOW, DecisionSource.RULES_FALLBACK);
        }
    }

    private TriageDecision decision(TriageRequest request, ProviderTriageResult result,
            ReliabilitySignal reliability, DecisionSource source) {
        PriorityResult priority = priorityScorer.score(new PriorityContext(result.urgency(), result.slaRisk(), result.category(),
                request.subject() + "\n" + request.message(), request.repeatContactCount()));
        return new TriageDecision(request.ticketId(), result.category(), result.urgency(), result.slaRisk(), result.sentiment(),
                result.summary(), result.evidence(), result.policyIds(), result.explanation(), result.recommendedActions(),
                result.suggestedReply(), reliability, result.warnings(), priority.score(), priority.appliedRules(), source,
                result.modelVersion(), result.promptVersion());
    }

    private void recordSafely(TriageRequest request, String provider, String modelVersion, String promptVersion, Instant started, Instant ended,
            String status, String errorCode, Long inputTokens, Long outputTokens) {
        try {
            invocationRecorder.record(new ModelInvocationRecord(request.ticketId(), provider, modelVersion, promptVersion,
                    started, ended, Math.max(0, Duration.between(started, ended).toMillis()), status, errorCode,
                    inputTokens, outputTokens));
        } catch (RuntimeException ignored) {
            // Invocation persistence must not turn a valid triage decision into a provider fallback.
        }
    }

    private String safeErrorCode(RuntimeException failure) {
        if (failure instanceof SemanticValidationException) return "SEMANTIC_VALIDATION";
        if (failure instanceof ProviderCallException providerFailure) return providerFailure.errorCode();
        return "PROVIDER_FAILURE";
    }
}
