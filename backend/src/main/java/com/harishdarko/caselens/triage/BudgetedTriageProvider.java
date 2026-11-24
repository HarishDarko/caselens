package com.harishdarko.caselens.triage;

import com.harishdarko.caselens.security.AiCallBudget;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spends one unit of the global daily AI budget, and of the calling client's daily share of it, before the first
 * provider call of each delivery of a queued job (a rate-limited job has at most 2 deliveries, so it spends at most 2
 * units; the in-call retry shares its delivery's unit; see AiCallBudget). When either is spent, or the check itself
 * fails, it throws and the provider is never called: TriageEngine answers with the rules fallback as it does for any
 * other provider failure. The error code on the model-invocation record says which one ran out.
 */
public final class BudgetedTriageProvider implements TriageProvider {
    private static final Logger log = LoggerFactory.getLogger(BudgetedTriageProvider.class);
    static final String BUDGET_EXHAUSTED = "AI_BUDGET_EXHAUSTED";
    static final String SHARE_EXHAUSTED = "AI_BUDGET_SHARE_EXHAUSTED";
    private final TriageProvider delegate;
    private final AiCallBudget budget;
    private final Function<UUID, Optional<String>> clientKeys;

    /** No per-client share: every call counts against the global budget only. */
    public BudgetedTriageProvider(TriageProvider delegate, AiCallBudget budget) {
        this(delegate, budget, ticketId -> Optional.empty());
    }

    /** {@code clientKeys} finds the client key of the workspace that owns a ticket. */
    public BudgetedTriageProvider(TriageProvider delegate, AiCallBudget budget,
            Function<UUID, Optional<String>> clientKeys) {
        this.delegate = Objects.requireNonNull(delegate);
        this.budget = Objects.requireNonNull(budget);
        this.clientKeys = Objects.requireNonNull(clientKeys);
    }

    @Override
    public ProviderCall analyze(TriageRequest request, RedactedTicket redactedTicket) {
        // Nothing is spent when there is no time for a call; the provider checks again after the lookups below.
        if (request.outOfTime()) {
            throw new ProviderCallException("Too little time left for a provider call", TriageRequest.TIME_BUDGET_EXHAUSTED);
        }
        switch (budget.tryAcquire(clientKeyOf(request.ticketId()))) {
            case CLIENT_SHARE_SPENT -> throw new ProviderCallException(
                    "This client's daily share of AI calls is spent", SHARE_EXHAUSTED);
            case BUDGET_SPENT -> throw new ProviderCallException("The daily AI call budget is spent", BUDGET_EXHAUSTED);
            case GRANTED -> { }
        }
        return delegate.analyze(request, redactedTicket);
    }

    /**
     * A failed lookup counts the call against the global budget only, which still caps the cost; it must not turn a
     * triage into a fallback on its own. It is logged, because while lookups fail the per-client share is off. The
     * log names the ticket and the error type only, never the client key or an address.
     */
    private String clientKeyOf(UUID ticketId) {
        try {
            return clientKeys.apply(ticketId).orElse(null);
        } catch (RuntimeException lookupFailed) {
            log.warn("triage.ai_share_lookup_failed ticketId={} errorType={} effect=global_budget_only",
                    ticketId, lookupFailed.getClass().getSimpleName());
            return null;
        }
    }

    @Override public String providerName() { return delegate.providerName(); }
    @Override public String modelVersion() { return delegate.modelVersion(); }
    @Override public String promptVersion() { return delegate.promptVersion(); }
}
