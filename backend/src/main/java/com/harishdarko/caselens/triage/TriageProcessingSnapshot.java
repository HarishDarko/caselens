package com.harishdarko.caselens.triage;

import java.util.List;

/** {@code fallbackReason} is null unless the result is a rules fallback. */
public record TriageProcessingSnapshot(TriageJobSnapshot job, TriageResult result,
        List<TriageAttemptSnapshot> attempts, FallbackReason fallbackReason) {}
