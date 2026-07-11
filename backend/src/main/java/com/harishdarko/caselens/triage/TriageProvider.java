package com.harishdarko.caselens.triage;

public interface TriageProvider {
    ProviderCall analyze(TriageRequest request, RedactedTicket redactedTicket);

    default String providerName() { return getClass().getSimpleName(); }
    default String modelVersion() { return "unknown"; }
    default String promptVersion() { return "triage-v1"; }
}
