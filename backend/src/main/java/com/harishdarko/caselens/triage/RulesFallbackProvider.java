package com.harishdarko.caselens.triage;

public final class RulesFallbackProvider implements TriageProvider {
    private final RuleBasedTriageProvider delegate = new RuleBasedTriageProvider("rules-v1", "triage-v1");

    @Override
    public ProviderCall analyze(TriageRequest request, RedactedTicket redactedTicket) {
        return delegate.analyze(request, redactedTicket);
    }

    @Override public String providerName() { return "rules"; }
    @Override public String modelVersion() { return "rules-v1"; }
    @Override public String promptVersion() { return "triage-v1"; }
}
