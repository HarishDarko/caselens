package com.harishdarko.caselens.triage;

public record PriorityContext(Urgency urgency, SlaRisk slaRisk, Category category, String normalizedText,
        int repeatContactCount) {}
