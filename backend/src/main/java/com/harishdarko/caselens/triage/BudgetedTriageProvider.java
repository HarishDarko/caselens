package com.harishdarko.caselens.triage;

import com.harishdarko.caselens.security.AiCallBudget;
import java.util.Objects;

/**
 * Spends one unit of the global daily AI budget before each provider call. When the budget is spent it throws a
 * provider failure, and TriageEngine answers with the rules fallback as it does for any other provider failure.
 */
public final class BudgetedTriageProvider implements TriageProvider {
    static final String BUDGET_EXHAUSTED = "AI_BUDGET_EXHAUSTED";
    private final TriageProvider delegate;
    private final AiCallBudget budget;

    public BudgetedTriageProvider(TriageProvider delegate, AiCallBudget budget) {
        this.delegate = Objects.requireNonNull(delegate);
        this.budget = Objects.requireNonNull(budget);
    }

    @Override
    public ProviderCall analyze(TriageRequest request, RedactedTicket redactedTicket) {
        if (!budget.tryAcquire()) {
            throw new ProviderCallException("The daily AI call budget is spent", BUDGET_EXHAUSTED);
        }
        return delegate.analyze(request, redactedTicket);
    }

    @Override public String providerName() { return delegate.providerName(); }
    @Override public String modelVersion() { return delegate.modelVersion(); }
    @Override public String promptVersion() { return delegate.promptVersion(); }
}
