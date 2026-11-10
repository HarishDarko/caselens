package com.harishdarko.caselens.triage;

import java.util.Objects;

public record PriorityContext(Urgency urgency, SlaRisk slaRisk, Category category, String normalizedText,
        int repeatContactCount, PackRules pack) {
    public PriorityContext {
        Objects.requireNonNull(pack, "Industry pack is required");
    }

    /** A context scored with the default industry pack. */
    public PriorityContext(Urgency urgency, SlaRisk slaRisk, Category category, String normalizedText, int repeatContactCount) {
        this(urgency, slaRisk, category, normalizedText, repeatContactCount, PackRules.evCharging());
    }
}
