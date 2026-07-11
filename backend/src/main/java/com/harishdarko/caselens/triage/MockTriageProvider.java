package com.harishdarko.caselens.triage;

public final class MockTriageProvider implements TriageProvider {
    private final RuleBasedTriageProvider delegate = new RuleBasedTriageProvider("mock-v1", "triage-v1");

    @Override
    public ProviderCall analyze(TriageRequest request, RedactedTicket redactedTicket) {
        return delegate.analyze(request, redactedTicket);
    }

    @Override public String providerName() { return "mock"; }
    @Override public String modelVersion() { return "mock-v1"; }
    @Override public String promptVersion() { return "triage-v1"; }
}
