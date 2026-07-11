package com.harishdarko.caselens.triage;

public record ProviderUsageSummary(String provider, long requests, long successes, long failures,
        long inputTokens, long outputTokens) {}
