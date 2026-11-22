package com.harishdarko.caselens.triage;

import com.harishdarko.caselens.security.AiCallBudget;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Spends one unit of the global daily AI budget, and of the calling client's daily share of it, before each provider
 * call. When either is spent it throws a provider failure, and TriageEngine answers with the rules fallback as it does
 * for any other provider failure. The error code on the model-invocation record says which one ran out.
 */
public final class BudgetedTriageProvider implements TriageProvider {
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
     * triage into a fallback on its own.
     */
    private String clientKeyOf(UUID ticketId) {
        try {
            return clientKeys.apply(ticketId).orElse(null);
        } catch (RuntimeException lookupFailed) {
            return null;
        }
    }

    @Override public String providerName() { return delegate.providerName(); }
    @Override public String modelVersion() { return delegate.modelVersion(); }
    @Override public String promptVersion() { return delegate.promptVersion(); }
}
