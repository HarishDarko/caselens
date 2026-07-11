package com.harishdarko.caselens.triage;

import java.util.List;

public record TriageProcessingSnapshot(TriageJobSnapshot job, TriageResult result,
        List<TriageAttemptSnapshot> attempts) {}
